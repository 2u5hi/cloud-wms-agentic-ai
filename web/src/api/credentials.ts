import { useSyncExternalStore } from 'react'

/**
 * The supervisor passcode (ADR 0027), kept for this browser tab only. sessionStorage rather than localStorage:
 * closing the tab signs you out, which is right for a shared demo credential. Every read and write is guarded
 * because storage can be unavailable (private windows, blocked site data); the console then just works
 * signed out.
 */
const KEY = 'wms.supervisor-passcode'

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function createCredentials(storage: () => Store | undefined) {
  const listeners = new Set<() => void>()
  const notify = () => listeners.forEach((listener) => listener())

  const read = (): string | null => {
    try {
      return storage()?.getItem(KEY) ?? null
    } catch {
      return null
    }
  }

  return {
    get: read,
    set(passcode: string) {
      try {
        storage()?.setItem(KEY, passcode)
      } catch {
        // Unavailable storage: stay signed out rather than fail.
      }
      notify()
    },
    clear() {
      try {
        storage()?.removeItem(KEY)
      } catch {
        // Nothing stored, nothing to clear.
      }
      notify()
    },
    /** `Authorization` for a request, or nothing when signed out. */
    headers(): Record<string, string> {
      const passcode = read()
      return passcode ? { Authorization: `Bearer ${passcode}` } : {}
    },
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

export const credentials = createCredentials(() =>
  typeof window === 'undefined' ? undefined : window.sessionStorage,
)

/** Re-renders when the supervisor signs in or out. */
export function useSupervisorPasscode(): string | null {
  return useSyncExternalStore(credentials.subscribe, credentials.get, () => null)
}
