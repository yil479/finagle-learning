package com.travelmsg.delivery

import com.travelmsg.domain.{Decision, FlightCancelled, Send, Suppress, TravelEvent}
import com.travelmsg.persistence.{MessageTemplateStore, TravelerProfileStore}
import com.travelmsg.service.MessageTemplates
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
class DeliveryService @Inject() (
  profileStore: TravelerProfileStore,
  templateStore: MessageTemplateStore,
  sender: MessageSender) {

  private val pool: FuturePool = FuturePool.unboundedPool
  private val MaxAttempts = 3

  // Transactional events (for now, just FlightCancelled) go by SMS - more
  // likely to be seen fast - everything else goes by email.
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
    // Profile and template lookups depend on nothing but `event` itself -
    // neither needs the other's result - so they run concurrently via
    // Future.join instead of one blocking round-trip waiting on the other.
    val profileF = pool { profileStore.find(event.travelerId) }
    val templateF = pool { templateStore.find(event.getClass.getSimpleName) }

    Future.join(profileF, templateF).flatMap {
      case (None, _) =>
        Future.value(Suppress("No contact info on file for traveler"))
      case (Some(profile), templateOpt) =>
        // No error handling for a missing template: MessageTemplateStore
        // seeds all four event types at startup, so this can't actually
        // happen - trusting that invariant rather than guarding against
        // a case that can't occur.
        val template = templateOpt.get
        val placeholders = MessageTemplates.placeholdersFor(event)

        val attempt = channelFor(event) match {
          case Channel.Email =>
            val subject = MessageTemplates.render(template.emailSubject, placeholders)
            val textBody = MessageTemplates.render(template.emailBodyText, placeholders)
            val htmlBody = MessageTemplates.render(template.emailBodyHtml, placeholders)
            pool { sender.sendEmail(profile.email, subject, textBody, htmlBody) }
          case Channel.Sms =>
            val smsBody = MessageTemplates.render(template.smsBody, placeholders)
            pool { sender.sendSms(profile.phone, smsBody) }
        }

        withRetries(MaxAttempts)(attempt)
          .map(_ => send)
          .rescue { case NonFatal(e) => Future.value(Suppress(s"Delivery failed: ${e.getMessage}")) }
    }
  }
}
