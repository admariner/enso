/** @file A context menu available everywhere in the directory. */
import { useStore } from 'zustand'

import ContextMenu from '#/components/ContextMenu'
import ContextMenuEntry from '#/components/ContextMenuEntry'

import UpsertDatalinkModal from '#/modals/UpsertDatalinkModal'
import UpsertSecretModal from '#/modals/UpsertSecretModal'

import {
  useNewDatalink,
  useNewFolder,
  useNewProject,
  useNewSecret,
  useUploadFiles,
} from '#/hooks/backendHooks'
import { useEventCallback } from '#/hooks/eventCallbackHooks'
import type { Category } from '#/layouts/CategorySwitcher/Category'
import { useDriveStore } from '#/providers/DriveProvider'
import { useSetModal } from '#/providers/ModalProvider'
import { useText } from '#/providers/TextProvider'
import type Backend from '#/services/Backend'
import { BackendType, type DirectoryId } from '#/services/Backend'
import { inputFiles } from '#/utilities/input'

/** Props for a {@link GlobalContextMenu}. */
export interface GlobalContextMenuProps {
  readonly hidden?: boolean
  readonly backend: Backend
  readonly category: Category
  readonly rootDirectoryId: DirectoryId
  readonly directoryKey: DirectoryId | null
  readonly directoryId: DirectoryId | null
  readonly path: string | null
  readonly doPaste: (newParentKey: DirectoryId, newParentId: DirectoryId) => void
}

/** A context menu available everywhere in the directory. */
export const GlobalContextMenu = function GlobalContextMenu(props: GlobalContextMenuProps) {
  // For some reason, applying the ReactCompiler for this component breaks the copy-paste functionality
  // eslint-disable-next-line react-compiler/react-compiler
  'use no memo'

  const {
    hidden = false,
    backend,
    category,
    directoryKey = null,
    directoryId = null,
    path,
    rootDirectoryId,
  } = props
  const { doPaste } = props

  const { getText } = useText()
  const { setModal, unsetModal } = useSetModal()
  const isCloud = backend.type === BackendType.remote

  const driveStore = useDriveStore()
  const hasPasteData = useStore(
    driveStore,
    (storeState) => (storeState.pasteData?.data.ids.size ?? 0) > 0,
  )

  const newFolderRaw = useNewFolder(backend, category)
  const newFolder = useEventCallback(async () => {
    return await newFolderRaw(directoryId ?? rootDirectoryId, path)
  })
  const newSecretRaw = useNewSecret(backend, category)
  const newSecret = useEventCallback(async (name: string, value: string) => {
    return await newSecretRaw(name, value, directoryId ?? rootDirectoryId, path)
  })
  const newProjectRaw = useNewProject(backend, category)
  const newProject = useEventCallback(
    async (templateId: string | null | undefined, templateName: string | null | undefined) => {
      return await newProjectRaw({ templateName, templateId }, directoryId ?? rootDirectoryId, path)
    },
  )
  const newDatalinkRaw = useNewDatalink(backend, category)
  const newDatalink = useEventCallback(async (name: string, value: unknown) => {
    return await newDatalinkRaw(name, value, directoryId ?? rootDirectoryId, path)
  })
  const uploadFilesRaw = useUploadFiles(backend, category)
  const uploadFiles = useEventCallback(async (files: readonly File[]) => {
    await uploadFilesRaw(files, directoryId ?? rootDirectoryId, path)
  })

  return (
    <ContextMenu aria-label={getText('globalContextMenuLabel')} hidden={hidden}>
      <ContextMenuEntry
        hidden={hidden}
        action="uploadFiles"
        doAction={async () => {
          const files = await inputFiles()
          await uploadFiles(Array.from(files))
        }}
      />
      <ContextMenuEntry
        hidden={hidden}
        action="newProject"
        doAction={() => {
          unsetModal()
          void newProject(null, null)
        }}
      />
      <ContextMenuEntry
        hidden={hidden}
        action="newFolder"
        doAction={() => {
          unsetModal()
          void newFolder()
        }}
      />
      {isCloud && (
        <ContextMenuEntry
          hidden={hidden}
          action="newSecret"
          doAction={() => {
            setModal(
              <UpsertSecretModal
                id={null}
                name={null}
                doCreate={async (name, value) => {
                  await newSecret(name, value)
                }}
              />,
            )
          }}
        />
      )}
      {isCloud && (
        <ContextMenuEntry
          hidden={hidden}
          action="newDatalink"
          doAction={() => {
            setModal(
              <UpsertDatalinkModal
                doCreate={async (name, value) => {
                  await newDatalink(name, value)
                }}
              />,
            )
          }}
        />
      )}
      {isCloud && directoryKey == null && hasPasteData && (
        <ContextMenuEntry
          hidden={hidden}
          action="paste"
          doAction={() => {
            unsetModal()
            doPaste(rootDirectoryId, rootDirectoryId)
          }}
        />
      )}
    </ContextMenu>
  )
}
