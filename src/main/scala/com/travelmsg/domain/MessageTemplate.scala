package com.travelmsg.domain

// The actual customer-facing content for one event type, across both
// channels - separate from Decision/Send's `message` field (that one's an
// internal "what happened" description used in API responses, the send
// log, and the dry-run trace; this is what the customer actually sees).
// All four fields use the same {placeholder} substitution as before - see
// MessageTemplates.render/placeholdersFor, now shared by both systems.
case class MessageTemplate(
  eventType: String,
  emailSubject: String,
  emailBodyText: String,
  emailBodyHtml: String,
  smsBody: String)
