package com.travelmsg.delivery

import com.travelmsg.domain.{Decision, FlightCancelled, Send, Suppress, TravelEvent}
import com.travelmsg.persistence.TravelerProfileStore
import com.twitter.util.{Future, FuturePool}

import javax.inject.Inject
import scala.util.control.NonFatal

/**
 * Turns a Send decision into an actual delivery attempt. Not a Filter -
 * this has a real side effect (an email or SMS actually goes out), which
 * doesn't fit the Filter/Service decision-making pipeline the same way
 * capping, arbitration, and quiet hours do. DecisionController calls this
 * directly, after the pipeline has already decided Send.
 *
 * Returns a Decision, not just success/failure: if delivery can't happen
 * (no profile on file) or fails after retries, that becomes a Suppress
 * with a reason, same as every other "didn't send, here's why" case in
 * this project - the audit trail matters here too.
 */
class DeliveryService @Inject() (profileStore: TravelerProfileStore, sender: MessageSender) {

  private val pool: FuturePool = FuturePool.unboundedPool
  private val MaxAttempts = 3

  // TODO(me): decide which Channel each event type uses. Per our design
  // discussion: transactional events (for now, just FlightCancelled) go by
  // SMS - more likely to be seen fast - everything else goes by email.
  // Same "match on the event's type" idiom as `priorityOf` in
  // PriorityArbitrationFilter - `case _: FlightCancelled => Channel.Sms`,
  // with a fallback `case _ => Channel.Email` for everything else (you
  // don't need to name every other event type individually).
  private def channelFor(event: TravelEvent): Channel = event match {
    case _: FlightCancelled => Channel.Sms
    case _ => Channel.Email
  }

  // A retry loop, hand-written rather than reaching for Finagle's retry
  // framework (that's built around Finagle's client stack; this is just a
  // blocking call wrapped in a FuturePool, so it doesn't fit). `rescue`,
  // not `recover` - per CLAUDE.md, `rescue` is the one that lets you
  // return a new Future (here: another attempt), while `recover` only
  // lets you return a plain value.
  private def withRetries[T](attemptsRemaining: Int)(attempt: => Future[T]): Future[T] =
    attempt.rescue {
      case NonFatal(_) if attemptsRemaining > 1 => withRetries(attemptsRemaining - 1)(attempt)
    }

  def deliver(event: TravelEvent, send: Send): Future[Decision] = {
    pool { profileStore.find(event.travelerId) }.flatMap {
      case None =>
        Future.value(Suppress("No contact info on file for traveler"))
      case Some(profile) =>
        val attempt = channelFor(event) match {
          case Channel.Email => pool { sender.sendEmail(profile.email, "Travel update", send.message) }
          case Channel.Sms => pool { sender.sendSms(profile.phone, send.message) }
        }
        withRetries(MaxAttempts)(attempt)
          .map(_ => send)
          .rescue { case NonFatal(e) => Future.value(Suppress(s"Delivery failed: ${e.getMessage}")) }
    }
  }
}
