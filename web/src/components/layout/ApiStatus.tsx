import { useQuery } from '@tanstack/react-query'

import { Badge } from '@/components/ui/badge'
import { downComponents, fetchHealth, toApiStatus } from '@/features/system/health'
import { cn } from '@/lib/utils'

const labels = {
  checking: 'Checking API',
  online: 'API online',
  degraded: 'API degraded',
  offline: 'API offline',
} as const

const dots = {
  checking: 'bg-muted-foreground',
  online: 'bg-emerald-500',
  degraded: 'bg-amber-500',
  offline: 'bg-red-500',
} as const

export function ApiStatus() {
  const health = useQuery({
    queryKey: ['system', 'health'],
    queryFn: fetchHealth,
    refetchInterval: 10_000,
    retry: false,
  })

  const status = health.isPending ? 'checking' : health.isError ? 'offline' : toApiStatus(health.data)
  const down = downComponents(health.data)

  return (
    <Badge
      variant="outline"
      role="status"
      title={down.length > 0 ? `Down: ${down.join(', ')}` : undefined}
    >
      <span className={cn('size-1.5 rounded-full', dots[status])} aria-hidden />
      {labels[status]}
    </Badge>
  )
}
