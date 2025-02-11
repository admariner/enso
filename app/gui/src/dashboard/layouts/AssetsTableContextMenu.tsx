/**
 * @file A context menu for an `AssetsTable`, when no row is selected, or multiple rows
 * are selected.
 */
import * as React from 'react'

import { useStore } from 'zustand'

import { useDriveStore, useSelectedKeys, useSetSelectedKeys } from '#/providers/DriveProvider'

import AssetEventType from '#/events/AssetEventType'

import {
  canTransferBetweenCategories,
  type Category,
  isCloudCategory,
} from '#/layouts/CategorySwitcher/Category'
import { GlobalContextMenu } from '#/layouts/GlobalContextMenu'

import ContextMenu from '#/components/ContextMenu'
import ContextMenuEntry from '#/components/ContextMenuEntry'
import ContextMenus from '#/components/ContextMenus'

import ConfirmDeleteModal from '#/modals/ConfirmDeleteModal'

import type Backend from '#/services/Backend'
import * as backendModule from '#/services/Backend'

import { useDispatchAssetEvent } from '#/layouts/Drive/EventListProvider'
import { useFullUserSession } from '#/providers/AuthProvider'
import { useSetModal } from '#/providers/ModalProvider'
import { useText } from '#/providers/TextProvider'
import type * as assetTreeNode from '#/utilities/AssetTreeNode'
import * as permissions from '#/utilities/permissions'
import { EMPTY_SET } from '#/utilities/set'

// =================
// === Constants ===
// =================

/** Props for an {@link AssetsTableContextMenu}. */
export interface AssetsTableContextMenuProps {
  readonly hidden?: boolean
  readonly backend: Backend
  readonly category: Category
  readonly rootDirectoryId: backendModule.DirectoryId
  readonly nodeMapRef: React.MutableRefObject<
    ReadonlyMap<backendModule.AssetId, assetTreeNode.AnyAssetTreeNode>
  >
  readonly event: Pick<React.MouseEvent<Element, MouseEvent>, 'pageX' | 'pageY'>
  readonly doCopy: () => void
  readonly doCut: () => void
  readonly doPaste: (
    newParentKey: backendModule.DirectoryId,
    newParentId: backendModule.DirectoryId,
  ) => void
  readonly doDelete: (assetId: backendModule.AssetId, forever?: boolean) => Promise<void>
}

/**
 * A context menu for an `AssetsTable`, when no row is selected, or multiple rows
 * are selected.
 */
