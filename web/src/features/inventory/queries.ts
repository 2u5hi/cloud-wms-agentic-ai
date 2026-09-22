import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import { problemMessage } from '@/api/errors'
import type { BalanceFilters } from './filters'

export const PAGE_SIZE = 50

export function useBalances(filters: BalanceFilters, cursor: string | undefined) {
  return useQuery({
    queryKey: ['inventory', 'balances', filters, cursor],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/inventory', {
        params: { query: { ...filters, cursor, limit: PAGE_SIZE } },
      })
      if (error) throw new Error(problemMessage(error, 'Failed to load inventory'))
      return data
    },
    // Keep showing the current page while the next one loads, instead of flashing empty.
    placeholderData: keepPreviousData,
  })
}

export function useZones() {
  return useQuery({
    queryKey: ['zones'],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/zones', { params: { query: { limit: 500 } } })
      if (error) throw new Error(problemMessage(error, 'Failed to load zones'))
      return data.items
    },
    staleTime: 5 * 60_000,
  })
}
