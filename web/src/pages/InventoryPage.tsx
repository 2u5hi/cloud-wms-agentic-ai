import { useEffect, useState } from 'react'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'
import { useSearchParams } from 'react-router'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  type BalanceFilters,
  filtersFromParams,
  filtersToParams,
  LOCATION_TYPE_LABELS,
  LOCATION_TYPES,
  type LocationType,
} from '@/features/inventory/filters'
import { useBalances, useZones } from '@/features/inventory/queries'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'
import { cn } from '@/lib/utils'

// Radix Select can't use "" as an item value, so "all" stands for "no filter".
const ALL = 'all'

export function InventoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = filtersFromParams(searchParams)
  const filterKey = filtersToParams(filters).toString()

  // Filter changes add history entries so Back restores the previous view; see FilterBar for typing.
  const updateFilters = (next: BalanceFilters, options?: { replace?: boolean }) =>
    setSearchParams(filtersToParams(next), { replace: options?.replace ?? false })

  // Cursor-based pages: a stack of cursors, reset whenever the filters change.
  const [pages, setPages] = useState<{ key: string; cursors: (string | undefined)[] }>({
    key: filterKey,
    cursors: [undefined],
  })
  const cursors = pages.key === filterKey ? pages.cursors : [undefined]
  const cursor = cursors.at(-1)

  const balances = useBalances(filters, cursor)
  const zones = useZones()
  const nextCursor = balances.data?.nextCursor ?? undefined

  return (
    <div className="flex flex-col gap-4">
      <FilterBar filters={filters} zones={zones.data?.map((zone) => zone.code) ?? []} onChange={updateFilters} />

      <div className={cn('rounded-lg border border-border', balances.isPlaceholderData && 'opacity-60')}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Location</TableHead>
              <TableHead>Zone</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>SKU</TableHead>
              <TableHead className="text-right">On hand</TableHead>
              <TableHead className="text-right">Allocated</TableHead>
              <TableHead className="text-right">Available</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {balances.isPending ? (
              <MessageRow>Loading inventory…</MessageRow>
            ) : balances.isError ? (
              <MessageRow className="text-destructive">{balances.error.message}</MessageRow>
            ) : balances.data.items.length === 0 ? (
              <MessageRow>No stock matches these filters.</MessageRow>
            ) : (
              balances.data.items.map((balance) => (
                <TableRow key={`${balance.location}/${balance.sku}`}>
                  <TableCell className="font-mono text-xs">{balance.location}</TableCell>
                  <TableCell>{balance.zone}</TableCell>
                  <TableCell>
                    <Badge variant="outline">{LOCATION_TYPE_LABELS[balance.locationType]}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{balance.sku}</TableCell>
                  <Quantity value={balance.onHand} />
                  <Quantity value={balance.allocated} />
                  <Quantity value={balance.available} strong />
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
        <span>Page {cursors.length}</span>
        <Button
          variant="outline"
          size="sm"
          disabled={cursors.length === 1}
          onClick={() => setPages({ key: filterKey, cursors: cursors.slice(0, -1) })}
        >
          <ChevronLeft aria-hidden /> Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={!nextCursor || balances.isPlaceholderData}
          onClick={() => setPages({ key: filterKey, cursors: [...cursors, nextCursor] })}
        >
          Next <ChevronRight aria-hidden />
        </Button>
      </div>
    </div>
  )
}

type FilterBarProps = {
  filters: BalanceFilters
  zones: string[]
  onChange: (filters: BalanceFilters, options?: { replace?: boolean }) => void
}

function FilterBar({ filters, zones, onChange }: FilterBarProps) {
  const [sku, setSku] = useState(filters.sku ?? '')

  // Follow the URL when it changes from outside (Back/Forward, a shared link).
  const [urlSku, setUrlSku] = useState(filters.sku)
  if (filters.sku !== urlSku) {
    setUrlSku(filters.sku)
    setSku(filters.sku ?? '')
  }

  // Push the typed SKU to the URL once typing pauses.
  const debouncedSku = useDebouncedValue(sku)
  useEffect(() => {
    const next = debouncedSku.trim() || undefined
    if (next !== filters.sku) {
      // Starting a SKU filter adds a history entry; refining or clearing it replaces that entry.
      onChange({ ...filters, sku: next }, { replace: filters.sku !== undefined })
    }
    // Only react to the debounced input; filters and onChange are read fresh from this render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSku])

  const hasFilters = Boolean(filters.sku || filters.zone || filters.type)

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Input
        aria-label="SKU"
        placeholder="SKU code, e.g. SKU-10001"
        className="w-64 font-mono text-xs"
        value={sku}
        onChange={(event) => setSku(event.target.value)}
      />
      <Select value={filters.zone ?? ALL} onValueChange={(zone) => onChange({ ...filters, zone: zone === ALL ? undefined : zone })}>
        <SelectTrigger aria-label="Zone" className="w-36">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>All zones</SelectItem>
          {zones.map((zone) => (
            <SelectItem key={zone} value={zone}>
              Zone {zone}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select
        value={filters.type ?? ALL}
        onValueChange={(type) => onChange({ ...filters, type: type === ALL ? undefined : (type as LocationType) })}
      >
        <SelectTrigger aria-label="Location type" className="w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>All location types</SelectItem>
          {LOCATION_TYPES.map((type) => (
            <SelectItem key={type} value={type}>
              {LOCATION_TYPE_LABELS[type]}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {hasFilters && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            setSku('')
            onChange({})
          }}
        >
          <X aria-hidden /> Clear
        </Button>
      )}
    </div>
  )
}

function Quantity({ value, strong = false }: { value: number; strong?: boolean }) {
  return (
    <TableCell
      className={cn(
        'text-right font-mono text-xs tabular-nums',
        value === 0 && 'text-muted-foreground',
        strong && value > 0 && 'font-semibold',
      )}
    >
      {value.toLocaleString()}
    </TableCell>
  )
}

function MessageRow({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <TableRow>
      <TableCell colSpan={7} className={cn('h-24 text-center text-muted-foreground', className)}>
        {children}
      </TableCell>
    </TableRow>
  )
}
