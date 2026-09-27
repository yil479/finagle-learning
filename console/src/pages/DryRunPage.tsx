import { useState } from 'react'
import { dryRun, EVENT_TYPES, type DryRunResult, type EventType } from '../api'

const emptyFields = {
  flightId: '',
  delayMinutes: '',
  tripId: '',
  oldPrice: '',
  newPrice: '',
  hoursUntilDeparture: '',
}

function DryRunPage() {
  const [eventType, setEventType] = useState<EventType>('FlightDelayed')
  const [travelerId, setTravelerId] = useState('traveler-1')
  const [timezone, setTimezone] = useState('UTC')
  const [fields, setFields] = useState(emptyFields)
  const [result, setResult] = useState<DryRunResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  function updateField(name: keyof typeof emptyFields, value: string) {
    setFields((prev) => ({ ...prev, [name]: value }))
  }

  // The request body shape genuinely differs per event type - this
  // builds exactly what each one needs, matching the four request case
  // classes in DecisionController.scala field for field.
  function buildBody(): Record<string, unknown> {
    const common = { traveler_id: travelerId, timezone, event_id: crypto.randomUUID() }
    switch (eventType) {
      case 'FlightDelayed':
        return { ...common, flight_id: fields.flightId, delay_minutes: Number(fields.delayMinutes) }
      case 'PriceDropped':
        return {
          ...common,
          trip_id: fields.tripId,
          old_price: Number(fields.oldPrice),
          new_price: Number(fields.newPrice),
        }
      case 'TripStartingSoon':
        return { ...common, trip_id: fields.tripId, hours_until_departure: Number(fields.hoursUntilDeparture) }
      case 'FlightCancelled':
        return { ...common, flight_id: fields.flightId }
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setResult(null)
    try {
      const response = await dryRun(eventType, buildBody())
      setResult(response)
    } catch (e) {
      setError(String(e))
    }
  }

  return (
    <div>
      <h1>Dry Run</h1>
      <p className="status">Simulates a decision without sending anything or writing to the send log.</p>

      <form onSubmit={handleSubmit}>
        <label htmlFor="eventType">Event type</label>
        {/* A plain HTML <select> - React just reads its `value` from
            state and writes back via `onChange`, same controlled-input
            pattern as every text input on this page. */}
        <select id="eventType" value={eventType} onChange={(e) => setEventType(e.target.value as EventType)}>
          {EVENT_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>

        <div className="field-row">
          <div>
            <label htmlFor="travelerId">Traveler ID</label>
            <input id="travelerId" value={travelerId} onChange={(e) => setTravelerId(e.target.value)} />
          </div>
          <div>
            <label htmlFor="timezone">Timezone</label>
            <input id="timezone" value={timezone} onChange={(e) => setTimezone(e.target.value)} />
          </div>
        </div>

        {/* Conditional rendering in JSX is just a JavaScript expression -
            no `v-if`/`*ngIf` directive. `condition && <jsx/>` is the
            common idiom: if `condition` is false, the whole expression
            evaluates to `false`, and React simply renders nothing for it. */}
        {(eventType === 'FlightDelayed' || eventType === 'FlightCancelled') && (
          <>
            <label htmlFor="flightId">Flight ID</label>
            <input id="flightId" value={fields.flightId} onChange={(e) => updateField('flightId', e.target.value)} />
          </>
        )}

        {eventType === 'FlightDelayed' && (
          <>
            <label htmlFor="delayMinutes">Delay (minutes)</label>
            <input
              id="delayMinutes"
              type="number"
              value={fields.delayMinutes}
              onChange={(e) => updateField('delayMinutes', e.target.value)}
            />
          </>
        )}

        {(eventType === 'PriceDropped' || eventType === 'TripStartingSoon') && (
          <>
            <label htmlFor="tripId">Trip ID</label>
            <input id="tripId" value={fields.tripId} onChange={(e) => updateField('tripId', e.target.value)} />
          </>
        )}

        {eventType === 'PriceDropped' && (
          <div className="field-row">
            <div>
              <label htmlFor="oldPrice">Old price</label>
              <input
                id="oldPrice"
                type="number"
                value={fields.oldPrice}
                onChange={(e) => updateField('oldPrice', e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="newPrice">New price</label>
              <input
                id="newPrice"
                type="number"
                value={fields.newPrice}
                onChange={(e) => updateField('newPrice', e.target.value)}
              />
            </div>
          </div>
        )}

        {eventType === 'TripStartingSoon' && (
          <>
            <label htmlFor="hoursUntilDeparture">Hours until departure</label>
            <input
              id="hoursUntilDeparture"
              type="number"
              value={fields.hoursUntilDeparture}
              onChange={(e) => updateField('hoursUntilDeparture', e.target.value)}
            />
          </>
        )}

        <button type="submit">Run simulation</button>
      </form>

      {error && <p className="error">{error}</p>}

      {result && (
        <div className="preview-frame">
          <h2>
            Result: <span className={`badge ${result.decision}`}>{result.decision}</span>
          </h2>
          <p>{result.detail}</p>
          <h2 style={{ marginTop: 20 }}>Decision trace</h2>
          <ol className="trace-list">
            {result.trace.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </div>
      )}
    </div>
  )
}

export default DryRunPage
