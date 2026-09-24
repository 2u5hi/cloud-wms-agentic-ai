import { Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { StatusBadge } from '@/components/StatusBadge'
import {
  type Proposal,
  useInvestigate,
  useProposalDecision,
  useProposals,
} from '@/features/agent/queries'
import { formatTime } from '@/lib/format'

/**
 * The agent explains the diagnosis and can suggest fixes, one per blocked task. It never changes the
 * warehouse: approving a proposal is what runs the command, and that happens in wms-core (ADR 0024, 0027).
 */
export function AgentPanel({ wave }: { wave: number }) {
  const investigate = useInvestigate(wave)
  const proposals = useProposals(wave)
  const open = proposals.data?.filter((proposal) => proposal.status === 'PROPOSED') ?? []
  const decided = proposals.data?.filter((proposal) => proposal.status !== 'PROPOSED') ?? []

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2 text-sm">
          <Sparkles className="size-4" aria-hidden />
          Ask the agent
        </CardTitle>
        <Button size="sm" variant="outline" onClick={() => investigate.mutate(undefined)} disabled={investigate.isPending}>
          {investigate.isPending ? 'Investigating…' : 'Why is this wave blocked?'}
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {investigate.isError && <p className="text-sm text-destructive">{investigate.error.message}</p>}

        {investigate.data && (
          <div className="flex flex-col gap-2">
            <p className="text-sm">{investigate.data.answer}</p>
            <ul className="flex flex-col gap-1">
              {investigate.data.evidence.map((line) => (
                <li key={line} className="text-xs text-muted-foreground">
                  · {line}
                </li>
              ))}
            </ul>
            <p className="text-xs text-muted-foreground">
              {investigate.data.usage.model} · {investigate.data.toolCalls.length} tool calls ·{' '}
              {investigate.data.usage.costUsd.toFixed(4)} USD
            </p>
            {investigate.data.proposals
              .filter((proposal) => 'error' in proposal)
              .map((refused) => (
                <p key={refused.payload.task} className="text-xs text-destructive">
                  The WMS refused a proposal for task {refused.payload.task}: {refused.error}
                </p>
              ))}
          </div>
        )}

        {open.length === 0 && decided.length === 0 && !investigate.data && (
          <p className="text-sm text-muted-foreground">
            The blockers above come from the WMS itself. The agent adds an explanation and, when a
            reassignment would help, a proposal you can approve.
          </p>
        )}

        {open.map((proposal) => (
          <ProposalCard key={proposal.id} proposal={proposal} wave={wave} />
        ))}
        {decided.map((proposal) => (
          <ProposalCard key={proposal.id} proposal={proposal} wave={wave} />
        ))}
      </CardContent>
    </Card>
  )
}

function ProposalCard({ proposal, wave }: { proposal: Proposal; wave: number }) {
  const approve = useProposalDecision(wave, 'approve')
  const reject = useProposalDecision(wave, 'reject')
  const pending = proposal.status === 'PROPOSED'
  const error = approve.error ?? reject.error

  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs">#{proposal.id}</span>
        <span className="text-sm font-medium">{describe(proposal)}</span>
        <StatusBadge status={proposal.status} />
        <span className="ml-auto text-xs text-muted-foreground">
          {proposal.createdBy.type.toLowerCase()} {proposal.createdBy.id} · {formatTime(proposal.createdAt)}
        </span>
      </div>
      <p className="mt-1 text-sm text-muted-foreground">{proposal.rationale}</p>
      <ul className="mt-1">
        {proposal.evidence.map((line) => (
          <li key={line} className="text-xs text-muted-foreground">
            · {line}
          </li>
        ))}
      </ul>
      {proposal.decisionNote && (
        <p className="mt-1 text-xs text-muted-foreground">Decision: {proposal.decisionNote}</p>
      )}
      {error && <p className="mt-2 text-sm text-destructive">{error.message}</p>}
      {pending && (
        <div className="mt-3 flex gap-2">
          <Button size="sm" onClick={() => approve.mutate({ id: proposal.id })} disabled={approve.isPending}>
            Approve and run
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => reject.mutate({ id: proposal.id })}
            disabled={reject.isPending}
          >
            Reject
          </Button>
        </div>
      )}
    </div>
  )
}

function describe(proposal: Proposal): string {
  switch (proposal.kind) {
    case 'REASSIGN_TASK':
      return `Give task ${proposal.payload.task} to ${proposal.payload.worker}`
    default:
      return proposal.kind
  }
}
