package com.travelmsg.filter

import com.travelmsg.domain.{Decision, Send, Suppress, TravelEvent, BypassesFrequencyCap}
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.{Duration, Future, Time}

import scala.collection.concurrent.TrieMap
import com.travelmsg.trace.Trace

/**
 * `SimpleFilter[Req, Rep]` is Finagle's abstract class for a `Filter` that
 * doesn't change its request/response types - it just wraps behavior
 * around the `Service` it's chained in front of. (The fully general
 * `Filter[ReqIn, RepOut, ReqOut, RepIn]` can transform types on the way in
 * and out, e.g. Thrift-struct-to-plain-object translation; we don't need
 * that here.) Spring comparison: this plays the role of a
 * `HandlerInterceptor`, except Filters compose by hand via `andThen` (see
 * DecisionController) instead of being registered into a framework-managed
 * interceptor chain.
 *
 * Per CLAUDE.md: cross-cutting rules (capping, quiet hours, dedupe) are
 * Filters in front of the Service, not `if` statements inside it. This one
 * limits how often we're willing to actually Send to the same traveler,
 * regardless of what DecisionService decides.
 */
class FrequencyCapFilter extends SimpleFilter[TravelEvent, Decision] {

  private val CapWindow: Duration = 60.minutes

  // travelerId -> the Time of the last Send we let through for them.
  // TrieMap, not a plain mutable.Map: Finagle can invoke this Filter
  // concurrently across requests, and a plain HashMap here would be a real
  // race condition, not just a style nitpick.
  private val lastSentAt = TrieMap.empty[String, Time]

  override def apply(event: TravelEvent, service: Service[TravelEvent, Decision]): Future[Decision] =
  event match {
    case _: BypassesFrequencyCap =>
      Trace.record("FrequencyCapFilter: bypassed (transactional)")
      service(event)
    case _ =>
      val travelerId = event.travelerId
      service(event).map {
        case send @ Send(_, _) =>
          lastSentAt.get(travelerId) match {
            case Some(lastSent) if Time.now - lastSent < CapWindow =>
              val suppress = Suppress(s"Frequency capped: already sent to $travelerId within the last $CapWindow")
              Trace.record(s"FrequencyCapFilter: ${suppress.reason}")
              suppress
            case _ =>
              Trace.record("FrequencyCapFilter: not capped, recording send")
              lastSentAt.update(travelerId, Time.now)
              send
          }
        case suppress: Suppress => suppress
      }
  }
}
