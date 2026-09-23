/** Timestamps arrive as UTC instants (ADR 0018) and are shown in the viewer's own zone. */
export function formatTime(instant: string): string {
  return new Date(instant).toLocaleString(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatAge(minutes: number): string {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  return hours < 24 ? `${hours}h ${minutes % 60}m` : `${Math.floor(hours / 24)}d ${hours % 24}h`
}

/** Hours until an instant, negative once it has passed. */
export function hoursUntil(instant: string, now: number = Date.now()): number {
  return (new Date(instant).getTime() - now) / 3_600_000
}

/** "1 order", "2 orders" — counts in this console are usually small enough to read as sentences. */
export function plural(count: number, noun: string, plural = noun + 's'): string {
  return `${count.toLocaleString()} ${count === 1 ? noun : plural}`
}
