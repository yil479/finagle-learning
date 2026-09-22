package com.travelmsg.filter

import com.travelmsg.domain.{Decision, Send, Suppress, TravelEvent, BypassesQuietHours}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Third Filter in the pipeline, and the innermost one (closest to
 * DecisionService - see DecisionController's `pipeline` val). That
 * ordering is deliberate: a quiet-hours suppression should never be
 * recorded by FrequencyCapFilter or PriorityArbitrationFilter as if it
 * were an actual send, so it needs to happen before either of them sees
 * the Decision.
 *
 * Unlike the other two Filters, this one is stateless - whether "now" is
 * quiet hours is a pure function of the current instant and a timezone, no
 * per-traveler memory needed.
 *
 * Per CLAUDE.md: quiet hours use the traveler's *current* timezone (the
 * destination's, during a trip - not home), which is why `timezone` lives
 * on the event itself (see TravelEvent.scala) rather than being looked up
 * from some fixed traveler profile we don't have yet.
 *
 * `java.time`, not `com.twitter.util.Time`, is doing the actual timezone
 * math here. `Time`/`Duration` (used in the other two Filters) are great
 * for elapsed-time bookkeeping - "how long since X" - but have no real
 * calendar/timezone awareness, unlike `java.time.ZonedDateTime`. Right
 * tool for the job, even within an otherwise Twitter-stack-first codebase.
 */
class QuietHoursFilter extends SimpleFilter[TravelEvent, Decision] {

  private val QuietHoursStart = 21 // 9pm local time
  private val QuietHoursEnd = 8 // 8am local time

  // TODO(me): return true if `now` falls within the quiet window. Deals
  // only in already-resolved local time - no clock lookups here, which is
  // what makes this trivially unit-testable (see below).
  //
  // The window wraps past midnight (21:00 -> 08:00 the next day), so this
  // isn't a simple `QuietHoursStart <= hour && hour <= QuietHoursEnd` range
  // check - that would be true for no hours at all (nothing is both >= 21
  // and <= 8). Think about what condition is true at both 23:00 and 03:00,
  // but false at 14:00.
  private[filter] def isQuietHours(now: ZonedDateTime): Boolean = {
    val hour = now.getHour
    hour >= QuietHoursStart || hour < QuietHoursEnd
  }

  // `protected`, not called directly in `apply`'s body inline: this is the
  // one seam a test can override to control "now" without needing a full
  // Guice Clock binding. Tests that don't care about quiet hours (frequency
  // capping, arbitration) can bind a QuietHoursFilter subclass that always
  // returns a safely non-quiet time, so they stop depending on real
  // wall-clock time entirely - see DecisionControllerFeatureTest.
  protected def currentTime(zoneId: ZoneId): ZonedDateTime = ZonedDateTime.now(zoneId)

  // TODO(me): same bypass shape as FrequencyCapFilter - wrap this method's
  // existing body in a `match` on `event`, with `case _: TransactionalEvent
  // => service(event)` as the bypass case, and everything currently here
  // as the fallback case. Remember to import TransactionalEvent.
  override def apply(event: TravelEvent, service: Service[TravelEvent, Decision]): Future[Decision] =
  event match {
    case _: BypassesQuietHours => service(event)
    case _ =>
      service(event).map {
        case send @ Send(_, _) =>
          val now = currentTime(ZoneId.of(event.timezone))
          if (isQuietHours(now)) {
            Suppress(s"Quiet hours in ${event.timezone} - message held")
          } else {
            send
          }
        case suppress: Suppress => suppress
      }
  }
}
