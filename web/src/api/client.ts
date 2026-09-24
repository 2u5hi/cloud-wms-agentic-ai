import createClient from 'openapi-fetch'

import { credentials } from './credentials'
import type { paths } from './schema'

// Typed against contracts/openapi.yaml. Regenerate schema.d.ts with `npm run gen:api`
// whenever the contract changes.
export const api = createClient<paths>({ baseUrl: '' })

// Reads are public; commands need the supervisor's credential (ADR 0027). Attached here, once, rather than
// by every call site.
api.use({
  onRequest({ request }) {
    for (const [name, value] of Object.entries(credentials.headers())) {
      request.headers.set(name, value)
    }
    return request
  },
  // A stored passcode that stops working (rotated, mistyped in another tab) would otherwise fail every read,
  // because the API refuses unrecognised credentials outright. Drop it; the retry then reads anonymously.
  onResponse({ response }) {
    if (response.status === 401 && credentials.get()) {
      credentials.clear()
    }
    return response
  },
})
