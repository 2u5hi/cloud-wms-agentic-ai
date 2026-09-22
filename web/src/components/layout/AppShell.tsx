import { Boxes, LayoutDashboard, Warehouse } from 'lucide-react'
import { NavLink, Outlet, useMatches } from 'react-router'

import { ApiStatus } from '@/components/layout/ApiStatus'
import { cn } from '@/lib/utils'

// Only pages that exist are listed here; entries are added as features are built.
const navigation = [
  { label: 'Dashboard', to: '/', icon: LayoutDashboard, end: true },
  { label: 'Inventory', to: '/inventory', icon: Boxes, end: false },
]

/** Routes declare their header title as `handle: { title }`. */
type RouteHandle = { title?: string }

export function AppShell() {
  const title = useMatches()
    .map((match) => (match.handle as RouteHandle | undefined)?.title)
    .filter(Boolean)
    .at(-1)

  return (
    <div className="flex min-h-svh bg-background text-foreground">
      <aside className="hidden w-56 shrink-0 flex-col border-r border-border md:flex">
        <div className="flex h-14 items-center gap-2 border-b border-border px-4 text-sm font-semibold">
          <Warehouse className="size-4" aria-hidden />
          WMS Console
        </div>
        <nav className="flex flex-col gap-1 p-2" aria-label="Main">
          {navigation.map(({ label, to, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-muted-foreground hover:bg-accent/50 hover:text-foreground',
                  isActive && 'bg-accent text-accent-foreground',
                )
              }
            >
              <Icon className="size-4" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center justify-between border-b border-border px-6">
          <h1 className="text-sm font-medium">{title}</h1>
          <ApiStatus />
        </header>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
