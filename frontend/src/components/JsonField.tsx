import { useEffect, useState } from 'react'
import { cx } from '../lib/format'

/** A JSON text area that only reports a value when the text parses. Parse errors are shown inline. */
export function JsonField({ label, value, onChange, rows = 6, hint, id }: {
  label: string; value: unknown; onChange: (v: unknown) => void; rows?: number; hint?: string; id?: string
}) {
  const [text, setText] = useState(() => format(value))
  const [error, setError] = useState<string>()
  useEffect(() => {
    // Re-sync when the value changes from outside (e.g. another record was selected).
    setText((current) => {
      try {
        if (JSON.stringify(JSON.parse(current)) === JSON.stringify(value ?? {})) return current
      } catch {
        /* keep reformatting below */
      }
      return format(value)
    })
    setError(undefined)
  }, [value])
  const fieldId = id ?? 'json-' + label.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  return (
    <div className="text-sm">
      <label htmlFor={fieldId} className="mb-1 block font-medium text-slate-700">{label}</label>
      <textarea
        id={fieldId}
        rows={rows}
        spellCheck={false}
        value={text}
        aria-invalid={!!error}
        onChange={(e) => {
          setText(e.target.value)
          try {
            const parsed = e.target.value.trim() === '' ? {} : JSON.parse(e.target.value)
            setError(undefined)
            onChange(parsed)
          } catch (err) {
            setError((err as Error).message)
          }
        }}
        className={cx('w-full rounded-lg border bg-slate-900 px-3 py-2 font-mono text-xs text-slate-100 shadow-sm focus:outline-none focus:ring-2',
          error ? 'border-rose-400 focus:ring-rose-200' : 'border-slate-300 focus:ring-brand-100')}
      />
      {error ? <span className="mt-1 block text-xs text-rose-600">Not valid JSON: {error}</span>
        : hint ? <span className="mt-1 block text-xs text-slate-500">{hint}</span> : null}
    </div>
  )
}

function format(v: unknown) {
  return JSON.stringify(v ?? {}, null, 2)
}
