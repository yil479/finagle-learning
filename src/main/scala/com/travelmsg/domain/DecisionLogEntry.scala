package com.travelmsg.domain

// One row in the send log - written for every real (non-dry-run) decision,
// success or suppression alike. `timestamp` is ISO-8601 (java.time.Instant's
// default toString), which also happens to sort correctly as a plain
// string - convenient, since it's the DynamoDB sort key (see
// DecisionLogStore) and query results come back in that order for free.
case class DecisionLogEntry(
  travelerId: String,
  timestamp: String,
  eventId: String,
  eventType: String,
  decision: String,
  detail: String)
