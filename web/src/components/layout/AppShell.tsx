import type { ReactNode } from 'react'
import { LayoutDashboard, Warehouse } from 'lucide-react'

import { ApiStatus } from '@/components/layout/ApiStatus'

// Only pages that exist are listed here; entries are added as features are built.
const navigation = [{ label: 'Dashboard', href: '/', icon: LayoutDashboard }]

type AppShellProps = {
  title: string
  children: ReactNode
}

export function AppShell({ title, children }: AppShellProps) {
  return (
    <div className="flex min-h-svh bg-background text-foreground">
      <aside className="hidden w-56 shrink-0 flex-col border-r border-border md:flex">
        <div className="flex h-14 items-center gap-2 border-b border-border px-4 text-sm font-semibold">
          <Warehouse className="size-4" aria-hidden />
          WMS Console
        </div>
        <nav className="flex flex-col gap-1 p-2" aria-label="Main">
          {navigation.map(({ label, href, icon: Icon }) => (
            <a
              key={href}
              href={href}
              aria-current="page"
              className="flex items-center gap-2 rounded-md bg-accent px-3 py-2 text-sm font-medium text-accent-foreground"
            >
              <Icon className="size-4" aria-hidden />
              {label}
            </a>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center justify-between border-b border-border px-6">
          <h1 className="text-sm font-medium">{title}</h1>
          <ApiStatus />
        </header>
        <main className="flex-1 p-6">{children}</main>
      </div>
    </div>
  )
}
