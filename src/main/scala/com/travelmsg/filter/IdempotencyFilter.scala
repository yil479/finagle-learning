package com.travelmsg.filter

import com.travelmsg.domain.{Decision, Suppress, TravelEvent}
import com.travelmsg.persistence.IdempotencyStore
import com.travelmsg.trace.Trace
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.{Future, FuturePool}

import javax.inject.Inject

/**
 * Outermost filter in the pipeline (see DecisionController) - idempotency
 * has to short-circuit before frequency capping, arbitration, or quiet
 * hours ever run, so a duplicate event doesn't get double-counted against
 * any of that state (e.g. consuming a slot in the frequency-cap window a
 * second time for what's really the same event arriving twice).
 *
 * `FuturePool.unboundedPool` wraps a blocking call so it runs on a
 * dedicated thread pool instead of Finagle's Netty event loop - the event
 * loop stays free to handle other requests while this one waits on the
 * network round-trip to DynamoDB. Spring comparison: similar to offloading
 * a blocking JDBC call onto a separate executor instead of running it on
 * a request-handling thread.
 */
class IdempotencyFilter @Inject() (store: IdempotencyStore) extends SimpleFilter[TravelEvent, Decision] {

  private val pool: FuturePool = FuturePool.unboundedPool

  override def apply(event: TravelEvent, service: Service[TravelEvent, Decision]): Future[Decision] = {
  pool { store.hasSeen(event.eventId) }.flatMap { alreadySeen =>
    if (alreadySeen) {
      Trace.record("IdempotencyFilter: duplicate event_id, short-circuiting")
      Future.value(Suppress("Duplicate event, already processed"))
    } else {
      Trace.record("IdempotencyFilter: new event_id, proceeding")
      service(event).flatMap { decision =>
        pool { store.markSeen(event.eventId) }.map(_ => decision)
      }
    }
  }
}
}
