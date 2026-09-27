import { useState } from 'react'
import { getDecisionLog, type DecisionLogEntry } from '../api'

function SendLogPage() {
  const [travelerId, setTravelerId] = useState('')
  const [entries, setEntries] = useState<DecisionLogEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)

  async function loadLog() {
    setError(null)
    try {
      const result = await getDecisionLog(travelerId)
      // Newest first - the backend returns them in DynamoDB sort-key
      // order (oldest first), so this just reverses that for display.
      setEntries([...result].reverse())
      setLoaded(true)
    } catch (e) {
      setError(String(e))
    }
  }

  return (
    <div>
      <h1>Send Log</h1>

      <label htmlFor="travelerId">Traveler ID</label>
      <div className="field-row">
        <input
          id="travelerId"
          value={travelerId}
          onChange={(e) => setTravelerId(e.target.value)}
          placeholder="traveler-1"
        />
        <button type="button" onClick={loadLog} disabled={!travelerId}>
          Load
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {loaded && entries.length === 0 && <p className="status">No decisions recorded for this traveler yet.</p>}

      {entries.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Event</th>
              <th>Decision</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {/* `.map(...)` over an array is how React renders a list -
                there's no `v-for`/`*ngFor` directive, JSX is just
                JavaScript, so you use JavaScript's own array methods.
                `key` tells React which DOM node corresponds to which item
                across re-renders, so it doesn't have to guess by
                position - event_id is already unique per entry, so it's
                a natural fit. */}
            {entries.map((entry) => (
              <tr key={entry.event_id}>
                <td>{entry.timestamp}</td>
                <td>{entry.event_type}</td>
                <td>
                  <span className={`badge ${entry.decision}`}>{entry.decision}</span>
                </td>
                <td>{entry.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

export default SendLogPage
