/**
 * @file
 * A dialog is an overlay shown above other content in an application.
 * Can be used to display alerts, confirmations, or other content.
 */
import * as React from 'react'

import * as aria from '#/components/aria'
import * as errorBoundary from '#/components/ErrorBoundary'
import * as portal from '#/components/Portal'
import * as suspense from '#/components/Suspense'

import * as twv from '#/utilities/tailwindVariants'

import { useEventCallback } from '#/hooks/eventCallbackHooks'
import * as dialogProvider from './DialogProvider'
import * as dialogStackProvider from './DialogStackProvider'
import * as utlities from './utilities'
import * as variants from './variants'

/** Props for a {@link Popover}. */
export interface PopoverProps
  extends Omit<aria.PopoverProps, 'children'>,
    twv.VariantProps<typeof POPOVER_STYLES> {
  readonly children:
    | React.ReactNode
    | ((opts: aria.PopoverRenderProps & { readonly close: () => void }) => React.ReactNode)
  readonly isDismissable?: boolean
}

export const POPOVER_STYLES = twv.tv({
  base: 'shadow-xl w-full overflow-clip z-tooltip',
  variants: {
    isEntering: {
      true: 'animate-in fade-in placement-bottom:slide-in-from-top-1 placement-top:slide-in-from-bottom-1 placement-left:slide-in-from-right-1 placement-right:slide-in-from-left-1 ease-out duration-200',
    },
    isExiting: {
      true: 'animate-out fade-out placement-bottom:slide-out-to-top-1 placement-top:slide-out-to-bottom-1 placement-left:slide-out-to-right-1 placement-right:slide-out-to-left-1 ease-in duration-150',
    },
    size: {
      auto: { base: 'w-[unset]', dialog: 'p-2.5' },
      xxsmall: { base: 'max-w-[206px]', dialog: 'p-2' },
      xsmall: { base: 'max-w-xs', dialog: 'p-2.5' },
      small: { base: 'max-w-sm', dialog: 'p-3.5' },
      medium: { base: 'max-w-md', dialog: 'p-3.5' },
      large: { base: 'max-w-lg', dialog: 'px-4 py-4' },
      hero: { base: 'max-w-xl', dialog: 'px-6 py-5' },
    },
    rounded: {
      none: { base: 'rounded-none', dialog: 'rounded-none' },
      small: { base: 'rounded-sm', dialog: 'rounded-sm scroll-offset-edge-md' },
      medium: { base: 'rounded-md', dialog: 'rounded-md scroll-offset-edge-xl' },
      large: { base: 'rounded-lg', dialog: 'rounded-lg scroll-offset-edge-xl' },
      xlarge: { base: 'rounded-xl', dialog: 'rounded-xl scroll-offset-edge-xl' },
      xxlarge: { base: 'rounded-2xl', dialog: 'rounded-2xl scroll-offset-edge-2xl' },
      xxxlarge: { base: 'rounded-3xl', dialog: 'rounded-3xl scroll-offset-edge-3xl' },
      xxxxlarge: { base: 'rounded-4xl', dialog: 'rounded-4xl scroll-offset-edge-4xl' },
    },
  },
  slots: {
    dialog: variants.DIALOG_BACKGROUND({ class: 'flex-auto overflow-y-auto max-h-[inherit]' }),
  },
  defaultVariants: { rounded: 'xxxlarge', size: 'small' },
})

const SUSPENSE_LOADER_PROPS = { minHeight: 'h32' } as const

/**
 * A popover is an overlay element positioned relative to a trigger.
 * It can be used to display additional content or actions.*
 */
export function Popover(props: PopoverProps) {
  const {
    children,
    className,
    size,
    rounded,
    placement = 'bottom start',
    isDismissable = true,
    ...ariaPopoverProps
  } = props

  const popoverRef = React.useRef<HTMLDivElement>(null)
  const root = portal.useStrictPortalContext()
  const popoverStyle = { zIndex: '' }

  return (
    <aria.Popover
      ref={popoverRef}
      className={(values) =>
        POPOVER_STYLES({
          isEntering: values.isEntering,
          isExiting: values.isExiting,
          size,
          rounded,
          className: typeof className === 'function' ? className(values) : className,
        }).base()
      }
      UNSTABLE_portalContainer={root}
      placement={placement}
      style={popoverStyle}
      shouldCloseOnInteractOutside={() => false}
      {...ariaPopoverProps}
    >
      {(opts) => (
        <PopoverContent
          popoverRef={popoverRef}
          size={size}
          rounded={rounded}
          opts={opts}
          isDismissable={isDismissable}
        >
          {children}
        </PopoverContent>
      )}
    </aria.Popover>
  )
}

/**
 * Props for a {@link PopoverContent}.
 */
interface PopoverContentProps {
  readonly children: PopoverProps['children']
  readonly size: PopoverProps['size']
  readonly rounded: PopoverProps['rounded']
  readonly opts: aria.PopoverRenderProps
  readonly popoverRef: React.RefObject<HTMLDivElement>
  readonly isDismissable: boolean
}

/**
 * The content of a popover.
 */
function PopoverContent(props: PopoverContentProps) {
  const { children, size, rounded, opts, isDismissable, popoverRef } = props

  const dialogRef = React.useRef<HTMLDivElement>(null)
  const dialogId = aria.useId()

  // We use as here to make the types more accurate
  // eslint-disable-next-line no-restricted-syntax
  const contextState = React.useContext(
    aria.OverlayTriggerStateContext,
  ) as aria.OverlayTriggerState | null

  const dialogContext = React.useContext(aria.DialogContext)

  // This is safe, because the labelledBy provided by DialogTrigger is always
  // passed to the DialogContext, and we check for undefined below.
  // eslint-disable-next-line no-restricted-syntax
  const labelledBy = (dialogContext as aria.DialogProps | undefined)?.['aria-labelledby']

  const close = useEventCallback(() => {
    contextState?.close()
  })

  utlities.useInteractOutside({
    ref: dialogRef,
    id: dialogId,
    onInteractOutside: useEventCallback(() => {
      if (isDismissable) {
        close()
      } else {
        if (popoverRef.current) {
          // eslint-disable-next-line @typescript-eslint/no-magic-numbers
          utlities.animateScale(popoverRef.current, 1.025)
        }
      }
    }),
  })

  return (
    <>
      <dialogStackProvider.DialogStackRegistrar id={dialogId} type="popover" />
      <div
        id={dialogId}
        ref={dialogRef}
        role="dialog"
        aria-labelledby={labelledBy}
        tabIndex={-1}
        className={POPOVER_STYLES({ ...opts, size, rounded }).dialog()}
      >
        <dialogProvider.DialogProvider dialogId={dialogId} close={close}>
          <errorBoundary.ErrorBoundary>
            <suspense.Suspense loaderProps={SUSPENSE_LOADER_PROPS}>
              {typeof children === 'function' ? children({ ...opts, close }) : children}
            </suspense.Suspense>
          </errorBoundary.ErrorBoundary>
        </dialogProvider.DialogProvider>
      </div>
    </>
  )
}
