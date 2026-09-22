package com.travelmsg.service

import com.travelmsg.domain.{Decision, FlightDelayed, Send, Suppress, TravelEvent, FlightCancelled}
import com.twitter.finagle.Service
import com.twitter.util.Future
import com.travelmsg.domain.PriceDropped
import com.travelmsg.domain.TripStartingSoon

/**
 * `Service[Req, Rep]` is Finagle's core abstraction - conceptually a
 * `Function[Req, Future[Rep]]` with no annotations or component scanning
 * involved. Per CLAUDE.md, business logic lives in Service[TravelEvent,
 * Decision], not Service[Request, Response]: the HTTP layer (see
 * DecisionController) is a thin shell around this.
 *
 * IMPORTANT: the `Future` below is `com.twitter.util.Future`, not
 * `scala.concurrent.Future`. No implicit ExecutionContext is needed or
 * wanted. This runs on Finagle's Netty event loop, so a blocking call in
 * here (not relevant yet in Slice 1) would have to go through a
 * `FuturePool` instead of running inline.
 */
class DecisionService extends Service[TravelEvent, Decision] {

  private val DelayThresholdMinutes = 30

  override def apply(event: TravelEvent): Future[Decision] = event match{
    
    //
    case FlightDelayed(travelerId, flightId, delayMinutes, timezone,_) =>
    if (delayMinutes >= DelayThresholdMinutes) {
      Future.value(Send(travelerId, MessageTemplates.messageFor(event)))
    } else {
      Future.value(Suppress(s"Delay of $delayMinutes minutes is under the $DelayThresholdMinutes-minute threshold"))
    }

    case PriceDropped(travelerId, tripId, oldPrice, newPrice, timezone, _) =>
      Future.value(Send(travelerId, MessageTemplates.messageFor(event)))

    case TripStartingSoon(travelerId, tripId, hoursUntilDeparture, timezone, _) =>
      Future.value(Send(travelerId, MessageTemplates.messageFor(event)))

    // TODO(me): add a case for FlightCancelled here, once you've added it
    // to TravelEvent.scala. Same unconditional-Send shape as PriceDropped
    // and TripStartingSoon above - a cancellation is always worth sending,
    // no threshold to check. Use MessageTemplates.messageFor(event) for
    // the message, same as the other cases.
    case FlightCancelled(travelerId, flightId, timezone, _) => 
      Future.value(Send(travelerId, MessageTemplates.messageFor(event)))
    }
    
}
