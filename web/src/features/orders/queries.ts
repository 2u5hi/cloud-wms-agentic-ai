import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import { problemMessage } from '@/api/errors'
import type { components } from '@/api/schema'

export type OrderStatus = components['schemas']['OrderSummaryView']['status']

export const ORDER_STATUSES: OrderStatus[] = [
  'RECEIVED',
  'ALLOCATED',
  'RELEASED',
  'PICKING',
  'PICKED',
  'PACKED',
  'SHIPPED',
  'CANCELLED',
]

export function useOrders(status: OrderStatus | undefined, cursor: string | undefined) {
  return useQuery({
    queryKey: ['orders', status, cursor],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/orders', { params: { query: { status, cursor, limit: 50 } } })
      if (error) throw new Error(problemMessage(error, 'Failed to load orders'))
      return data
    },
    placeholderData: keepPreviousData,
  })
}
