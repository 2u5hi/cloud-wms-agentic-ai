/**
 * The human-readable message from an API error. Errors are RFC 9457 problem+json bodies, which the
 * contract doesn't yet declare per operation, so openapi-fetch types them loosely.
 */
export function problemMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object' && 'detail' in error && typeof error.detail === 'string') {
    return error.detail
  }
  return fallback
}
