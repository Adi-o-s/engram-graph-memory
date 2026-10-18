import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react'
import { Loader2, AlertTriangle, Inbox } from 'lucide-react'
import { ApiError } from '../api/client'
import { cx, titleCase } from '../lib/format'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

export function Button({
  variant = 'primary',
  size = 'md',
  busy,
  className,
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md'; busy?: boolean }) {
  const styles: Record<Variant, string> = {
    primary: 'bg-brand-600 text-white hover:bg-brand-700 shadow-sm',
    secondary: 'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50',
    ghost: 'text-slate-600 hover:bg-slate-100',
    danger: 'bg-rose-600 text-white hover:bg-rose-700',
  }
  return (
    <button
      type="button"
      className={cx(
        'inline-flex items-center justify-center gap-1.5 rounded-lg font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed',
        size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3.5 py-2 text-sm',
        styles[variant],
        className,
      )}
      disabled={disabled || busy}
      {...rest}
    >
      {busy && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
      {children}
    </button>
  )
}

export function Card({ title, actions, children, className, bodyClassName }: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string
}) {
  return (
    <section className={cx('rounded-xl border border-slate-200 bg-white shadow-sm', className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
          <h2 className="text-sm font-semibold text-slate-800">{title}</h2>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cx('p-4', bodyClassName)}>{children}</div>
    </section>
  )
}

const tones = {
  gray: 'bg-slate-100 text-slate-700 ring-slate-200',
  blue: 'bg-sky-50 text-sky-700 ring-sky-200',
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  amber: 'bg-amber-50 text-amber-800 ring-amber-200',
  red: 'bg-rose-50 text-rose-700 ring-rose-200',
  violet: 'bg-violet-50 text-violet-700 ring-violet-200',
  indigo: 'bg-brand-50 text-brand-700 ring-brand-100',
} as const
export type Tone = keyof typeof tones

export function Badge({ tone = 'gray', children, className }: { tone?: Tone; children: ReactNode; className?: string }) {
  return (
    <span className={cx('inline-flex items-center rounded-md px-1.5 py-0.5 text-[11px] font-medium ring-1 ring-inset whitespace-nowrap', tones[tone], className)}>
      {children}
    </span>
  )
}

const statusTones: Record<string, Tone> = {
  CURRENT: 'green', SUPERSEDED: 'amber', RETRACTED: 'red',
  TODO: 'gray', IN_PROGRESS: 'blue', BLOCKED: 'amber', IN_REVIEW: 'violet', DONE: 'green', FAILED: 'red',
  PENDING: 'gray', ACKNOWLEDGED: 'blue', COMPLETED: 'green', REJECTED: 'amber', DEAD_LETTER: 'red',
  EPISODIC: 'violet', SEMANTIC: 'indigo', PROCEDURAL: 'blue',
  CREATED: 'green', SUPERSEDED_PREVIOUS: 'amber', DUPLICATE: 'gray', REPLAYED: 'gray',
  ACTIVE: 'green', DISABLED: 'gray', INACTIVE: 'gray',
  READY: 'blue', RUNNING: 'blue', WAITING_APPROVAL: 'amber', SUCCEEDED: 'green', SKIPPED: 'gray', CANCELLED: 'gray',
  PAUSED: 'amber', APPROVED: 'green', DENIED: 'red',
}

export function StatusBadge({ value }: { value?: string | null }) {
  if (!value) return null
  return <Badge tone={statusTones[value] ?? 'gray'}>{titleCase(value)}</Badge>
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-6 text-sm text-slate-500" role="status">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> {label}…
    </div>
  )
}

export function ErrorBox({ error, className }: { error: unknown; className?: string }) {
  if (!error) return null
  const api = error instanceof ApiError ? error : null
  const message = api ? api.message : error instanceof Error ? error.message : String(error)
  return (
    <div role="alert" className={cx('flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800', className)}>
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <div>
        <div>{message}</div>
        {api && (
          <div className="mt-0.5 font-mono text-[11px] text-rose-600">
            HTTP {api.status} · {api.code}
            {api.correlationId ? ` · correlation ${api.correlationId}` : ''}
          </div>
        )}
      </div>
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 py-8 text-center text-sm text-slate-500">
      <Inbox className="h-6 w-6 text-slate-300" aria-hidden />
      {children}
    </div>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <div className="text-sm">
      <label className="block">
        <span className="mb-1 block font-medium text-slate-700">{label}</span>
        {children}
      </label>
      {hint && <span className="mt-1 block text-xs text-slate-500">{hint}</span>}
    </div>
  )
}

const inputClass = 'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100'

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputClass, props.className)} />
}
export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={cx(inputClass, props.className)} />
}
export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(inputClass, 'pr-8', props.className)} />
}

export function JsonView({ value, className }: { value: unknown; className?: string }) {
  return <pre className={cx('code-block max-h-80', className)}>{JSON.stringify(value, null, 2)}</pre>
}

export function PageHeader({ title, description, actions }: { title: string; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold text-ink">{title}</h1>
        {description && <p className="mt-1 max-w-3xl text-sm text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}

export function Stat({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'good' | 'bad' }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className={cx('mt-1 text-2xl font-semibold', tone === 'good' ? 'text-emerald-600' : tone === 'bad' ? 'text-rose-600' : 'text-ink')}>{value}</div>
      {hint && <div className="mt-0.5 text-xs text-slate-500">{hint}</div>}
    </div>
  )
}

export function Mono({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cx('font-mono text-[12px] text-slate-600', className)}>{children}</span>
}
