import { createBrowserRouter, RouterProvider } from 'react-router'

import { AppShell } from '@/components/layout/AppShell'
import { DashboardPage } from '@/pages/DashboardPage'
import { InventoryPage } from '@/pages/InventoryPage'
import { OrdersPage } from '@/pages/OrdersPage'
import { TasksPage } from '@/pages/TasksPage'
import { WaveDetailPage } from '@/pages/WaveDetailPage'
import { WavesPage } from '@/pages/WavesPage'

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <DashboardPage />, handle: { title: 'Dashboard' } },
      { path: 'orders', element: <OrdersPage />, handle: { title: 'Orders' } },
      { path: 'waves', element: <WavesPage />, handle: { title: 'Waves' } },
      { path: 'waves/:number', element: <WaveDetailPage />, handle: { title: 'Wave' } },
      { path: 'tasks', element: <TasksPage />, handle: { title: 'Tasks' } },
      { path: 'inventory', element: <InventoryPage />, handle: { title: 'Inventory' } },
    ],
  },
])

function App() {
  return <RouterProvider router={router} />
}

export default App
