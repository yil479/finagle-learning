// Every type here uses snake_case field names on purpose, even though that's
// not typical TypeScript style - it matches the backend's actual JSON wire
// format exactly (Finatra's convention, see the Scala backend's own
// gotchas). Keeping these as a direct mirror avoids a whole extra mapping
// layer just to rename fields for a small console like this one.

export interface TravelerProfile {
  traveler_id: string
  email: string
  phone: string
}

export interface DecisionLogEntry {
  traveler_id: string
  timestamp: string
  event_id: string
  event_type: string
  decision: string
  detail: string
}

export interface MessageTemplate {
  event_type: string
  email_subject: string
  email_body_text: string
  email_body_html: string
  sms_body: string
}

export interface DryRunResult {
  decision: string
  detail: string
  trace: string[]
}

// The four event types are a fixed, closed set on the backend (a sealed
// trait with exactly these four case classes) - hardcoding them here
// mirrors that, rather than fetching a "list of event types" from
// somewhere that doesn't exist.
export const EVENT_TYPES = ['FlightDelayed', 'PriceDropped', 'TripStartingSoon', 'FlightCancelled'] as const
export type EventType = (typeof EVENT_TYPES)[number]

async function request<T>(path: string, init?: RequestInit): Promise<T | null> {
  const response = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} failed: ${response.status}`)
  }
  return response.json() as Promise<T>
}

export function getProfile(travelerId: string): Promise<TravelerProfile | null> {
  return request<TravelerProfile>(`/travelers/${encodeURIComponent(travelerId)}/profile`)
}

export function saveProfile(travelerId: string, email: string, phone: string): Promise<TravelerProfile | null> {
  return request<TravelerProfile>(`/travelers/${encodeURIComponent(travelerId)}/profile`, {
    method: 'POST',
    body: JSON.stringify({ email, phone }),
  })
}

export async function getDecisionLog(travelerId: string): Promise<DecisionLogEntry[]> {
  const result = await request<{ entries: DecisionLogEntry[] }>(
    `/travelers/${encodeURIComponent(travelerId)}/log`,
  )
  return result?.entries ?? []
}

export function getTemplate(eventType: EventType): Promise<MessageTemplate | null> {
  return request<MessageTemplate>(`/templates/${eventType}`)
}

export function saveTemplate(template: MessageTemplate): Promise<MessageTemplate | null> {
  return request<MessageTemplate>(`/templates/${template.event_type}`, {
    method: 'PUT',
    body: JSON.stringify(template),
  })
}

// One route per event type, matching DecisionController.scala exactly -
// an explicit table instead of deriving the path from the name, so it's
// obvious at a glance that these four are correct rather than trusting a
// naming transformation to get every case right.
const DRY_RUN_PATHS: Record<EventType, string> = {
  FlightDelayed: '/decisions/dry-run',
  PriceDropped: '/decisions/price-dropped/dry-run',
  TripStartingSoon: '/decisions/trip-starting-soon/dry-run',
  FlightCancelled: '/decisions/flight-cancelled/dry-run',
}

// The dry-run request bodies differ per event type (a FlightDelayed needs
// delay_minutes, a PriceDropped needs old_price/new_price, and so on) -
// `Record<string, unknown>` keeps this one function usable for all four
// without a separate call per event type, since the caller already knows
// which fields it built for the chosen event type.
export function dryRun(eventType: EventType, body: Record<string, unknown>): Promise<DryRunResult | null> {
  return request<DryRunResult>(DRY_RUN_PATHS[eventType], { method: 'POST', body: JSON.stringify(body) })
}
