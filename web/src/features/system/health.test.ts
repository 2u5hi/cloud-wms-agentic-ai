import { afterEach, describe, expect, it, vi } from 'vitest'

import { downComponents, fetchHealth, toApiStatus } from './health'

describe('toApiStatus', () => {
  it('is online when health is UP', () => {
    expect(toApiStatus({ status: 'UP' })).toBe('online')
  })

  it('is degraded when the API answers but is not UP', () => {
    expect(toApiStatus({ status: 'DOWN', components: { db: { status: 'DOWN' } } })).toBe('degraded')
  })

  it('is offline when there is no health response', () => {
    expect(toApiStatus(undefined)).toBe('offline')
  })
})

describe('downComponents', () => {
  it('lists components that are not UP', () => {
    const health = {
      status: 'DOWN',
      components: { db: { status: 'DOWN' }, diskSpace: { status: 'UP' }, ping: { status: 'UP' } },
    }
    expect(downComponents(health)).toEqual(['db'])
  })

  it('is empty when there are no components', () => {
    expect(downComponents({ status: 'UP' })).toEqual([])
    expect(downComponents(undefined)).toEqual([])
  })
})

describe('fetchHealth', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const stubFetch = (status: number, body: unknown) =>
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), { status })))

  it('returns the body when healthy', async () => {
    stubFetch(200, { status: 'UP' })
    await expect(fetchHealth()).resolves.toEqual({ status: 'UP' })
  })

  it('returns the body on 503 so a down database reads as degraded, not offline', async () => {
    stubFetch(503, { status: 'DOWN', components: { db: { status: 'DOWN' } } })
    await expect(fetchHealth()).resolves.toMatchObject({ status: 'DOWN' })
  })

  it('throws when the API is unreachable (dev proxy returns 502)', async () => {
    stubFetch(502, {})
    await expect(fetchHealth()).rejects.toThrow('HTTP 502')
  })
})
