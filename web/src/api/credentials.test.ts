import { describe, expect, it, vi } from 'vitest'

import { createCredentials } from './credentials'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  }
}

describe('credentials', () => {
  it('sends nothing until a supervisor signs in', () => {
    const credentials = createCredentials(() => memoryStorage())

    expect(credentials.headers()).toEqual({})
  })

  it('sends the passcode as a bearer credential once signed in, and stops after signing out', () => {
    const storage = memoryStorage()
    const credentials = createCredentials(() => storage)

    credentials.set('let-me-in')
    expect(credentials.headers()).toEqual({ Authorization: 'Bearer let-me-in' })

    credentials.clear()
    expect(credentials.headers()).toEqual({})
  })

  it('tells subscribers when the sign-in changes', () => {
    const credentials = createCredentials(() => memoryStorage())
    const listener = vi.fn()
    credentials.subscribe(listener)

    credentials.set('let-me-in')
    credentials.clear()

    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('works signed out when storage is unavailable', () => {
    const credentials = createCredentials(() => ({
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
      removeItem: () => {
        throw new Error('blocked')
      },
    }))

    credentials.set('let-me-in')
    expect(credentials.get()).toBeNull()
    expect(credentials.headers()).toEqual({})
  })
})
