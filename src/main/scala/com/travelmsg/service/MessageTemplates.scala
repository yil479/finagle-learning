package com.travelmsg.service

import com.travelmsg.domain.TravelEvent
import com.travelmsg.domain.{FlightDelayed, PriceDropped, TravelEvent,TripStartingSoon}
import com.travelmsg.domain.FlightCancelled
/**
 * Slice 5: the actual message text moves out of DecisionService and into
 * templates here, with {placeholder} tokens filled in from each event's
 * own fields. Kept deliberately simple for now - a hardcoded Map, not a
 * database or a UI. Those are natural next steps once persistence and the
 * React console exist later in the roadmap.
 */
object MessageTemplates {

  // TODO(me): one template string per event type, using {placeholderName}
  // tokens for anything that should be filled in - e.g.
  //   "FlightDelayed" -> "Flight {flightId} is delayed {delayMinutes} minutes"
  // Keys must match `event.getClass.getSimpleName` (see `messageFor` below)
  // - the same "get the type name as a String" trick you already used in
  // PriorityArbitrationFilter.
  private val templates: Map[String, String] = Map(
    "FlightDelayed" -> "Flight {flightId} is delayed {delayMinutes} minutes",
    "PriceDropped" -> "Price for trip {tripId} dropped from {oldPrice} to {newPrice}",
    "TripStartingSoon" -> "Your trip {tripId} starts in {hoursUntilDeparture} hours",
    "FlightCancelled" -> "Flight {flightId} is cancelled"
    // TODO(me): add a "FlightCancelled" entry here, same shape as the
    // others - a template string using {flightId}.
  )

  // TODO(me): pattern-match on `event` and return a Map from placeholder
  // name (matching the {tokens} above, without the braces) to its value as
  // a String - e.g. for FlightDelayed: Map("flightId" -> flightId,
  // "delayMinutes" -> delayMinutes.toString). Numbers need `.toString`
  // since every value here has to be a String.
  //
  // Not private: the new editable-template feature reuses this exact
  // placeholder-building logic for the customer-facing email/SMS content
  // in DeliveryService, rather than duplicating the same match a second
  // time - same fields, same event, same substitution mechanism.
  def placeholdersFor(event: TravelEvent): Map[String, String] = event match {
  case FlightDelayed(_, flightId, delayMinutes, _, _) =>
    Map("flightId" -> flightId, "delayMinutes" -> delayMinutes.toString)

  case PriceDropped(_, tripId, oldPrice, newPrice, _, _) =>
    Map("tripId" -> tripId, "oldPrice" -> oldPrice.toString, "newPrice" -> newPrice.toString)

  case TripStartingSoon(_, tripId, hoursUntilDeparture, _, _) =>
    Map("tripId" -> tripId, "hoursUntilDeparture" -> hoursUntilDeparture.toString)
  
  case FlightCancelled(_, flightId, _, _) =>
    Map("flightId" -> flightId)

  // TODO(me): add a case for FlightCancelled here, same shape as the
  // others - build a Map with a "flightId" entry.
}

  // Fills in every {key} in `template` using `placeholders`. `foldLeft`
  // walks the map one entry at a time, carrying a running value forward -
  // here, the in-progress string, with one more blank filled in at each
  // step. Spring/Java comparison: like a for-loop that reassigns a
  // `String result` variable each pass, except nothing is actually
  // mutated - each step produces a new String, handed to the next step.
  def render(template: String, placeholders: Map[String, String]): String =
    placeholders.foldLeft(template) {
      case (soFar, (key, value)) => soFar.replace(s"{$key}", value)
    }

  def messageFor(event: TravelEvent): String = {
    val template = templates(event.getClass.getSimpleName)
    render(template, placeholdersFor(event))
  }
}