export default function AssetsTableContextMenu(props: AssetsTableContextMenuProps) {
  // eslint-disable-next-line react-compiler/react-compiler
  'use no memo'
  const { hidden = false, backend, category } = props
  const { nodeMapRef, event, rootDirectoryId } = props
  const { doCopy, doCut, doPaste, doDelete } = props

  const { user } = useFullUserSession()
  const { setModal, unsetModal } = useSetModal()
  const { getText } = useText()

  const isCloud = isCloudCategory(category)
  const dispatchAssetEvent = useDispatchAssetEvent()
  const selectedKeys = useSelectedKeys()
  const setSelectedKeys = useSetSelectedKeys()
  const driveStore = useDriveStore()

  const hasPasteData = useStore(driveStore, ({ pasteData }) => {
    const effectivePasteData =
      (
        pasteData?.data.backendType === backend.type &&
        canTransferBetweenCategories(pasteData.data.category, category, user)
      ) ?
        pasteData
      : null
    return (effectivePasteData?.data.ids.size ?? 0) > 0
  })

  const id = React.useId()

  // This works because all items are mutated, ensuring their value stays
  // up to date.
  const ownsAllSelectedAssets =
    !isCloud ||
    Array.from(selectedKeys).every(
      (key) =>
        permissions.tryFindSelfPermission(user, nodeMapRef.current.get(key)?.item.permissions)
          ?.permission === permissions.PermissionAction.own,
    )

  // This is not a React component even though it contains JSX.
  const doDeleteAll = () => {
    const deleteAll = () => {
      unsetModal()
      setSelectedKeys(EMPTY_SET)

      for (const key of selectedKeys) {
        void doDelete(key, false)
      }
    }
    if (
      isCloud &&
      [...selectedKeys].every(
        (key) => nodeMapRef.current.get(key)?.item.type !== backendModule.AssetType.directory,
      )
    ) {
      deleteAll()
    } else {
      const [firstKey] = selectedKeys
      const soleAssetName =
        firstKey != null ? nodeMapRef.current.get(firstKey)?.item.title ?? '(unknown)' : '(unknown)'
      setModal(
        <ConfirmDeleteModal
          defaultOpen
          actionText={
            selectedKeys.size === 1 ?
              getText('deleteSelectedAssetActionText', soleAssetName)
            : getText('deleteSelectedAssetsActionText', selectedKeys.size)
          }
          doDelete={deleteAll}
        />,
      )
    }
  }

  const pasteAllMenuEntry = hasPasteData && (
    <ContextMenuEntry
      hidden={hidden}
      action="paste"
      label={getText('pasteAllShortcut')}
      doAction={() => {
        const [firstKey] = selectedKeys
        const selectedNode =
          selectedKeys.size === 1 && firstKey != null ? nodeMapRef.current.get(firstKey) : null

        if (selectedNode?.type === backendModule.AssetType.directory) {
          doPaste(selectedNode.key, selectedNode.item.id)
        } else {
          doPaste(rootDirectoryId, rootDirectoryId)
        }
      }}
    />
  )

  if (category.type === 'trash') {
    return (
      selectedKeys.size !== 0 && (
        <ContextMenus key={id} hidden={hidden} event={event}>
          <ContextMenu aria-label={getText('assetsTableContextMenuLabel')} hidden={hidden}>
            <ContextMenuEntry
              hidden={hidden}
              action="undelete"
              label={getText('restoreAllFromTrashShortcut')}
              doAction={() => {
                unsetModal()
                dispatchAssetEvent({ type: AssetEventType.restore, ids: selectedKeys })
              }}
            />
            {isCloud && (
              <ContextMenuEntry
                hidden={hidden}
                action="delete"
                label={getText('deleteAllForeverShortcut')}
                doAction={() => {
                  const [firstKey] = selectedKeys
                  const soleAssetName =
                    firstKey != null ?
                      nodeMapRef.current.get(firstKey)?.item.title ?? '(unknown)'
                    : '(unknown)'
                  setModal(
                    <ConfirmDeleteModal
                      defaultOpen
                      actionText={
                        selectedKeys.size === 1 ?
                          getText('deleteSelectedAssetForeverActionText', soleAssetName)
                        : getText('deleteSelectedAssetsForeverActionText', selectedKeys.size)
                      }
                      doDelete={() => {
                        setSelectedKeys(EMPTY_SET)
                        dispatchAssetEvent({
                          type: AssetEventType.deleteForever,
                          ids: selectedKeys,
                        })
                      }}
                    />,
                  )
                }}
              />
            )}
            {pasteAllMenuEntry}
          </ContextMenu>
        </ContextMenus>
      )
    )
  } else if (category.type === 'recent') {
    return null
  } else {
    return (
      <ContextMenus key={id} hidden={hidden} event={event}>
        {(selectedKeys.size !== 0 || pasteAllMenuEntry !== false) && (
          <ContextMenu aria-label={getText('assetsTableContextMenuLabel')} hidden={hidden}>
            {selectedKeys.size !== 0 && ownsAllSelectedAssets && (
              <ContextMenuEntry
                hidden={hidden}
                action="delete"
                label={isCloud ? getText('moveAllToTrashShortcut') : getText('deleteAllShortcut')}
                doAction={doDeleteAll}
              />
            )}
            {selectedKeys.size !== 0 && isCloud && (
              <ContextMenuEntry
                hidden={hidden}
                action="copy"
                label={getText('copyAllShortcut')}
                doAction={doCopy}
              />
            )}
            {selectedKeys.size !== 0 && ownsAllSelectedAssets && (
              <ContextMenuEntry
                hidden={hidden}
                action="cut"
                label={getText('cutAllShortcut')}
                doAction={doCut}
              />
            )}
            {pasteAllMenuEntry}
          </ContextMenu>
        )}
        {(category.type !== 'cloud' ||
          user.plan == null ||
          user.plan === backendModule.Plan.solo) && (
          <GlobalContextMenu
            hidden={hidden}
            backend={backend}
            category={category}
            rootDirectoryId={rootDirectoryId}
            directoryKey={null}
            directoryId={null}
            path={null}
            doPaste={doPaste}
          />
        )}
      </ContextMenus>
    )
  }
}
