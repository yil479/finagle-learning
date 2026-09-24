package com.travelmsg.http

import com.travelmsg.delivery.DeliveryService
import com.travelmsg.domain.{Decision, FlightCancelled, FlightDelayed, PriceDropped, Send, Suppress, TravelEvent, TripStartingSoon}
import com.travelmsg.filter.{FrequencyCapFilter, IdempotencyFilter, PriorityArbitrationFilter, QuietHoursFilter}
import com.travelmsg.service.{DecisionLogger, DecisionService}
import com.travelmsg.trace.Trace
import com.twitter.finagle.Service
import com.twitter.finatra.http.Controller
import com.twitter.util.Future
import javax.inject.{Inject, Singleton}

// Request/response shapes are kept separate from the domain model: Jackson
// binds JSON directly to these case classes (the parameter type in the
// route block below is how Finatra knows what to parse the body into), and
// the controller maps between them and the domain types the Service uses.
// One request case class per event type, since we're not teaching Jackson
// polymorphic deserialization (discriminator fields, @JsonSubTypes) here -
// that's real complexity this slice doesn't need.
//
// `timezone` on every request: per CLAUDE.md, quiet hours use the
// traveler's *current* timezone (destination during a trip, home
// otherwise) - resolving which one that is isn't this system's job yet
// (no traveler-profile store exists until Slice 6+), so the caller is
// expected to have already resolved it and just tells us.
//
// `eventId` on every request: per CLAUDE.md, upstream events can arrive
// more than once, and every send needs to be keyed and deduped - the
// caller assigns a unique ID per event occurrence (a UUID, in practice)
// and IdempotencyFilter uses it to recognize a retry.
case class DecisionRequest(travelerId: String, flightId: String, delayMinutes: Int, timezone: String, eventId: String) {
  def toDomain: FlightDelayed = FlightDelayed(
    travelerId = travelerId,
    flightId = flightId,
    delayMinutes = delayMinutes,
    timezone = timezone,
    eventId = eventId)
}

case class PriceDroppedRequest(
  travelerId: String,
  tripId: String,
  oldPrice: BigDecimal,
  newPrice: BigDecimal,
  timezone: String,
  eventId: String) {
  def toDomain: PriceDropped = PriceDropped(
    travelerId = travelerId,
    tripId = tripId,
    oldPrice = oldPrice,
    newPrice = newPrice,
    timezone = timezone,
    eventId = eventId)
}

case class TripStartingSoonRequest(
  travelerId: String,
  tripId: String,
  hoursUntilDeparture: Int,
  timezone: String,
  eventId: String) {
  def toDomain: TripStartingSoon = TripStartingSoon(
    travelerId = travelerId,
    tripId = tripId,
    hoursUntilDeparture = hoursUntilDeparture,
    timezone = timezone,
    eventId = eventId)
}

case class FlightCancelledRequest(travelerId: String, flightId: String, timezone: String, eventId: String) {
  def toDomain: FlightCancelled =
    FlightCancelled(travelerId = travelerId, flightId = flightId, timezone = timezone, eventId = eventId)
}

case class DecisionResponse(decision: String, detail: String)

object DecisionResponse {
  def fromDomain(decision: Decision): DecisionResponse = decision match {
    case Send(_, message) => DecisionResponse("send", message)
    case Suppress(reason) => DecisionResponse("suppress", reason)
  }
}

// Separate from DecisionResponse, not an added field on it: adding `trace`
// to DecisionResponse itself would show up on every real response too
// (even as an empty list), breaking every existing test's exact
// withJsonBody match. A dry-run needs its own response shape and its own
// routes, not a flag on the existing ones.
case class DryRunResponse(decision: String, detail: String, trace: List[String])

/**
 * Thin shell: decode JSON, delegate to the Service, encode the result.
 * No decision logic lives here - that's DecisionService's job, and that's
 * what's unit-tested directly with no HTTP involved.
 *
 * Spring comparison: `@Inject` here plays the role of `@Autowired` /
 * constructor injection via Guice, Finatra's DI container. There's no
 * `@RestController` / `@PostMapping` scanning - `post("/decisions") { ... }`
 * below is imperative route registration, run once at startup by
 * DecisionController's constructor.
 */
