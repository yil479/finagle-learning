package com.travelmsg.service

import com.travelmsg.domain.{Decision, DecisionLogEntry, TravelEvent}
import com.travelmsg.http.DecisionResponse
import com.travelmsg.persistence.DecisionLogStore
import com.twitter.util.{Future, FuturePool}

import java.time.Instant
import javax.inject.Inject

/**
 * Writes one send-log entry per real decision. Same FuturePool-wrapping
 * pattern as IdempotencyStore/DeliveryService - `store.save` is a
 * blocking DynamoDB call. Reuses DecisionResponse.fromDomain (already
 * written for the HTTP response) to turn a Decision into the same
 * "send"/"suppress" + detail strings shown to callers, rather than
 * re-deriving that mapping a second time here.
 */
class DecisionLogger @Inject() (store: DecisionLogStore) {

  private val pool: FuturePool = FuturePool.unboundedPool

  def log(event: TravelEvent, decision: Decision): Future[Decision] = {
    val response = DecisionResponse.fromDomain(decision)
    val entry = DecisionLogEntry(
      travelerId = event.travelerId,
      timestamp = Instant.now().toString,
      eventId = event.eventId,
      eventType = event.getClass.getSimpleName,
      decision = response.decision,
      detail = response.detail
    )
    pool { store.save(entry) }.map(_ => decision)
  }
}
