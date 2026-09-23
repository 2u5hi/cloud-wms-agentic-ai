import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { commandHeaders } from '@/api/command'
import { problemMessage } from '@/api/errors'

export function useWaves(cursor?: string) {
  return useQuery({
    queryKey: ['waves', cursor],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/waves', { params: { query: { cursor, limit: 50 } } })
      if (error) throw new Error(problemMessage(error, 'Failed to load waves'))
      return data
    },
    placeholderData: keepPreviousData,
  })
}

export function useWave(number: number) {
  return useQuery({
    queryKey: ['wave', number],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/waves/{number}', { params: { path: { number } } })
      if (error) throw new Error(problemMessage(error, 'Failed to load wave'))
      return data
    },
  })
}

/** Why the wave is not finishing. Computed by the WMS, not by a model (ADR 0022). */
export function useWaveDiagnosis(number: number) {
  return useQuery({
    queryKey: ['wave', number, 'diagnosis'],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/waves/{number}/diagnosis', {
        params: { path: { number }, query: { atRiskWithinHours: 4 } },
      })
      if (error) throw new Error(problemMessage(error, 'Failed to diagnose wave'))
      return data
    },
    refetchInterval: 15_000,
  })
}

export function usePlanWave() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ preview }: { preview: boolean }) => {
      const { data, error } = await api.POST('/api/v1/waves/plan', {
        params: { query: { preview }, header: commandHeaders() },
        body: {},
      })
      if (error) throw new Error(problemMessage(error, 'Failed to plan a wave'))
      return data
    },
    onSuccess: (_, variables) => {
      if (!variables.preview) {
        queryClient.invalidateQueries({ queryKey: ['waves'] })
        queryClient.invalidateQueries({ queryKey: ['orders'] })
      }
    },
  })
}

export function useWaveCommand(number: number, action: 'release' | 'cancel') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const path = action === 'release' ? '/api/v1/waves/{number}/release' : '/api/v1/waves/{number}/cancel'
      const { data, error } = await api.POST(path, {
        params: { path: { number }, header: commandHeaders() },
      })
      if (error) throw new Error(problemMessage(error, `Failed to ${action} the wave`))
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wave', number] })
      queryClient.invalidateQueries({ queryKey: ['waves'] })
      queryClient.invalidateQueries({ queryKey: ['tasks'] })
      queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
  })
}
