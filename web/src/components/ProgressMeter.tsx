import type { Progress } from '../lib/types'

type Props = { progress: Progress | null; label: string }

/** Segmented bar; switches to a moving stripe while the server post-processes (ffmpeg). */
export function ProgressMeter({ progress, label }: Props) {
  const indeterminate = !progress || progress.phase === 'processing' || progress.fraction == null
  const percent = indeterminate ? undefined : Math.round((progress.fraction ?? 0) * 100)

  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className="relative h-1.5 overflow-hidden rounded-full bg-surface-2 ring-1 ring-line ring-inset"
    >
      {indeterminate ? (
        <div className="absolute inset-0 bg-[repeating-linear-gradient(-45deg,var(--accent)_0_8px,transparent_8px_16px)] motion-safe:animate-[processing-stripe_0.8s_linear_infinite]" />
      ) : (
        <div
          className="h-full rounded-full bg-accent shadow-[0_0_10px_var(--accent)] motion-safe:transition-[width] motion-safe:duration-300 ease-out [mask-image:repeating-linear-gradient(90deg,#000_0_10px,transparent_10px_12px)]"
          style={{ width: `${percent}%` }}
        />
      )}
    </div>
  )
}
