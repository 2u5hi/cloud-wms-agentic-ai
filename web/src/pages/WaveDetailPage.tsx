import { Link, useParams } from 'react-router'

import { StatusBadge } from '@/components/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useTasks } from '@/features/tasks/queries'
import { advise, type Blocker } from '@/features/waves/blockers'
import { useWave, useWaveCommand, useWaveDiagnosis } from '@/features/waves/queries'
import { formatAge, formatTime, hoursUntil, plural } from '@/lib/format'
import { cn } from '@/lib/utils'

export function WaveDetailPage() {
  const number = Number(useParams().number)
  const wave = useWave(number)
  const diagnosis = useWaveDiagnosis(number)
  const tasks = useTasks({ wave: number })
  const release = useWaveCommand(number, 'release')
  const cancel = useWaveCommand(number, 'cancel')
  const status = wave.data?.status

  if (wave.isError) {
    return <p className="text-sm text-destructive">{wave.error.message}</p>
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-lg font-semibold">Wave {number}</h2>
        {status && <StatusBadge status={status} />}
        {wave.data && (
          <span className="text-sm text-muted-foreground">
            {plural(wave.data.orders.length, 'order')} · {plural(wave.data.unitsAllocated, 'unit')} · planned{' '}
            {formatTime(wave.data.plannedAt)}
          </span>
        )}
        {status === 'PLANNED' && (
          <div className="ml-auto flex gap-2">
            <Button size="sm" onClick={() => release.mutate()} disabled={release.isPending}>
              Release to the floor
            </Button>
            <Button size="sm" variant="destructive" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
              Cancel
            </Button>
          </div>
        )}
      </div>
      {(release.isError || cancel.isError) && (
        <p className="text-sm text-destructive">{(release.error ?? cancel.error)?.message}</p>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Why this wave is not finishing</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {diagnosis.isPending ? (
            <p className="text-sm text-muted-foreground">Diagnosing…</p>
          ) : diagnosis.isError ? (
            <p className="text-sm text-destructive">{diagnosis.error.message}</p>
          ) : (
            <>
              <div className="flex flex-wrap gap-6 text-sm">
                <Count label="Picks" value={diagnosis.data.picks.total} />
                <Count label="Waiting" value={diagnosis.data.picks.waiting} warn />
                <Count label="Ready" value={diagnosis.data.picks.ready} />
                <Count label="In progress" value={diagnosis.data.picks.assigned + diagnosis.data.picks.inProgress} />
                <Count label="Completed" value={diagnosis.data.picks.completed} />
              </div>

              {diagnosis.data.blockers.length === 0 ? (
                <p className="text-sm text-muted-foreground">Nothing is blocking this wave.</p>
              ) : (
                <ul className="flex flex-col gap-3">
                  {diagnosis.data.blockers.map((blocker, index) => (
                    <BlockerRow key={blocker.kind + blocker.sku + index} blocker={blocker} />
                  ))}
                </ul>
              )}

              {diagnosis.data.atRiskOrders.length > 0 && (
                <p className="text-sm">
                  <span className="font-medium text-amber-400">At risk:</span>{' '}
                  {diagnosis.data.atRiskOrders.map((order) => (
                    <span key={order.order} className="mr-3 text-muted-foreground">
                      <span className="font-mono text-xs">{order.order}</span> {describeCutoff(order.carrierCutoffAt)}{' '}
                      with {plural(order.remainingPicks, 'pick')} left
                    </span>
                  ))}
                </p>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">Orders</h3>
        <div className="rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Order</TableHead>
                <TableHead>Customer</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Cutoff</TableHead>
                <TableHead className="text-right">Priority</TableHead>
                <TableHead className="text-right">Ordered</TableHead>
                <TableHead className="text-right">Allocated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {wave.data?.orders.map((order) => (
                <TableRow key={order.externalRef}>
                  <TableCell className="font-mono text-xs">{order.externalRef}</TableCell>
                  <TableCell>{order.customer}</TableCell>
                  <TableCell>
                    <StatusBadge status={order.status} />
                  </TableCell>
                  <TableCell className="font-mono text-xs">{formatTime(order.carrierCutoffAt)}</TableCell>
                  <Numeric value={order.priority} />
                  <Numeric value={order.unitsOrdered} />
                  <Numeric value={order.unitsAllocated} warn={order.unitsAllocated < order.unitsOrdered} />
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>

      <section className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-medium">Work</h3>
          <Link className="text-xs text-muted-foreground underline underline-offset-4" to={'/tasks?wave=' + number}>
            Open in Tasks
          </Link>
        </div>
        <div className="rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Task</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>SKU</TableHead>
                <TableHead>From</TableHead>
                <TableHead>To</TableHead>
                <TableHead>Worker</TableHead>
                <TableHead className="text-right">Qty</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {tasks.data?.items.map((task) => (
                <TableRow key={task.id}>
                  <TableCell className="font-mono text-xs">{task.id}</TableCell>
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
                  <TableCell className="font-mono text-xs">{task.assignedWorker ?? '—'}</TableCell>
                  <Numeric value={task.quantity} />
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>
    </div>
  )
}

/** A cutoff in the past is missed, not "in 0.0h". */
function describeCutoff(cutoff: string): string {
  const hours = hoursUntil(cutoff)
  return hours < 0 ? `missed its ${formatTime(cutoff)} cutoff` : `cuts off in ${hours.toFixed(1)}h`
}

const SEVERITY = {
  stuck: 'border-l-red-400',
  waiting: 'border-l-amber-400',
  moving: 'border-l-violet-400',
} as const

/** The engine's own sentence first, then what to do about it. Nothing here is inferred. */
function BlockerRow({ blocker }: { blocker: Blocker }) {
  const advice = advise(blocker)
  const replenishment = blocker.replenishment
  return (
    <li className={cn('border-l-2 pl-3', SEVERITY[advice.severity])}>
      <p className="text-sm font-medium">{advice.headline}</p>
      <p className="text-sm text-muted-foreground">{blocker.detail}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        {blocker.affectedPicks > 0 && plural(blocker.affectedPicks, 'pick') + ' · '}
        {plural(blocker.affectedOrders, 'order')}
        {replenishment && (
          <>
            {' · task '}
            <span className="font-mono">{replenishment.taskId}</span>, {plural(replenishment.quantity, 'unit')} from{' '}
            <span className="font-mono">{replenishment.fromLocation}</span> to{' '}
            <span className="font-mono">{replenishment.toLocation}</span>, {formatAge(replenishment.ageMinutes)} old
          </>
        )}
        {blocker.orders.length > 0 && ' · ' + blocker.orders.join(', ')}
      </p>
      <p className="mt-1 text-xs">
        <span className="text-muted-foreground">Fix: </span>
        {advice.action}
      </p>
    </li>
  )
}

function Count({ label, value, warn = false }: { label: string; value: number; warn?: boolean }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={cn('font-mono text-lg tabular-nums', warn && value > 0 && 'text-amber-400')}>{value}</div>
    </div>
  )
}

function Numeric({ value, warn = false }: { value: number; warn?: boolean }) {
  return (
    <TableCell
      className={cn(
        'text-right font-mono text-xs tabular-nums',
        value === 0 && 'text-muted-foreground',
        warn && 'font-semibold text-amber-400',
      )}
    >
      {value.toLocaleString()}
    </TableCell>
  )
}
