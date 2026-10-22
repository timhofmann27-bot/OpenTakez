import { JobRow } from '../components/JobRow'
import { useT } from '../i18n'
import { useJobs } from '../lib/jobs'

export function Downloads() {
  const t = useT()
  const jobs = useJobs()

  if (!jobs.length) return <p className="pt-2 text-sm text-muted">{t('downloads.empty')}</p>
  return (
    <ul className="flex flex-col gap-2.5">
      {jobs.map((view) => (
        <JobRow key={view.job.id} view={view} />
      ))}
    </ul>
  )
}
