import { Link } from 'react-router'

import { StatusBadge } from '@/components/StatusBadge'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { usePlanWave, useWaves } from '@/features/waves/queries'
import { formatTime, plural } from '@/lib/format'
import { cn } from '@/lib/utils'

export function WavesPage() {
  const waves = useWaves()
  const plan = usePlanWave()
  const preview = plan.data?.preview ? plan.data : undefined

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={() => plan.mutate({ preview: true })} disabled={plan.isPending}>
          Plan a wave
        </Button>
        {/* Previewing writes nothing and locks no stock (ADR 0020), so the numbers are advisory. */}
        {preview && (
          <div className="flex items-center gap-3 text-sm">
            <span className="text-muted-foreground">
              Would cover {plural(preview.orders, 'order')}: {plural(preview.unitsAllocated, 'unit')},{' '}
              {plural(preview.pickTasks, 'pick')}, {plural(preview.replenishmentTasks, 'replenishment')}
              {preview.unitsShort > 0 && `, ${plural(preview.unitsShort, 'unit')} short`}
            </span>
            <Button size="sm" onClick={() => plan.mutate({ preview: false })} disabled={plan.isPending}>
              Confirm
            </Button>
          </div>
        )}
        {plan.data && !plan.data.preview && (
          <span className="text-sm text-muted-foreground">
            {plan.data.waveNumber ? (
              <>
                Planned <Link className="underline" to={`/waves/${plan.data.waveNumber}`}>wave {plan.data.waveNumber}</Link>
              </>
            ) : (
              'Nothing to plan: no open orders.'
            )}
          </span>
        )}
        {plan.isError && <span className="text-sm text-destructive">{plan.error.message}</span>}
      </div>

      <div className={cn('rounded-lg border border-border', waves.isPlaceholderData && 'opacity-60')}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Wave</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Planned</TableHead>
              <TableHead>Released</TableHead>
              <TableHead className="text-right">Orders</TableHead>
              <TableHead className="text-right">Units</TableHead>
              <TableHead className="text-right">Tasks</TableHead>
              <TableHead className="text-right">Outstanding</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {waves.isPending ? (
              <MessageRow>Loading waves…</MessageRow>
            ) : waves.isError ? (
              <MessageRow className="text-destructive">{waves.error.message}</MessageRow>
            ) : waves.data.items.length === 0 ? (
              <MessageRow>No waves yet. Plan one to create pick work.</MessageRow>
            ) : (
              waves.data.items.map((wave) => {
                const outstanding = wave.tasks.total - wave.tasks.completed - wave.tasks.cancelled
                return (
                  <TableRow key={wave.number}>
                    <TableCell>
                      <Link className="font-mono text-xs underline underline-offset-4" to={`/waves/${wave.number}`}>
                        {wave.number}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={wave.status} />
                    </TableCell>
                    <TableCell className="font-mono text-xs">{formatTime(wave.plannedAt)}</TableCell>
                    <TableCell className="font-mono text-xs">
                      {wave.releasedAt ? formatTime(wave.releasedAt) : <span className="text-muted-foreground">—</span>}
                    </TableCell>
                    <Quantity value={wave.orders} />
                    <Quantity value={wave.unitsAllocated} />
                    <Quantity value={wave.tasks.total} />
                    <Quantity value={outstanding} warn={wave.tasks.waiting > 0} />
                  </TableRow>
                )
              })
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function Quantity({ value, warn = false }: { value: number; warn?: boolean }) {
  return (
    <TableCell
      className={cn(
        'text-right font-mono text-xs tabular-nums',
        value === 0 && 'text-muted-foreground',
        warn && value > 0 && 'font-semibold text-amber-400',
      )}
    >
      {value.toLocaleString()}
    </TableCell>
  )
}

function MessageRow({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <TableRow>
      <TableCell colSpan={8} className={cn('h-24 text-center text-muted-foreground', className)}>
        {children}
      </TableCell>
    </TableRow>
  )
}
