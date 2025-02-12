/** @file Settings tab for viewing and editing keyboard shortcuts. */
import * as React from 'react'

import BlankIcon from '#/assets/blank.svg'
import CrossIcon from '#/assets/cross.svg'
import Plus2Icon from '#/assets/plus2.svg'
import ReloadIcon from '#/assets/reload.svg'
import { mergeProps } from '#/components/aria'
import { Button, ButtonGroup, DialogTrigger } from '#/components/AriaComponents'
import KeyboardShortcut from '#/components/dashboard/KeyboardShortcut'
import FocusArea from '#/components/styled/FocusArea'
import SvgMask from '#/components/SvgMask'
import type { DashboardBindingKey } from '#/configurations/inputBindings'
import { useRefresh } from '#/hooks/refreshHooks'
import { useStickyTableHeaderOnScroll } from '#/hooks/scrollHooks'
import CaptureKeyboardShortcutModal from '#/modals/CaptureKeyboardShortcutModal'
import ConfirmDeleteModal from '#/modals/ConfirmDeleteModal'
import { useInputBindings } from '#/providers/InputBindingsProvider'
import { useText } from '#/providers/TextProvider'
import { unsafeEntries } from '#/utilities/object'

// ========================================
// === KeyboardShortcutsSettingsSection ===
// ========================================

/** Settings tab for viewing and editing keyboard shortcuts. */
export default function KeyboardShortcutsSettingsSection() {
  const [refresh, doRefresh] = useRefresh()
  const inputBindings = useInputBindings()
  const { getText } = useText()
  const rootRef = React.useRef<HTMLDivElement>(null)
  const bodyRef = React.useRef<HTMLTableSectionElement>(null)
  const allShortcuts = React.useMemo(() => {
    // This is REQUIRED, in order to avoid disabling the `react-hooks/exhaustive-deps` lint.
    // eslint-disable-next-line @typescript-eslint/no-unused-expressions
    refresh
    return new Set(Object.values(inputBindings.metadata).flatMap((value) => value.bindings))
  }, [inputBindings.metadata, refresh])
  const visibleBindings = React.useMemo(
    () => unsafeEntries(inputBindings.metadata).filter((kv) => kv[1].rebindable !== false),
    [inputBindings.metadata],
  )

  const { onScroll } = useStickyTableHeaderOnScroll(rootRef, bodyRef)

  return (
    <>
      <ButtonGroup className="grow-0">
        <DialogTrigger>
          <Button size="medium" variant="outline">
            {getText('resetAll')}
          </Button>
          <ConfirmDeleteModal
            actionText={getText('resetAllKeyboardShortcuts')}
            actionButtonLabel={getText('resetAll')}
            doDelete={async () => {
              for (const k in inputBindings.metadata) {
                // eslint-disable-next-line no-restricted-syntax
                inputBindings.reset(k as DashboardBindingKey)
              }
              doRefresh()
              await Promise.resolve()
            }}
          />
        </DialogTrigger>
      </ButtonGroup>
      <FocusArea direction="vertical" focusChildClass="focus-default" focusDefaultClass="">
        {(innerProps) => (
          <div
            {...mergeProps<JSX.IntrinsicElements['div']>()(innerProps, {
              ref: rootRef,
              className: 'flex-1 min-h-0 overflow-auto',
              onScroll,
            })}
          >
            <table className="table-fixed border-collapse rounded-rows">
              <thead className="sticky top-0 z-1 bg-dashboard">
                <tr className="h-row text-left text-sm font-semibold">
                  <th className="min-w-8 pl-cell-x pr-1.5">{/* Icon */}</th>
                  <th className="min-w-36 px-cell-x">{getText('name')}</th>
                  <th className="px-cell-x">{getText('shortcuts')}</th>
                  <th className="w-full min-w-64 px-cell-x">{getText('description')}</th>
                </tr>
              </thead>
              <tbody ref={bodyRef}>
                {visibleBindings.map((kv) => {
                  const [action, info] = kv
                  return (
                    <tr key={action} className="rounded-rows-child">
                      <td className="flex h-row items-center rounded-l-full bg-clip-padding pl-cell-x pr-1.5">
                        <SvgMask
                          src={info.icon ?? BlankIcon}
                          color={info.color}
                          className="size-4"
                        />
                      </td>
                      <td className="border-l-2 border-r-2 border-transparent bg-clip-padding px-cell-x">
                        {info.name}
                      </td>
                      <td className="group min-w-max border-l-2 border-r-2 border-transparent bg-clip-padding px-cell-x">
                        <FocusArea direction="horizontal">
                          {(bindingsProps) => (
                            <div {...bindingsProps}>
                              {/* I don't know why this padding is needed,
                               * given that this is a flex container. */}
                              {}
                              <div className="gap-buttons flex items-center pr-4">
                                {info.bindings.map((binding, j) => (
                                  <div key={j} className="inline-flex shrink-0 items-center gap-1">
                                    <KeyboardShortcut
                                      shortcut={binding}
                                      className="rounded-lg border-0.5 border-primary/10 px-1"
                                    />
                                    <Button
                                      variant="ghost"
                                      size="medium"
                                      aria-label={getText('removeShortcut')}
                                      tooltipPlacement="top left"
                                      icon={CrossIcon}
                                      showIconOnHover
                                      onPress={() => {
                                        inputBindings.delete(action, binding)
                                        doRefresh()
                                      }}
                                    />
                                  </div>
                                ))}
                                <div className="grow" />
                                <div className="flex shrink-0 items-center gap-1">
                                  <DialogTrigger>
                                    <Button
                                      variant="ghost"
                                      size="medium"
                                      aria-label={getText('addShortcut')}
                                      tooltipPlacement="top left"
                                      icon={Plus2Icon}
                                      showIconOnHover
                                    />
                                    <CaptureKeyboardShortcutModal
                                      description={`'${info.name}'`}
                                      existingShortcuts={allShortcuts}
                                      onSubmit={(shortcut) => {
                                        inputBindings.add(action, shortcut)
                                        doRefresh()
                                      }}
                                    />
                                  </DialogTrigger>
                                  <Button
                                    variant="ghost"
                                    size="medium"
                                    aria-label={getText('resetShortcut')}
                                    tooltipPlacement="top left"
                                    icon={ReloadIcon}
                                    showIconOnHover
                                    onPress={() => {
                                      inputBindings.reset(action)
                                      doRefresh()
                                    }}
                                  />
                                </div>
                              </div>
                            </div>
                          )}
                        </FocusArea>
                      </td>
                      <td className="cell-x rounded-r-full border-l-2 border-r-2 border-transparent bg-clip-padding">
                        {info.description}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </FocusArea>
    </>
  )
}
