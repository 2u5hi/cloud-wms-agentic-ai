import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

const tone: Record<string, string> = {
  RECEIVED: 'text-muted-foreground',
  PLANNED: 'text-sky-400',
  ALLOCATED: 'text-sky-400',
  RELEASED: 'text-amber-400',
  READY: 'text-amber-400',
  WAITING: 'text-red-400',
  ASSIGNED: 'text-violet-400',
  IN_PROGRESS: 'text-violet-400',
  PICKING: 'text-violet-400',
  PICKED: 'text-emerald-400',
  PACKED: 'text-emerald-400',
  SHIPPED: 'text-emerald-400',
  COMPLETED: 'text-emerald-400',
  CANCELLED: 'text-muted-foreground line-through',
}

/** Warehouse statuses read better as words than colours alone, so the label is always spelled out. */
export function StatusBadge({ status }: { status: string }) {
  return (
    <Badge variant="outline" className={cn('font-mono text-[11px]', tone[status] ?? 'text-foreground')}>
      {status.replace('_', ' ').toLowerCase()}
    </Badge>
  )
}
