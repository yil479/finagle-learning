package com.travelmsg.filter

import com.travelmsg.domain.{Decision, FlightDelayed, PriceDropped, Send, Suppress, TravelEvent, TripStartingSoon}
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.{Duration, Future, Time}

import scala.collection.concurrent.TrieMap
import com.travelmsg.domain.FlightCancelled
import com.travelmsg.trace.Trace

/**
 * A second Filter in front of DecisionService, same shape as
 * FrequencyCapFilter but a different concern: when two different event
 * types both want to Send to the same traveler close together, only the
 * higher-priority one should actually go through. The loser is dropped
 * (Suppressed with a reason) - per our design discussion, "deferred" and
 * "bundled" aren't implemented, since neither has the infrastructure it'd
 * need yet (a scheduler, message composition).
 *
 * Composition order matters here: in DecisionController, this Filter sits
 * *inside* FrequencyCapFilter (arbitration resolves competing types first;
 * frequency capping then governs overall pacing on whatever arbitration
 * decided). One known simplification worth knowing about: this Filter
 * records "last sent" based on what arbitration decided, before
 * FrequencyCapFilter gets a chance to veto it - so if frequency capping
 * later suppresses a message arbitration approved, this Filter's
 * bookkeeping won't know that. Fixing that properly needs the filters to
 * share state, which is more machinery than this slice calls for.
 */
class PriorityArbitrationFilter extends SimpleFilter[TravelEvent, Decision] {

  private val ArbitrationWindow: Duration = 60.minutes

  // travelerId -> (the event we last decided to Send for them, when)
  // TrieMap for the same reason as FrequencyCapFilter: concurrent requests.
  private val lastSentEvent = TrieMap.empty[String, (TravelEvent, Time)]

  // TODO(me): Slice 6 adds FlightCancelled, ranked above everything else
  // (it's transactional - always the most urgent thing competing for a
  // traveler's attention). Add one more case here:
  //   case _: FlightCancelled => 4
  // and remember to add FlightCancelled to this file's import line.
  private def priorityOf(event: TravelEvent): Int = event match {
    case _: FlightCancelled => 4
    case _: FlightDelayed => 3
    case _: TripStartingSoon => 2
    case _: PriceDropped => 1
  }

  override def apply(event: TravelEvent, service: Service[TravelEvent, Decision]): Future[Decision] = {
    // `event.travelerId` directly, now that TravelEvent declares it as an
    // abstract member - the travelerIdOf match this used to need is gone.
    val travelerId = event.travelerId

    service(event).map {
      case send @ Send(_, _) =>
        lastSentEvent.get(travelerId) match {
          case Some((priorEvent, priorTime))
              if Time.now - priorTime < ArbitrationWindow && priorityOf(priorEvent) > priorityOf(event) =>
              Trace.record(s"PriorityArbitrationFilter: lost to active ${priorEvent.getClass.getSimpleName}")
              Suppress(s"Lower priority than an active ${priorEvent.getClass.getSimpleName} for this traveler")
          case _ =>
            Trace.record("PriorityArbitrationFilter: no higher-priority competitor, recording send")
            lastSentEvent.update(travelerId, (event, Time.now))
            send
        }
      case suppress: Suppress => suppress
    }
  }
}
