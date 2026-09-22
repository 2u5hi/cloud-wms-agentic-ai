import { createBrowserRouter, RouterProvider } from 'react-router'

import { AppShell } from '@/components/layout/AppShell'
import { DashboardPage } from '@/pages/DashboardPage'
import { InventoryPage } from '@/pages/InventoryPage'

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <DashboardPage />, handle: { title: 'Dashboard' } },
      { path: 'inventory', element: <InventoryPage />, handle: { title: 'Inventory' } },
    ],
  },
])

function App() {
  return <RouterProvider router={router} />
}

export default App
