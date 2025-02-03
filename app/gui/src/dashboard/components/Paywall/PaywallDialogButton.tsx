/**
 * @file
 *
 * A button that opens a paywall dialog when clicked.
 */

import * as React from 'react'

import * as ariaComponents from '#/components/AriaComponents'

import * as components from './components'
import * as paywallDialog from './PaywallDialog'

/** Props for a {@link PaywallDialogButton}. */
export type PaywallDialogButtonProps<IconType extends string> =
  components.PaywallButtonProps<IconType> & {
    readonly dialogProps?: paywallDialog.PaywallDialogProps
    readonly dialogTriggerProps?: ariaComponents.DialogTriggerProps
  }

/** A button that opens a paywall dialog when clicked */
export function PaywallDialogButton<IconType extends string>(
  props: PaywallDialogButtonProps<IconType>,
): React.JSX.Element {
  const { feature, dialogProps, dialogTriggerProps, ...buttonProps } = props

  return (
    <ariaComponents.DialogTrigger {...dialogTriggerProps}>
      <components.PaywallButton feature={feature} {...buttonProps} />

      <paywallDialog.PaywallDialog feature={feature} {...dialogProps} />
    </ariaComponents.DialogTrigger>
  )
}
