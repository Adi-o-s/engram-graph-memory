import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api/endpoints'
import { setUnauthorizedHandler, tokenStore } from '../api/client'
import type { LoginResponse, Me, TenantSummary } from '../api/types'

interface AuthState {
  me: Me | null
  memberships: TenantSummary[]
  ready: boolean
  login: (email: string, password: string, tenantSlug?: string) => Promise<void>
  switchTenant: (slug: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [memberships, setMemberships] = useState<TenantSummary[]>([])
  const [ready, setReady] = useState(false)

  const logout = useCallback(() => {
    tokenStore.set(null)
    setMe(null)
    setMemberships([])
  }, [])

  const applyLogin = useCallback(async (response: LoginResponse) => {
    tokenStore.set(response.token)
    setMemberships(response.memberships)
    setMe(await api.me())
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(logout)
    const token = tokenStore.get()
    if (!token) {
      setReady(true)
      return () => setUnauthorizedHandler(null)
    }
    api
      .me()
      .then((m) => {
        setMe(m)
        setMemberships(m.memberships ?? [m.tenant])
      })
      .catch(() => tokenStore.set(null))
      .finally(() => setReady(true))
    return () => setUnauthorizedHandler(null)
  }, [logout])

  const value = useMemo<AuthState>(
    () => ({
      me,
      memberships,
      ready,
      logout,
      login: async (email, password, tenantSlug) => applyLogin(await api.login(email, password, tenantSlug)),
      switchTenant: async (slug) => applyLogin(await api.switchTenant(slug)),
    }),
    [me, memberships, ready, logout, applyLogin],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
