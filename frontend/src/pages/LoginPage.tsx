import { useState } from 'react'
import { Database } from 'lucide-react'
import { api } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../lib/useAsync'
import { Button, ErrorBox, Field, Input } from '../components/ui'

export function LoginPage() {
  const { login } = useAuth()
  const accounts = useAsync(() => api.demoAccounts(), [])
  const [email, setEmail] = useState('alice@acme.test')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()

  async function submit(e?: React.FormEvent) {
    e?.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await login(email, password)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const hint = accounts.data?.passwordHint

  return (
    <div className="flex min-h-full items-center justify-center px-4 py-10">
      <div className="w-full max-w-4xl">
        <div className="mb-8 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-600 text-white">
            <Database className="h-5 w-5" aria-hidden />
          </div>
          <div>
            <h1 className="text-2xl font-semibold text-ink">Memora</h1>
            <p className="text-sm text-slate-500">Shared memory infrastructure for AI agents. Runs locally, no API keys.</p>
          </div>
        </div>
        <div className="grid gap-6 md:grid-cols-2">
          <form onSubmit={submit} className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="text-base font-semibold">Sign in</h2>
            <Field label="Email">
              <Input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </Field>
            <Field label="Password" hint={hint ? `Local demo accounts use ${hint}.` : undefined}>
              <Input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </Field>
            <ErrorBox error={error} />
            <Button type="submit" busy={busy} className="w-full">Sign in</Button>
          </form>
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="text-base font-semibold">Demo accounts</h2>
            {accounts.error ? <ErrorBox error={accounts.error} className="mt-3" /> : null}
            {accounts.data && !accounts.data.seeded && (
              <p className="mt-2 text-sm text-slate-500">Demo seeding is disabled on this server.</p>
            )}
            <ul className="mt-3 space-y-2">
              {accounts.data?.accounts.map((a) => (
                <li key={a.email}>
                  <button
                    type="button"
                    className="w-full rounded-lg border border-slate-200 p-3 text-left hover:border-brand-500"
                    onClick={() => {
                      setEmail(a.email)
                      if (hint === 'memora-demo' && !password) setPassword(hint)
                    }}
                  >
                    <div className="flex items-baseline justify-between gap-2">
                      <span className="text-sm font-medium">{a.displayName}</span>
                      <span className="font-mono text-xs text-slate-500">{a.email}</span>
                    </div>
                    <div className="mt-0.5 text-xs text-slate-500">{a.tenants.join(', ')}</div>
                    <div className="mt-0.5 text-xs text-slate-400">{a.note}</div>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </div>
  )
}
