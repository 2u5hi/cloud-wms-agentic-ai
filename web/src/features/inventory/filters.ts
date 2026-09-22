import type { components } from '@/api/schema'

export type LocationType = components['schemas']['LocationView']['type']

export const LOCATION_TYPES: LocationType[] = ['FORWARD_PICK', 'RESERVE', 'STAGING', 'PACK', 'DOCK']

export const LOCATION_TYPE_LABELS: Record<LocationType, string> = {
  FORWARD_PICK: 'Forward pick',
  RESERVE: 'Reserve',
  STAGING: 'Staging',
  PACK: 'Pack',
  DOCK: 'Dock',
}

export type BalanceFilters = {
  sku?: string
  zone?: string
  type?: LocationType
}

/** Filters live in the URL so a filtered view can be shared, bookmarked, and navigated back to. */
export function filtersFromParams(params: URLSearchParams): BalanceFilters {
  const type = params.get('type')
  return {
    sku: params.get('sku')?.trim() || undefined,
    zone: params.get('zone') || undefined,
    type: LOCATION_TYPES.includes(type as LocationType) ? (type as LocationType) : undefined,
  }
}

export function filtersToParams(filters: BalanceFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.sku?.trim()) params.set('sku', filters.sku.trim())
  if (filters.zone) params.set('zone', filters.zone)
  if (filters.type) params.set('type', filters.type)
  return params
}
