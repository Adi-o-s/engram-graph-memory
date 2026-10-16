import { NavLink, Outlet } from 'react-router-dom'
import {
  LayoutDashboard, PlayCircle, Brain, MessagesSquare, Bot, KanbanSquare, ShieldCheck, Gauge, LogOut, Database,
} from 'lucide-react'
import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { cx } from '../lib/format'
import { Select } from './ui'

const NAV = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/demo', label: 'Guided demo', icon: PlayCircle },
  { to: '/memories', label: 'Memory explorer', icon: Brain },
  { to: '/conversations', label: 'Conversations', icon: MessagesSquare },
  { to: '/agents', label: 'Agent workspace', icon: Bot },
  { to: '/tasks', label: 'Tasks & handoffs', icon: KanbanSquare },
  { to: '/isolation', label: 'Tenant isolation', icon: ShieldCheck },
  { to: '/lab', label: 'Concurrency lab', icon: Gauge },
]

export function Layout() {
  const { me, memberships, switchTenant, logout } = useAuth()
  const [switching, setSwitching] = useState(false)
  if (!me) return null

  return (
    <div className="flex min-h-full flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-slate-200 bg-white md:h-screen md:w-60 md:border-b-0 md:border-r md:sticky md:top-0">
        <div className="flex items-center gap-2 px-4 py-4">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-600 text-white">
            <Database className="h-4 w-4" aria-hidden />
          </div>
          <div>
            <div className="text-sm font-semibold text-ink">Memora</div>
            <div className="text-[11px] text-slate-500">Shared memory for agents</div>
          </div>
        </div>
        <nav className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-col md:overflow-visible">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cx(
                  'flex items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-sm',
                  isActive ? 'bg-brand-50 font-medium text-brand-700' : 'text-slate-600 hover:bg-slate-100',
                )
              }
            >
              <Icon className="h-4 w-4" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto hidden border-t border-slate-100 p-3 md:block">
          <div className="text-xs text-slate-500">Signed in as</div>
          <div className="truncate text-sm font-medium text-slate-800">{me.name}</div>
          <label className="mt-2 block text-xs text-slate-500">
            Tenant
            <Select
              aria-label="Tenant"
              className="mt-1 py-1.5"
              value={me.tenant.slug}
              disabled={switching || memberships.length < 2}
              onChange={async (e) => {
                setSwitching(true)
                try {
                  await switchTenant(e.target.value)
                } finally {
                  setSwitching(false)
                }
              }}
            >
              {memberships.map((t) => (
                <option key={t.slug} value={t.slug}>
                  {t.name} ({t.role.toLowerCase()})
                </option>
              ))}
            </Select>
          </label>
          <button type="button" onClick={logout} className="mt-3 flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-800">
            <LogOut className="h-3.5 w-3.5" aria-hidden /> Sign out
          </button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 px-4 py-6 md:px-8">
        <div className="mb-4 flex items-center justify-between gap-2 md:hidden">
          <span className="text-sm text-slate-600">{me.name} · {me.tenant.name}</span>
          <button type="button" onClick={logout} className="text-xs text-slate-500">Sign out</button>
        </div>
        {/* key forces pages to reload their data after a tenant switch */}
        <div key={me.tenant.id}>
          <Outlet />
        </div>
      </main>
    </div>
  )
}
