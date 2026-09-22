package com.travelmsg.filter

import org.scalatest.funsuite.AnyFunSuite

import java.time.{ZoneId, ZonedDateTime}
import com.travelmsg.domain.{Decision, FlightCancelled, Send, TravelEvent}
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Service
import com.twitter.util.{Await, Future}
class QuietHoursFilterTest extends AnyFunSuite {

  private val filter = new QuietHoursFilter
  private val zone = ZoneId.of("UTC")

  private def atHour(hour: Int): ZonedDateTime =
    ZonedDateTime.of(2024, 1, 1, hour, 0, 0, 0, zone)

  test("is quiet late at night") {
    assert(filter.isQuietHours(atHour(23)))
  }

  test("is quiet early in the morning") {
    assert(filter.isQuietHours(atHour(3)))
  }

  test("is not quiet during the day") {
    assert(!filter.isQuietHours(atHour(14)))
  }
  test("transactional events bypass quiet hours") {
  val filter = new QuietHoursFilter {
    override protected def currentTime(zoneId: ZoneId): ZonedDateTime =
      ZonedDateTime.of(2024, 1, 1, 2, 0, 0, 0, zoneId) // 2am - definitely quiet
  }
  val event = FlightCancelled(travelerId = "traveler-x", flightId = "UA999", timezone = "UTC", eventId = "event-1")
  val alwaysSends = new Service[TravelEvent, Decision] {
    def apply(request: TravelEvent): Future[Decision] = Future.value(Send("traveler-x", "cancelled"))
  }

  val result = Await.result(filter.apply(event, alwaysSends), 5.seconds)
  assert(result.isInstanceOf[Send])
}
}