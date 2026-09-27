import { useEffect, useState } from 'react'
import { EVENT_TYPES, getTemplate, saveTemplate, type EventType, type MessageTemplate } from '../api'

// Sample values for each event type's {placeholder} tokens, purely for
// the live preview below - matches MessageTemplates.placeholdersFor on
// the backend field for field, just with made-up sample data instead of
// a real event.
const SAMPLE_PLACEHOLDERS: Record<EventType, Record<string, string>> = {
  FlightDelayed: { flightId: 'UA123', delayMinutes: '45' },
  PriceDropped: { tripId: 'trip-42', oldPrice: '500', newPrice: '400' },
  TripStartingSoon: { tripId: 'trip-42', hoursUntilDeparture: '3' },
  FlightCancelled: { flightId: 'UA123' },
}

function render(template: string, placeholders: Record<string, string>): string {
  return Object.entries(placeholders).reduce(
    (soFar, [key, value]) => soFar.replaceAll(`{${key}}`, value),
    template,
  )
}

const emptyTemplate = (eventType: EventType): MessageTemplate => ({
  event_type: eventType,
  email_subject: '',
  email_body_text: '',
  email_body_html: '',
  sms_body: '',
})

function TemplatesPage() {
  const [eventType, setEventType] = useState<EventType>('FlightDelayed')
  const [template, setTemplate] = useState<MessageTemplate>(emptyTemplate('FlightDelayed'))
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // `useEffect` runs its function after render, and again whenever any
  // value in the dependency array (`[eventType]`) changes - this is
  // React's rough equivalent of Vue's `watch(eventType, ...)` or
  // Angular's `ngOnChanges`. Here: every time the dropdown selection
  // changes, re-fetch that event type's template automatically, instead
  // of requiring a separate "Load" button click like the other pages.
  useEffect(() => {
    let cancelled = false
    setError(null)
    getTemplate(eventType)
      .then((found) => {
        if (!cancelled) setTemplate(found ?? emptyTemplate(eventType))
      })
      .catch((e) => {
        if (!cancelled) setError(String(e))
      })
    // The cleanup function below runs if `eventType` changes again before
    // this fetch finishes - it stops a slow, stale response from
    // overwriting what the user is now looking at.
    return () => {
      cancelled = true
    }
  }, [eventType])

  function updateField(field: keyof MessageTemplate, value: string) {
    setTemplate((prev) => ({ ...prev, [field]: value }))
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const saved = await saveTemplate(template)
      if (saved) setTemplate(saved)
      setStatus('Saved.')
    } catch (e) {
      setError(String(e))
    }
  }

  const sample = SAMPLE_PLACEHOLDERS[eventType]

  return (
    <div>
      <h1>Templates</h1>

      <label htmlFor="eventType">Event type</label>
      <select id="eventType" value={eventType} onChange={(e) => setEventType(e.target.value as EventType)}>
        {EVENT_TYPES.map((type) => (
          <option key={type} value={type}>
            {type}
          </option>
        ))}
      </select>

      <form onSubmit={handleSave}>
        <label htmlFor="emailSubject">Email subject</label>
        <input
          id="emailSubject"
          value={template.email_subject}
          onChange={(e) => updateField('email_subject', e.target.value)}
        />

        <label htmlFor="emailBodyHtml">Email body (HTML)</label>
        <textarea
          id="emailBodyHtml"
          rows={6}
          value={template.email_body_html}
          onChange={(e) => updateField('email_body_html', e.target.value)}
        />

        <label htmlFor="emailBodyText">Email body (plain text fallback)</label>
        <textarea
          id="emailBodyText"
          rows={3}
          value={template.email_body_text}
          onChange={(e) => updateField('email_body_text', e.target.value)}
        />

        <label htmlFor="smsBody">SMS body</label>
        <textarea id="smsBody" rows={2} value={template.sms_body} onChange={(e) => updateField('sms_body', e.target.value)} />

        <button type="submit">Save</button>
      </form>

      {status && <p className="status">{status}</p>}
      {error && <p className="error">{error}</p>}

      <h2 style={{ marginTop: 32 }}>Live preview</h2>
      <p className="status">Using sample data: {JSON.stringify(sample)}</p>
      <p className="status">Subject: {render(template.email_subject, sample)}</p>
      {/* An <iframe> with `srcDoc`, not `dangerouslySetInnerHTML`, so the
          previewed email HTML/CSS is fully isolated from this page's own
          styles - the same reason email templates get previewed this way
          in real email-marketing tools. */}
      <iframe
        className="preview-frame"
        title="Email preview"
        srcDoc={render(template.email_body_html, sample)}
        style={{ width: '100%', height: 240, border: '1px solid var(--border)' }}
      />
    </div>
  )
}

export default TemplatesPage
