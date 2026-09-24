import { useState } from 'react'
import { LogOut, ShieldCheck } from 'lucide-react'

import { credentials, useSupervisorPasscode } from '@/api/credentials'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

/**
 * Anyone can browse; running commands and approving the agent's proposals needs the supervisor passcode
 * (ADR 0027). The passcode is checked against the API before it is kept, so a typo or the agent's token is
 * caught here instead of on the first command.
 */
export function SupervisorSignIn() {
  const passcode = useSupervisorPasscode()
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  if (passcode) {
    return (
      <div className="flex items-center gap-2">
        <Badge variant="outline" className="gap-1">
          <ShieldCheck className="size-3" aria-hidden />
          Supervisor
        </Badge>
        <Button variant="ghost" size="sm" onClick={() => credentials.clear()}>
          <LogOut aria-hidden /> Sign out
        </Button>
      </div>
    )
  }

  if (!open) {
    return (
      <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
        Supervisor sign-in
      </Button>
    )
  }

  const signIn = async (event: React.FormEvent) => {
    event.preventDefault()
    setChecking(true)
    setError(null)
    try {
      const response = await fetch('/api/v1/me', { headers: { Authorization: `Bearer ${value}` } })
      const me = response.ok ? await response.json() : null
      if (me?.role === 'SUPERVISOR') {
        credentials.set(value)
        setOpen(false)
        setValue('')
      } else {
        setError(response.status === 401 ? 'Passcode not recognised' : 'That is not a supervisor passcode')
      }
    } catch {
      setError('The API is unreachable')
    } finally {
      setChecking(false)
    }
  }

  return (
    <form onSubmit={signIn} className="flex items-center gap-2">
      <Input
        type="password"
        aria-label="Supervisor passcode"
        placeholder="Supervisor passcode"
        autoFocus
        className="h-8 w-44"
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      <Button type="submit" size="sm" disabled={!value || checking}>
        Sign in
      </Button>
      <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
        Cancel
      </Button>
      {error && <span className="text-xs text-destructive">{error}</span>}
    </form>
  )
}
