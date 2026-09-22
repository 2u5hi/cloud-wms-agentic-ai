import createClient from 'openapi-fetch'

import type { paths } from './schema'

// Typed against contracts/openapi.yaml. Regenerate schema.d.ts with `npm run gen:api`
// whenever the contract changes.
export const api = createClient<paths>({ baseUrl: '' })
