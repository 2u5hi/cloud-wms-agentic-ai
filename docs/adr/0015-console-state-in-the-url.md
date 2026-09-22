# 0015. Console filters live in the URL; plain tables until client-side features are needed

**Status:** accepted

## Context
Operations staff share views ("look at zone D forward-pick") and use the browser's Back button. Filter state held only in React state is lost on reload and can't be linked to.

## Decision
- Inventory filters are URL search parameters. A filter change adds a history entry. Typing a SKU adds an entry when the filter **starts** and replaces it while the filter is **refined**, so there isn't one entry per keystroke.

  [`web/src/features/inventory/filters.ts`](../../web/src/features/inventory/filters.ts)
  ```ts
  export function filtersFromParams(params: URLSearchParams): BalanceFilters
  export function filtersToParams(filters: BalanceFilters): URLSearchParams
  ```
  [`web/src/pages/InventoryPage.tsx`](../../web/src/pages/InventoryPage.tsx)
  ```ts
  // Starting a SKU filter adds a history entry; refining or clearing it replaces that entry.
  onChange({ ...filters, sku: next }, { replace: filters.sku !== undefined })
  ```
- Pagination cursors stay in component state as a stack (for Previous), and the stack resets when the filters change.
- Tables use shadcn's plain `Table`. The server already filters and paginates, so TanStack Table would add weight with nothing to do. It comes in when a grid needs client-side sorting or column controls.

## Consequences
- `/inventory?zone=D&type=FORWARD_PICK` is a shareable link that opens with the filters set.
- Two bugs found while testing in the browser were fixed:
  - the SKU box updated the URL during render, which React doesn't allow
  - Back skipped the unfiltered list, because the first keystroke replaced its history entry
- Known gaps: there's no navigation on phones yet (the sidebar hides), and there are no component tests yet. The logic is unit-tested, and the UI was verified in a real browser.
