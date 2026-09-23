import { Link, useSearchParams } from 'react-router'

import { StatusBadge } from '@/components/StatusBadge'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { TASK_STATUSES, TASK_TYPES, useTasks } from '@/features/tasks/queries'
import { cn } from '@/lib/utils'

const ALL = 'all'

export function TasksPage() {
  const [params, setParams] = useSearchParams()
  const wave = params.get('wave')
  const status = params.get('status') ?? undefined
  const type = params.get('type') ?? undefined

  const tasks = useTasks({
    wave: wave ? Number(wave) : undefined,
    status,
    type,
  })

  const update = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value === ALL) {
      next.delete(key)
    } else {
      next.set(key, value)
    }
    setParams(next)
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Select value={type ?? ALL} onValueChange={(value) => update('type', value)}>
          <SelectTrigger aria-label="Type" className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All types</SelectItem>
            {TASK_TYPES.map((value) => (
              <SelectItem key={value} value={value}>
                {value.toLowerCase()}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={status ?? ALL} onValueChange={(value) => update('status', value)}>
          <SelectTrigger aria-label="Status" className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All statuses</SelectItem>
            {TASK_STATUSES.map((value) => (
              <SelectItem key={value} value={value}>
                {value.replace('_', ' ').toLowerCase()}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {wave && (
          <span className="flex items-center gap-2 text-sm text-muted-foreground">
            Wave{' '}
            <Link className="font-mono underline underline-offset-4" to={'/waves/' + wave}>
              {wave}
            </Link>
            <Button variant="ghost" size="sm" onClick={() => update('wave', ALL)}>
              Clear
            </Button>
          </span>
        )}
      </div>

      <div className={cn('rounded-lg border border-border', tasks.isPlaceholderData && 'opacity-60')}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Task</TableHead>
              <TableHead>Wave</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>SKU</TableHead>
              <TableHead>From</TableHead>
              <TableHead>To</TableHead>
              <TableHead>Equipment</TableHead>
              <TableHead>Worker</TableHead>
              <TableHead className="text-right">Qty</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {tasks.isPending ? (
              <MessageRow>Loading work…</MessageRow>
            ) : tasks.isError ? (
              <MessageRow className="text-destructive">{tasks.error.message}</MessageRow>
            ) : tasks.data.items.length === 0 ? (
              <MessageRow>No work matches these filters.</MessageRow>
            ) : (
              tasks.data.items.map((task) => (
                <TableRow key={task.id}>
                  <TableCell className="font-mono text-xs">{task.id}</TableCell>
                  <TableCell className="font-mono text-xs">
                    {task.wave ? (
                      <Link className="underline underline-offset-4" to={'/waves/' + task.wave}>
                        {task.wave}
                      </Link>
                    ) : (
                      '—'
                    )}
                  </TableCell>
                  <TableCell className="text-xs">{task.type.toLowerCase()}</TableCell>
                  <TableCell>
                    <StatusBadge status={task.status} />
                    {task.waitingOnTask && (
                      <span className="ml-2 text-xs text-muted-foreground">on {task.waitingOnTask}</span>
                    )}
                  </TableCell>
                  <TableCell className="font-mono text-xs">{task.sku}</TableCell>
                  <TableCell className="font-mono text-xs">{task.fromLocation ?? '—'}</TableCell>
                  <TableCell className="font-mono text-xs">{task.toLocation ?? '—'}</TableCell>
                  <TableCell className="text-xs">{task.requiredEquipment ?? '—'}</TableCell>
                  <TableCell className="font-mono text-xs">{task.assignedWorker ?? '—'}</TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">{task.quantity}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function MessageRow({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <TableRow>
      <TableCell colSpan={10} className={cn('h-24 text-center text-muted-foreground', className)}>
        {children}
      </TableCell>
    </TableRow>
  )
}
