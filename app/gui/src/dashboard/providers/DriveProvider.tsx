/** @file The React provider (and associated hooks) for Data Catalog state. */
import * as React from 'react'

import * as zustand from '#/utilities/zustand'
import invariant from 'tiny-invariant'

import { useEventCallback } from '#/hooks/eventCallbackHooks'
import type { Category } from '#/layouts/CategorySwitcher/Category'
import type AssetTreeNode from '#/utilities/AssetTreeNode'
import type { PasteData } from '#/utilities/pasteData'
import { EMPTY_SET } from '#/utilities/set'
import type {
  AssetId,
  BackendType,
  DirectoryAsset,
  DirectoryId,
} from 'enso-common/src/services/Backend'
import { EMPTY_ARRAY } from 'enso-common/src/utilities/data/array'

// ==================
// === DriveStore ===
// ==================

/** Attached data for a paste payload. */
export interface DrivePastePayload {
  readonly backendType: BackendType
  readonly category: Category
  readonly ids: ReadonlySet<AssetId>
}

/** The state of this zustand store. */
interface DriveStore {
  readonly category: Category
  readonly setCategory: (category: Category) => void
  readonly targetDirectory: AssetTreeNode<DirectoryAsset> | null
  readonly setTargetDirectory: (targetDirectory: AssetTreeNode<DirectoryAsset> | null) => void
  readonly newestFolderId: DirectoryId | null
  readonly setNewestFolderId: (newestFolderId: DirectoryId | null) => void
  readonly canCreateAssets: boolean
  readonly setCanCreateAssets: (canCreateAssets: boolean) => void
  readonly canDownload: boolean
  readonly setCanDownload: (canDownload: boolean) => void
  readonly pasteData: PasteData<DrivePastePayload> | null
  readonly setPasteData: (pasteData: PasteData<DrivePastePayload> | null) => void
  readonly expandedDirectoryIds: readonly DirectoryId[]
  readonly setExpandedDirectoryIds: (selectedKeys: readonly DirectoryId[]) => void
  readonly selectedKeys: ReadonlySet<AssetId>
  readonly setSelectedKeys: (selectedKeys: ReadonlySet<AssetId>) => void
  readonly visuallySelectedKeys: ReadonlySet<AssetId> | null
  readonly setVisuallySelectedKeys: (visuallySelectedKeys: ReadonlySet<AssetId> | null) => void
}

// =======================
// === ProjectsContext ===
// =======================

/** State contained in a `ProjectsContext`. */
export type ProjectsContextType = zustand.StoreApi<DriveStore>

const DriveContext = React.createContext<ProjectsContextType | null>(null)

/** Props for a {@link DriveProvider}. */
export type ProjectsProviderProps = Readonly<React.PropsWithChildren>

// ========================
// === ProjectsProvider ===
// ========================

/**
 * A React provider (and associated hooks) for determining whether the current area
 * containing the current element is focused.
 */
export default function DriveProvider(props: ProjectsProviderProps) {
  const { children } = props

  const [store] = React.useState(() =>
    zustand.createStore<DriveStore>((set, get) => ({
      category: { type: 'cloud' },
      setCategory: (category) => {
        if (get().category !== category) {
          set({
            category,
            targetDirectory: null,
            selectedKeys: EMPTY_SET,
            visuallySelectedKeys: null,
            expandedDirectoryIds: EMPTY_ARRAY,
          })
        }
      },
      targetDirectory: null,
      setTargetDirectory: (targetDirectory) => {
        if (get().targetDirectory !== targetDirectory) {
          set({ targetDirectory })
        }
      },
      newestFolderId: null,
      setNewestFolderId: (newestFolderId) => {
        if (get().newestFolderId !== newestFolderId) {
          set({ newestFolderId })
        }
      },
      canCreateAssets: true,
      setCanCreateAssets: (canCreateAssets) => {
        if (get().canCreateAssets !== canCreateAssets) {
          set({ canCreateAssets })
        }
      },
      canDownload: false,
      setCanDownload: (canDownload) => {
        if (get().canDownload !== canDownload) {
          set({ canDownload })
        }
      },
      pasteData: null,
      setPasteData: (pasteData) => {
        if (get().pasteData !== pasteData) {
          set({ pasteData })
        }
      },
      expandedDirectoryIds: EMPTY_ARRAY,
      setExpandedDirectoryIds: (expandedDirectoryIds) => {
        if (get().expandedDirectoryIds !== expandedDirectoryIds) {
          set({ expandedDirectoryIds })
        }
      },
      selectedKeys: EMPTY_SET,
      setSelectedKeys: (selectedKeys) => {
        if (get().selectedKeys !== selectedKeys) {
          set({ selectedKeys })
        }
      },
      visuallySelectedKeys: null,
      setVisuallySelectedKeys: (visuallySelectedKeys) => {
        if (get().visuallySelectedKeys !== visuallySelectedKeys) {
          set({ visuallySelectedKeys })
        }
      },
    })),
  )

  return <DriveContext.Provider value={store}>{children}</DriveContext.Provider>
}

