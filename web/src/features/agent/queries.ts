import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { commandHeaders } from '@/api/command'
import { credentials } from '@/api/credentials'
import { problemMessage } from '@/api/errors'
import type { components } from '@/api/schema'

export type Proposal = components['schemas']['ProposalView']

/** The agent runs as its own service; the console talks to it through the same origin (see vite.config.ts). */
const AGENT_BASE = import.meta.env.VITE_AGENT_BASE ?? '/agent'

export type Investigation = {
  wave: number
  answer: string
  evidence: string[]
  /** Each fix the agent filed; the ones the WMS refused carry the reason instead of a stored proposal. */
  proposals: (Proposal | { error: string; code?: string; payload: { task: number; worker: string } })[]
  toolCalls: string[]
  usage: { costUsd: number; model: string; inputTokens: number; outputTokens: number }
}

export function useInvestigate(wave: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (question?: string) => {
      const response = await fetch(`${AGENT_BASE}/investigate`, {
        method: 'POST',
        // Starting an investigation costs money, so the agent asks for the supervisor's credential too.
        headers: { 'Content-Type': 'application/json', ...credentials.headers() },
        body: JSON.stringify({ wave, question }),
      })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        throw new Error(body?.detail ?? `The agent is unavailable (${response.status})`)
      }
      return body as Investigation
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['proposals', wave] }),
  })
}

export function useProposals(wave: number) {
  return useQuery({
    queryKey: ['proposals', wave],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/proposals', { params: { query: { wave, limit: 20 } } })
      if (error) throw new Error(problemMessage(error, 'Failed to load proposals'))
      return data.items
    },
  })
}

/** Approving runs the proposed command in the WMS; rejecting only records the decision. */
export function useProposalDecision(wave: number, decision: 'approve' | 'reject') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, note }: { id: number; note?: string }) => {
      const path = decision === 'approve' ? '/api/v1/proposals/{id}/approve' : '/api/v1/proposals/{id}/reject'
      const { data, error } = await api.POST(path, {
        params: { path: { id }, header: commandHeaders() },
        body: { note },
      })
      if (error) throw new Error(problemMessage(error, `Failed to ${decision} the proposal`))
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['proposals', wave] })
      queryClient.invalidateQueries({ queryKey: ['wave', wave] })
      queryClient.invalidateQueries({ queryKey: ['tasks'] })
    },
  })
}
