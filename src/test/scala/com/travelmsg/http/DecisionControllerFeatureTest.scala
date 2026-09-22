package com.travelmsg.http

import java.util.UUID
import com.travelmsg.server.TravelMessageServer
import com.twitter.finagle.http.Status.Ok
import com.twitter.finatra.http.EmbeddedHttpServer
import com.twitter.inject.server.FeatureTest
import com.travelmsg.filter.QuietHoursFilter
import java.time.{ZoneId, ZonedDateTime}

// One FeatureTest per endpoint, wired end to end through Finatra's DI and
// an embedded server, per CLAUDE.md's testing conventions. DecisionService
// itself should also get direct unit tests with no server involved - add
// those alongside this file once the TODOs below compile.
class DecisionControllerFeatureTest extends FeatureTest {

  override val server = new EmbeddedHttpServer(new TravelMessageServer, disableTestLogging = true)
    .bind[QuietHoursFilter].toInstance(new QuietHoursFilter {
      override protected def currentTime(zoneId: ZoneId): ZonedDateTime =
        ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, zoneId) // always noon local time - never quiet hours
    })

  // Runs once, at construction time, before any test() block - plain
  // statements in a class body execute in order, same as any other val
  // initialization. Delivery now needs a real profile on file for a Send
  // to actually go out, so every traveler_id used below needs one
  // registered first, or DeliveryService will (correctly) report
  // "No contact info on file" instead.
  (1 to 5).foreach { i =>
    server.httpPost(
      path = s"/travelers/traveler-$i/profile",
      postBody = s"""{"email": "traveler-$i@example.com", "phone": "+1555555000$i"}""",
      andExpect = Ok
    )
  }

  test("Send when a flight delay is at or above the 30-minute threshold") {
    server.httpPost(
      path = "/decisions",
      postBody =
        s"""{"traveler_id": "traveler-1", "flight_id": "UA123", "delay_minutes": 45, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}""",
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA123 is delayed 45 minutes"}"""
    )
  }

  test("Suppress when a flight delay is under the 30-minute threshold") {
    server.httpPost(
      path = "/decisions",
      postBody =
        s"""{"traveler_id": "traveler-1", "flight_id": "UA123", "delay_minutes": 15, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}""",
      andExpect = Ok,
      withJsonBody = """{"decision": "suppress", "detail": "Delay of 15 minutes is under the 30-minute threshold"}"""
    )
  }

  test("Suppress when a Send repeats for the same traveler within the cap window") {
    val postBody1 =
      s"""{"traveler_id": "traveler-2", "flight_id": "UA456", "delay_minutes": 45, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}"""
    val postBody2 =
      s"""{"traveler_id": "traveler-2", "flight_id": "UA456", "delay_minutes": 45, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}"""

    server.httpPost(
      path = "/decisions",
      postBody = postBody1,
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA456 is delayed 45 minutes"}"""
    )

    server.httpPost(
      path = "/decisions",
      postBody = postBody2,
      andExpect = Ok,
      withJsonBody = """{"decision": "suppress", "detail": "Frequency capped: already sent to traveler-2 within the last 1.hours"}"""
    )
  }

  test("Suppress when a lower-priority event competes with a recent higher-priority Send") {
    server.httpPost(
      path = "/decisions",
      postBody =
        s"""{"traveler_id": "traveler-3", "flight_id": "UA789", "delay_minutes": 45, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}""",
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA789 is delayed 45 minutes"}"""
    )

    server.httpPost(
      path = "/decisions/price-dropped",
      postBody =
        s"""{"traveler_id": "traveler-3", "trip_id": "trip-1", "old_price": 500, "new_price": 400, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}""",
      andExpect = Ok,
      withJsonBody = """{"decision": "suppress", "detail": "Lower priority than an active FlightDelayed for this traveler"}"""
    )
  }

  test("Send both times when a transactional event repeats for the same traveler") {
    val postBody1 =
      s"""{"traveler_id": "traveler-4", "flight_id": "UA999", "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}"""
    val postBody2 =
      s"""{"traveler_id": "traveler-4", "flight_id": "UA999", "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}"""

    server.httpPost(
      path = "/decisions/flight-cancelled",
      postBody = postBody1,
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA999 is cancelled"}"""
    )

    server.httpPost(
      path = "/decisions/flight-cancelled",
      postBody = postBody2,
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA999 is cancelled"}"""
    )
  }

  test("Suppress when the same event_id is sent twice") {
    // Same postBody, same event_id, both calls - this is the actual
    // duplicate-detection behavior IdempotencyFilter exists for.
    val postBody =
      s"""{"traveler_id": "traveler-5", "flight_id": "UA111", "delay_minutes": 45, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}"""

    server.httpPost(
      path = "/decisions",
      postBody = postBody,
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA111 is delayed 45 minutes"}"""
    )

    server.httpPost(
      path = "/decisions",
      postBody = postBody,
      andExpect = Ok,
      withJsonBody = """{"decision": "suppress", "detail": "Duplicate event, already processed"}"""
    )
  }

  test("Suppress when the traveler has no registered profile") {
    // traveler-no-profile is never registered above - the pipeline itself
    // says Send (delay is over threshold), but DeliveryService can't
    // actually deliver anywhere.
    server.httpPost(
      path = "/decisions",
      postBody =
        s"""{"traveler_id": "traveler-no-profile", "flight_id": "UA222", "delay_minutes": 45, "timezone": "UTC", "event_id": "${UUID.randomUUID()}"}""",
      andExpect = Ok,
      withJsonBody = """{"decision": "suppress", "detail": "No contact info on file for traveler"}"""
    )
  }

  test("Send log records a real decision") {
    // A real decision's eventId and timestamp aren't predictable ahead of
    // time (timestamp especially - it's whatever Instant.now() was at the
    // moment DecisionLogger ran), so this can't use withJsonBody's exact
    // match like the other tests. httpGetJson deserializes the response
    // straight into DecisionLogResponse instead, so we can assert against
    // real Scala values - check the eventId we sent shows up in the log,
    // rather than matching the whole body literally.
    // Fresh traveler, not reused by any other test in this file - avoids
    // colliding with another test's frequency-cap or arbitration state,
    // same lesson as Slice 2.
    server.httpPost(
      path = "/travelers/traveler-7/profile",
      postBody = """{"email": "traveler-7@example.com", "phone": "+15555550007"}""",
      andExpect = Ok
    )

    val eventId = UUID.randomUUID().toString
    server.httpPost(
      path = "/decisions",
      postBody =
        s"""{"traveler_id": "traveler-7", "flight_id": "UA321", "delay_minutes": 45, "timezone": "UTC", "event_id": "$eventId"}""",
      andExpect = Ok,
      withJsonBody = """{"decision": "send", "detail": "Flight UA321 is delayed 45 minutes"}"""
    )

    val log = server.httpGetJson[DecisionLogResponse](path = "/travelers/traveler-7/log", andExpect = Ok)
    val entry = log.entries.find(_.eventId == eventId)
    assert(entry.isDefined)
    assert(entry.get.decision == "send")
    assert(entry.get.detail == "Flight UA321 is delayed 45 minutes")
  }
}
