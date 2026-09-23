import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import { problemMessage } from '@/api/errors'

export const TASK_STATUSES = ['WAITING', 'READY', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const
export const TASK_TYPES = ['PICK', 'REPLENISH', 'COUNT'] as const

export type TaskFilters = {
  wave?: number
  status?: string
  type?: string
}

export function useTasks(filters: TaskFilters, cursor?: string) {
  return useQuery({
    queryKey: ['tasks', filters, cursor],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/tasks', {
        params: { query: { ...filters, cursor, limit: 100 } },
      })
      if (error) throw new Error(problemMessage(error, 'Failed to load tasks'))
      return data
    },
    placeholderData: keepPreviousData,
  })
}
