import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { Spinner } from './components/ui'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { GuidedDemoPage } from './pages/GuidedDemoPage'
import { MemoryExplorerPage } from './pages/MemoryExplorerPage'
import { ConversationsPage } from './pages/ConversationsPage'
import { AgentsPage } from './pages/AgentsPage'
import { TaskBoardPage } from './pages/TaskBoardPage'
import { TenantIsolationPage } from './pages/TenantIsolationPage'
import { ConcurrencyLabPage } from './pages/ConcurrencyLabPage'
import { PlanPage } from './pages/PlanPage'
import { RunsPage } from './pages/RunsPage'
import { WorkflowStudioPage } from './pages/WorkflowStudioPage'
import { AgentStudioPage } from './pages/AgentStudioPage'
import { WorkspacePage } from './pages/WorkspacePage'

export function App() {
  const { me, ready } = useAuth()
  if (!ready) return <div className="p-8"><Spinner label="Starting" /></div>
  if (!me) return <LoginPage />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="plan" element={<PlanPage />} />
        <Route path="runs" element={<RunsPage />} />
        <Route path="runs/:runId" element={<RunsPage />} />
        <Route path="workflows" element={<WorkflowStudioPage />} />
        <Route path="workflows/:workflowId" element={<WorkflowStudioPage />} />
        <Route path="agent-studio" element={<AgentStudioPage />} />
        <Route path="workspace" element={<WorkspacePage />} />
        <Route path="demo" element={<GuidedDemoPage />} />
        <Route path="memories" element={<MemoryExplorerPage />} />
        <Route path="conversations" element={<ConversationsPage />} />
        <Route path="agents" element={<AgentsPage />} />
        <Route path="tasks" element={<TaskBoardPage />} />
        <Route path="isolation" element={<TenantIsolationPage />} />
        <Route path="lab" element={<ConcurrencyLabPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