@Singleton
class DecisionController @Inject() (
  decisionService: DecisionService,
  frequencyCapFilter: FrequencyCapFilter,
  priorityArbitrationFilter: PriorityArbitrationFilter,
  quietHoursFilter: QuietHoursFilter,
  idempotencyFilter: IdempotencyFilter,
  deliveryService: DeliveryService,
  decisionLogger: DecisionLogger)
    extends Controller {

  // `filter.andThen(filter).andThen(filter).andThen(filter).andThen(service)`
  // is the hand-written pipeline CLAUDE.md describes: no framework wires
  // this together, it's a plain chain of method calls producing a new
  // Service. Reads left to right, outermost to innermost:
  //   idempotencyFilter -> frequencyCapFilter -> priorityArbitrationFilter -> quietHoursFilter -> decisionService
  // idempotencyFilter is now the outermost layer - a duplicate event needs
  // to short-circuit before any of the others ever see it, so a retry
  // doesn't get double-counted against the frequency cap or arbitration
  // state. quietHoursFilter stays innermost, for the same reason as
  // before (see PriorityArbitrationFilter's comment for the tradeoff that
  // ordering implies).
  private val pipeline: Service[TravelEvent, Decision] =
    idempotencyFilter
      .andThen(frequencyCapFilter)
      .andThen(priorityArbitrationFilter)
      .andThen(quietHoursFilter)
      .andThen(decisionService)

  // Decide, then - only for an actual Send - attempt real delivery, then
  // log the *final* outcome (post-delivery, since a failed delivery turns
  // a Send into a Suppress - the log should reflect what really happened,
  // not what the pipeline alone would have done). A Suppress straight from
  // the pipeline never reaches DeliveryService at all; there's nothing to
  // deliver, but it's still logged. Future#map/flatMap, not a callback
  // chain - com.twitter.util.Future supports the same idioms you'd use in
  // a for-comprehension.
  private def decide(event: TravelEvent): Future[DecisionResponse] =
    pipeline(event)
      .flatMap {
        case send: Send => deliveryService.deliver(event, send)
        case suppress: Suppress => Future.value(suppress)
      }
      .flatMap(decision => decisionLogger.log(event, decision))
      .map(DecisionResponse.fromDomain)

  // Dry-run: same pipeline, but no delivery and no send-log entry - this
  // is a simulation, nothing should actually happen. `Trace.withRecording`
  // turns trace capture on for exactly this one call; the same filters
  // that silently no-op their `Trace.record` calls on the real path now
  // actually record into the buffer this reads back via `Trace.current`.
  //
  // Known limitation, worth knowing about: this still runs through the
  // *real* pipeline instance - the same frequency-cap/arbitration/
  // idempotency state a real request would touch. A dry run isn't fully
  // side-effect-free; it can still mark an event_id as seen, or count
  // against a traveler's frequency cap. Making it truly side-effect-free
  // would mean threading a "dry run" flag through every Filter, which is
  // exactly the kind of invasive change using Trace/Contexts.local was
  // meant to avoid - not worth it for what this slice needs.
  private def dryRun(event: TravelEvent): Future[DryRunResponse] =
    Trace.withRecording {
      pipeline(event).map { decision =>
        val response = DecisionResponse.fromDomain(decision)
        DryRunResponse(response.decision, response.detail, Trace.current)
      }
    }

  post("/decisions") { request: DecisionRequest =>
    decide(request.toDomain)
  }

  post("/decisions/price-dropped") { request: PriceDroppedRequest =>
    decide(request.toDomain)
  }

  post("/decisions/trip-starting-soon") { request: TripStartingSoonRequest =>
    decide(request.toDomain)
  }

  post("/decisions/flight-cancelled") { request: FlightCancelledRequest =>
    decide(request.toDomain)
  }

  post("/decisions/dry-run") { request: DecisionRequest =>
    dryRun(request.toDomain)
  }

  post("/decisions/price-dropped/dry-run") { request: PriceDroppedRequest =>
    dryRun(request.toDomain)
  }

  post("/decisions/trip-starting-soon/dry-run") { request: TripStartingSoonRequest =>
    dryRun(request.toDomain)
  }

  post("/decisions/flight-cancelled/dry-run") { request: FlightCancelledRequest =>
    dryRun(request.toDomain)
  }
}
