// `useState` is React's version of a reactive local variable - closest
// equivalent to Vue's `ref()` or an Angular component's plain class field
// (with change detection watching it for you). `useState('')` returns a
// pair: the CURRENT value, and a function to update it. Calling the setter
// (e.g. `setTravelerId(...)`) is what tells React "re-render this
// component with the new value" - just assigning a plain variable would
// not do that, which is the one genuinely new idea here.
import { useState } from 'react'
import { getProfile, saveProfile, type TravelerProfile } from '../api'

function PreferencesPage() {
  const [travelerId, setTravelerId] = useState('')
  const [profile, setProfile] = useState<TravelerProfile | null>(null)
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function loadProfile() {
    setError(null)
    setStatus(null)
    try {
      const found = await getProfile(travelerId)
      setProfile(found)
      setEmail(found?.email ?? '')
      setPhone(found?.phone ?? '')
      if (!found) {
        setStatus('No profile on file yet - fill in the fields below to create one.')
      }
    } catch (e) {
      setError(String(e))
    }
  }

  // In JSX, a submit handler takes the browser's FormEvent and must call
  // `.preventDefault()` - otherwise the browser does its default full-page
  // form submission (a real page reload), the same as plain HTML forms
  // without any framework at all. Vue/Angular's form directives do this
  // for you implicitly; React expects you to say it explicitly.
  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const saved = await saveProfile(travelerId, email, phone)
      setProfile(saved)
      setStatus('Saved.')
    } catch (e) {
      setError(String(e))
    }
  }

  return (
    <div>
      <h1>Preferences</h1>

      <label htmlFor="travelerId">Traveler ID</label>
      <div className="field-row">
        {/* A "controlled input": its value comes from React state
            (`travelerId`), and every keystroke calls `onChange`, which
            updates that state. This is how React does two-way binding -
            there's no built-in `v-model`/`[(ngModel)]` equivalent; you
            wire the read and the write yourself, explicitly, every time. */}
        <input
          id="travelerId"
          value={travelerId}
          onChange={(e) => setTravelerId(e.target.value)}
          placeholder="traveler-1"
        />
        <button type="button" onClick={loadProfile} disabled={!travelerId}>
          Load
        </button>
      </div>

      {(profile !== null || status) && (
        <form onSubmit={handleSave}>
          <label htmlFor="email">Email</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />

          <label htmlFor="phone">Phone</label>
          <input id="phone" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+15555550000" />

          <button type="submit">Save</button>
        </form>
      )}

      {status && <p className="status">{status}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  )
}

export default PreferencesPage
