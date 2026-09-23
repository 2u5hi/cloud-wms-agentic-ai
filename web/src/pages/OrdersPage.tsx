import { useSearchParams } from 'react-router'

import { StatusBadge } from '@/components/StatusBadge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ORDER_STATUSES, type OrderStatus, useOrders } from '@/features/orders/queries'
import { formatTime } from '@/lib/format'
import { cn } from '@/lib/utils'

const ALL = 'all'
const COLUMNS = 9

export function OrdersPage() {
  const [params, setParams] = useSearchParams()
  const raw = params.get('status')
  const status = ORDER_STATUSES.includes(raw as OrderStatus) ? (raw as OrderStatus) : undefined
  const orders = useOrders(status, undefined)

  return (
    <div className="flex flex-col gap-4">
      <Select value={status ?? ALL} onValueChange={(next) => setParams(next === ALL ? {} : { status: next })}>
        <SelectTrigger aria-label="Status" className="w-48">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>All statuses</SelectItem>
          {ORDER_STATUSES.map((value) => (
            <SelectItem key={value} value={value}>
              {value.toLowerCase()}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <div className={cn('rounded-lg border border-border', orders.isPlaceholderData && 'opacity-60')}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Order</TableHead>
              <TableHead>Customer</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Carrier</TableHead>
              <TableHead>Cutoff</TableHead>
              <TableHead className="text-right">Priority</TableHead>
              <TableHead className="text-right">Ordered</TableHead>
              <TableHead className="text-right">Allocated</TableHead>
              <TableHead className="text-right">Short</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {orders.isPending ? (
              <MessageRow>Loading orders…</MessageRow>
            ) : orders.isError ? (
              <MessageRow className="text-destructive">{orders.error.message}</MessageRow>
            ) : orders.data.items.length === 0 ? (
              <MessageRow>No orders match this filter.</MessageRow>
            ) : (
              orders.data.items.map((order) => (
                <TableRow key={order.externalRef}>
                  <TableCell className="font-mono text-xs">{order.externalRef}</TableCell>
                  <TableCell>{order.customer}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <StatusBadge status={order.status} />
                      {order.onHold && (
                        <span className="text-xs text-red-400" title={order.holdReason ?? undefined}>
                          on hold
                        </span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>{order.carrier}</TableCell>
                  <TableCell className="font-mono text-xs">{formatTime(order.carrierCutoffAt)}</TableCell>
                  <Quantity value={order.priority} />
                  <Quantity value={order.unitsOrdered} />
                  <Quantity value={order.unitsAllocated} />
                  <Quantity value={order.shortQuantity} warn />
                </TableRow>
              ))
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
      <TableCell colSpan={COLUMNS} className={cn('h-24 text-center text-muted-foreground', className)}>
        {children}
      </TableCell>
    </TableRow>
  )
}
