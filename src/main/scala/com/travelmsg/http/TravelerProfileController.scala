package com.travelmsg.http

import com.travelmsg.domain.{DecisionLogEntry, TravelerProfile}
import com.travelmsg.persistence.{DecisionLogStore, TravelerProfileStore}
import com.twitter.finatra.http.Controller
import com.twitter.finatra.http.annotations.RouteParam
import com.twitter.util.{Future, FuturePool}

import javax.inject.{Inject, Singleton}

case class RegisterProfileRequest(@RouteParam travelerId: String, email: String, phone: String)

case class ProfileResponse(travelerId: String, email: String, phone: String)

case class DecisionLogRequest(@RouteParam travelerId: String)

case class DecisionLogResponse(entries: List[DecisionLogEntry])

/**
 * Separate from DecisionController on purpose - this is about traveler
 * contact info and history, not decision-making itself. Both stores'
 * calls are blocking DynamoDB calls, same as everywhere else they're
 * used - wrapped in a FuturePool rather than run inline on the Netty
 * event loop.
 */
@Singleton
class TravelerProfileController @Inject() (profileStore: TravelerProfileStore, logStore: DecisionLogStore)
    extends Controller {

  private val pool: FuturePool = FuturePool.unboundedPool

  post("/travelers/:traveler_id/profile") { request: RegisterProfileRequest =>
    pool {
      profileStore.save(TravelerProfile(request.travelerId, request.email, request.phone))
      ProfileResponse(request.travelerId, request.email, request.phone)
    }
  }

  get("/travelers/:traveler_id/log") { request: DecisionLogRequest =>
    pool {
      DecisionLogResponse(logStore.findByTraveler(request.travelerId))
    }
  }
}
