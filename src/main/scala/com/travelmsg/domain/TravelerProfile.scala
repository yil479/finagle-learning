package com.travelmsg.domain

// Not a TravelEvent variant - a traveler's contact info, looked up by
// travelerId at delivery time rather than carried on every event. See
// TravelerProfileStore for where this actually lives (DynamoDB).
case class TravelerProfile(travelerId: String, email: String, phone: String)
