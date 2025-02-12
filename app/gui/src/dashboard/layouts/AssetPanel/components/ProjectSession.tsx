/** @file Displays information describing a specific version of an asset. */
import LogsIcon from '#/assets/logs.svg'
import { Button, DialogTrigger } from '#/components/AriaComponents'
import ProjectLogsModal from '#/modals/ProjectLogsModal'
import { useText } from '#/providers/TextProvider'
import type Backend from '#/services/Backend'
import type { ProjectSession as BackendProjectSession, ProjectAsset } from '#/services/Backend'
import { formatDateTime } from 'enso-common/src/utilities/data/dateTime'

/** Props for a {@link ProjectSession}. */
export interface ProjectSessionProps {
  readonly backend: Backend
  readonly project: ProjectAsset
  readonly projectSession: BackendProjectSession
  readonly index: number
}

/** Displays information describing a specific version of an asset. */
export function ProjectSession(props: ProjectSessionProps) {
  const { backend, project, projectSession, index } = props

  const { getText } = useText()

  return (
    <div className="flex flex-row gap-4 rounded-2xl p-2">
      <div className="flex flex-1 flex-col">
        {getText('projectSessionX', index)}
        <time className="text-xs">
          {getText('onDateX', formatDateTime(new Date(projectSession.createdAt)))}
        </time>
      </div>
      <div className="flex items-center gap-1">
        <DialogTrigger>
          <Button variant="icon" isActive icon={LogsIcon} aria-label={getText('showLogs')} />

          <ProjectLogsModal
            backend={backend}
            projectSessionId={projectSession.projectSessionId}
            projectTitle={project.title}
          />
        </DialogTrigger>
      </div>
    </div>
  )
}