/** The drive store. */
export function useDriveStore() {
  const store = React.useContext(DriveContext)

  invariant(store, 'Drive store can only be used inside an `DriveProvider`.')

  return store
}

/** The category of the Asset Table. */
export function useCategory() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.category, { unsafeEnableTransition: true })
}

/** A function to set the category of the Asset Table. */
export function useSetCategory() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setCategory, { unsafeEnableTransition: true })
}

/** The target directory of the Asset Table selection. */
export function useTargetDirectory() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.targetDirectory)
}

/** A function to set the target directory of the Asset Table selection. */
export function useSetTargetDirectory() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setTargetDirectory)
}

/** The ID of the most newly created folder. */
export function useNewestFolderId() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.newestFolderId)
}

/** A function to set the ID of the most newly created folder. */
export function useSetNewestFolderId() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setNewestFolderId)
}

/** Whether assets can be created in the current directory. */
export function useCanCreateAssets() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.canCreateAssets)
}

/** A function to set whether assets can be created in the current directory. */
export function useSetCanCreateAssets() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setCanCreateAssets)
}

/** Whether the current Asset Table selection is downloadble. */
export function useCanDownload() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.canDownload)
}

/** A function to set whether the current Asset Table selection is downloadble. */
export function useSetCanDownload() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setCanDownload)
}

/** The paste data for the Asset Table. */
export function usePasteData() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.pasteData)
}

/** A function to set the paste data for the Asset Table. */
export function useSetPasteData() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setPasteData)
}

/** The expanded directories in the Asset Table. */
export function useExpandedDirectoryIds() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.expandedDirectoryIds)
}

/** A function to set the expanded directoyIds in the Asset Table. */
export function useSetExpandedDirectoryIds() {
  const store = useDriveStore()
  const privateSetExpandedDirectoryIds = zustand.useStore(
    store,
    (state) => state.setExpandedDirectoryIds,
    { unsafeEnableTransition: true },
  )
  return useEventCallback((expandedDirectoryIds: readonly DirectoryId[]) => {
    React.startTransition(() => {
      privateSetExpandedDirectoryIds(expandedDirectoryIds)
    })
  })
}

/** The selected keys in the Asset Table. */
export function useSelectedKeys() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.selectedKeys)
}

/** A function to set the selected keys in the Asset Table. */
export function useSetSelectedKeys() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setSelectedKeys)
}

/** The visually selected keys in the Asset Table. */
export function useVisuallySelectedKeys() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.selectedKeys, {
    unsafeEnableTransition: true,
  })
}

/** A function to set the visually selected keys in the Asset Table. */
export function useSetVisuallySelectedKeys() {
  const store = useDriveStore()
  return zustand.useStore(store, (state) => state.setVisuallySelectedKeys, {
    unsafeEnableTransition: true,
  })
}

/** Toggle whether a specific directory is expanded. */
export function useToggleDirectoryExpansion() {
  const driveStore = useDriveStore()
  const setExpandedDirectoryIds = useSetExpandedDirectoryIds()

  return useEventCallback((directoryId: DirectoryId, override?: boolean) => {
    const expandedDirectoryIds = driveStore.getState().expandedDirectoryIds
    const isExpanded = expandedDirectoryIds.includes(directoryId)
    const shouldExpand = override ?? !isExpanded

    if (shouldExpand !== isExpanded) {
      React.startTransition(() => {
        if (shouldExpand) {
          setExpandedDirectoryIds([...expandedDirectoryIds, directoryId])
        } else {
          setExpandedDirectoryIds(expandedDirectoryIds.filter((id) => id !== directoryId))
        }
      })
    }
  })
}
