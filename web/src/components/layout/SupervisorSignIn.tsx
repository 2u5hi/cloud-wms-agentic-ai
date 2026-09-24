import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { LogOut, RotateCcw, ShieldCheck } from 'lucide-react'
import { useNavigate } from 'react-router'

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
  const [resetting, setResetting] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  // The demo's own reset (ADR 0029): rebuilds the warehouse and the blocked wave, undoing whatever the last
  // visitor approved. Only on the demo and local builds; elsewhere the endpoint does not exist. It takes about
  // 20 seconds on the deployed demo, so it says so, and says when it's done, and lands on the rebuilt wave.
  const resetDemo = async () => {
    if (!window.confirm('Reset the demo? This rebuilds the warehouse and the blocked wave from scratch.')) return
    setResetting(true)
    setError(null)
    setNotice('Rebuilding the demo — about 20 seconds…')
    try {
      const response = await fetch('/demo/reset', { method: 'POST', headers: credentials.headers() })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        setNotice(null)
        setError(body?.detail ?? `Reset failed (${response.status})`)
        return
      }
      await queryClient.resetQueries()
      setNotice(`Demo reset: wave ${body.wave} is blocked again`)
      navigate(`/waves/${body.wave}`)
      window.setTimeout(() => setNotice(null), 8000)
    } catch {
      setNotice(null)
      setError('The API is unreachable')
    } finally {
      setResetting(false)
    }
  }

  if (passcode) {
    return (
      <div className="flex items-center gap-2">
        {notice && (
          <span role="status" className="text-xs text-muted-foreground">
            {notice}
          </span>
        )}
        {error && <span className="text-xs text-destructive">{error}</span>}
        <Button variant="ghost" size="sm" onClick={resetDemo} disabled={resetting}>
          <RotateCcw aria-hidden className={resetting ? 'animate-spin' : undefined} />{' '}
          {resetting ? 'Resetting…' : 'Reset demo'}
        </Button>
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
