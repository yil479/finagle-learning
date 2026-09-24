package com.travelmsg.http

import com.travelmsg.domain.MessageTemplate
import com.travelmsg.persistence.MessageTemplateStore
import com.twitter.finatra.http.Controller
import com.twitter.finatra.http.annotations.RouteParam
import com.twitter.util.FuturePool

import javax.inject.{Inject, Singleton}

case class GetTemplateRequest(@RouteParam eventType: String)

case class UpdateTemplateRequest(
  @RouteParam eventType: String,
  emailSubject: String,
  emailBodyText: String,
  emailBodyHtml: String,
  smsBody: String) {
  def toDomain: MessageTemplate =
    MessageTemplate(eventType, emailSubject, emailBodyText, emailBodyHtml, smsBody)
}

/**
 * Read and update the customer-facing message templates DeliveryService
 * sends from - separate from DecisionController's own request/response
 * types, since this is about campaign content, not decision-making.
 * `eventType` must match one of the four case class names
 * (FlightDelayed, PriceDropped, TripStartingSoon, FlightCancelled) -
 * these aren't user-defined, they're a fixed, closed set (same sealed
 * trait exhaustivity as everywhere else in this project), so there's no
 * "create" here, only "read" and "update" of the four that already exist.
 */
@Singleton
class MessageTemplateController @Inject() (store: MessageTemplateStore) extends Controller {

  private val pool: FuturePool = FuturePool.unboundedPool

  get("/templates/:event_type") { request: GetTemplateRequest =>
    pool {
      store.find(request.eventType)
    }
  }

  put("/templates/:event_type") { request: UpdateTemplateRequest =>
    pool {
      store.save(request.toDomain)
      request.toDomain
    }
  }
}
