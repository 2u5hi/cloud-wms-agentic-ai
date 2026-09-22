// Spring Boot Actuator health. Not part of the OpenAPI contract (actuator is excluded from it),
// so this uses fetch directly instead of the generated client.

export type HealthResponse = {
  status?: string
  components?: Record<string, { status?: string }>
}

export type ApiStatus = 'online' | 'degraded' | 'offline'

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch('/actuator/health')
  // Actuator answers 503 with a JSON body when a component (e.g. the database) is down.
  // Anything else that isn't 2xx (including the dev proxy's 502 when wms-core is not running)
  // means the API is unreachable.
  if (!response.ok && response.status !== 503) {
    throw new Error(`Health check failed with HTTP ${response.status}`)
  }
  return (await response.json()) as HealthResponse
}

export function toApiStatus(health: HealthResponse | undefined): ApiStatus {
  if (!health) return 'offline'
  return health.status === 'UP' ? 'online' : 'degraded'
}

export function downComponents(health: HealthResponse | undefined): string[] {
  return Object.entries(health?.components ?? {})
    .filter(([, component]) => component.status !== 'UP')
    .map(([name]) => name)
}
