import { useCallback, useEffect, useRef, useState } from 'react'

export interface AsyncState<T> {
  data: T | undefined
  error: unknown
  loading: boolean
  reload: () => Promise<void>
  setData: (value: T) => void
}

/** Loads data from the API, re-running when deps change. Keeps the previous data while reloading. */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<unknown>()
  const [loading, setLoading] = useState(true)
  const loaderRef = useRef(loader)
  loaderRef.current = loader
  const generation = useRef(0)

  const reload = useCallback(async () => {
    const current = ++generation.current
    setLoading(true)
    try {
      const value = await loaderRef.current()
      if (current === generation.current) {
        setData(value)
        setError(undefined)
      }
    } catch (e) {
      if (current === generation.current) setError(e)
    } finally {
      if (current === generation.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return { data, error, loading, reload, setData }
}

/** Calls fn every intervalMs while the page is visible. */
export function useInterval(fn: () => void, intervalMs: number | null) {
  const ref = useRef(fn)
  ref.current = fn
  useEffect(() => {
    if (intervalMs === null) return
    const id = setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') ref.current()
    }, intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])
}
