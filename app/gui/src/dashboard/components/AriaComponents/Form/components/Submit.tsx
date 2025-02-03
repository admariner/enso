/**
 * @file
 *
 * Submit button for forms.
 * Manages the form state and displays a loading spinner when the form is submitting.
 */
import type { JSX } from 'react'

import { Button, type ButtonProps } from '#/components/AriaComponents'
import { useText } from '#/providers/TextProvider'
import { useFormContext } from './FormProvider'
import type { FormInstance } from './types'

/** Additional props for the Submit component. */
interface SubmitButtonBaseProps<IconType extends string> {
  readonly variant?: ButtonProps<IconType>['variant']
  /**
   * Connects the submit button to a form.
   * If not provided, the button will use the nearest form context.
   *
   * This field is helpful when you need to use the submit button outside of the form.
   */
  // We do not need to know the form fields.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  readonly form?: FormInstance<any>
  readonly cancel?: boolean
}

/** Props for the Submit component. */
export type SubmitProps<IconType extends string> = Omit<
  ButtonProps<IconType>,
  'formnovalidate' | 'href' | 'variant'
> &
  SubmitButtonBaseProps<IconType>

/**
 * Submit button for forms.
 *
 * Manages the form state and displays a loading spinner when the form is submitting.
 */
export function Submit<IconType extends string>(props: SubmitProps<IconType>): JSX.Element {
  const { getText } = useText()

  const {
    size = 'medium',
    loading = false,
    children = getText('submit'),
    variant = 'submit',
    testId = 'form-submit-button',
    ...buttonProps
  } = props

  const form = useFormContext(props.form)
  const { formState } = form

  return (
    <Button
      type="submit"
      variant={variant}
      size={size}
      loading={loading || formState.isSubmitting}
      testId={testId}
      /* This is safe because we are passing all props to the button */
      /* eslint-disable-next-line @typescript-eslint/no-explicit-any,no-restricted-syntax */
      {...(buttonProps as any)}
    >
      {children}
    </Button>
  )
}
