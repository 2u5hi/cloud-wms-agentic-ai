/**
 * Every POST needs a unique Idempotency-Key (ADR 0007): retrying a command with the same key replays
 * the original response instead of running it twice.
 */
export function idempotencyKey(): string {
  return crypto.randomUUID()
}

export function commandHeaders(): { 'Idempotency-Key': string } {
  return { 'Idempotency-Key': idempotencyKey() }
}
