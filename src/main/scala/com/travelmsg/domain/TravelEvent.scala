package com.travelmsg.domain

// sealed trait + case class variants: this is how Scala models a closed set
// of alternatives (an ADT). Because it's `sealed`, every `match` on
// TravelEvent elsewhere in the codebase is exhaustivity-checked by the
// compiler - if Slice 3 adds a new event type and some `match` doesn't
// handle it, that file fails to compile instead of failing at runtime.
// Spring/Java comparison: this is like an enum with per-case fields, except
// the compiler actually enforces you handle every case at every call site.
// TODO(me): Slice 4 needs two fields common to every event - travelerId
// (already on all three case classes below) and a new `timezone` field -
// accessible without pattern-matching. Declare both as abstract members
// directly on the trait:
//
// You don't need to touch the case classes' bodies for `travelerId` - a
// case class constructor parameter automatically satisfies (Scala calls
// this "overrides") an abstract `def` of the same name and type declared
// on its supertype, since case class constructor params are `val`s under
// the hood. You DO need to add a new `timezone: String` constructor
// parameter to each of the three case classes below, though, since none of
// them have one yet - see the next TODO for the exact shape.
// TODO(me): Slice 6 needs a way to tell transactional events (cancellations,
// gate changes) apart from promotional ones, per CLAUDE.md's compliance
// rule that transactional events bypass frequency caps and quiet hours.
// Instead of a true/false flag, use two more sealed traits - the category
// becomes part of each event's type, not a value that could be set wrong:
//
//   sealed trait PromotionalEvent extends TravelEvent
//   sealed trait TransactionalEvent extends TravelEvent
//
// Both need to live in this file too, same rule as TravelEvent itself:
// `sealed` requires every direct subtype to be declared in the same file.
//
// Then, every existing case class below changes its `extends TravelEvent`
// to `extends PromotionalEvent` instead (none of them are transactional).
// The new FlightCancelled case class (see further down) will be the first
// - and for now, only - `extends TransactionalEvent`.
sealed trait TravelEvent {
    def travelerId: String
    def timezone: String
    def eventId: String
}
trait BypassesQuietHours
trait BypassesFrequencyCap

// TODO(me): model a FlightDelayed event as a case class extending
// TravelEvent. DecisionService and the controller below are written
// against this exact shape, so match it:
//
// TODO(me): Slice 6 - change this (and the two case classes below it) from
// `extends TravelEvent` to `extends PromotionalEvent`, once you've added
// that trait per the TODO above. None of these three are transactional.
  case class FlightDelayed(travelerId: String, flightId: String, delayMinutes: Int, timezone: String, eventId: String)
    extends TravelEvent with BypassesQuietHours

// TODO(me): Slice 3 adds two more variants - model both, exact shape below.
// The moment you add these, DecisionService.scala's `match` on TravelEvent
// stops being exhaustive (it only handles FlightDelayed today) and will
// fail to compile under our -Wconf flag - that's expected, and is the next
// thing to fix after this file.
//
  case class PriceDropped(travelerId: String, tripId: String, oldPrice: BigDecimal, newPrice: BigDecimal,timezone: String, eventId: String)
    extends TravelEvent
//
  case class TripStartingSoon(travelerId: String, tripId: String, hoursUntilDeparture: Int,timezone: String, eventId: String)
    extends TravelEvent with BypassesQuietHours with BypassesFrequencyCap
//
// BigDecimal, not Double, for prices - floating point can't represent

case class FlightCancelled(travelerId: String, flightId: String, timezone: String, eventId: String)
  extends TravelEvent with BypassesQuietHours with BypassesFrequencyCap

// TODO(me): Slice 7 needs one more common member - `eventId`, a unique ID
// the caller assigns to each event occurrence (a UUID, in practice), used
// to tell "the same event arriving twice" apart from "two different
// events that happen to look similar." Add it as a fourth abstract member
// on the trait, same pattern as travelerId/timezone:
//
//   def eventId: String
//
// Then add `eventId: String` as a new constructor parameter to all four
// case classes above. Same rule as `timezone` before it: the constructor
// parameter satisfies the abstract member automatically, but every case
// class needs the new field added to its own parameter list.