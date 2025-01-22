/** @file A horizontal selector supporting multiple input. */
import { useRef, type CSSProperties, type ForwardedRef, type Ref } from 'react'

import { omit, unsafeRemoveUndefined } from 'enso-common/src/utilities/data/object'

import {
  FieldError,
  ListBox,
  mergeProps,
  type ListBoxItemProps,
  type ListBoxProps,
} from '#/components/aria'
import {
  Form,
  type FieldPath,
  type FieldProps,
  type FieldStateProps,
  type FieldValues,
  type TSchema,
} from '#/components/AriaComponents'
import { mergeRefs } from '#/utilities/mergeRefs'
import { forwardRef } from '#/utilities/react'
import { tv, type VariantProps } from '#/utilities/tailwindVariants'
import { MultiSelectorOption } from './MultiSelectorOption'

/** * Props for the MultiSelector component. */
export interface MultiSelectorProps<
  Schema extends TSchema,
  TFieldName extends FieldPath<Schema, readonly T[]>,
  T,
> extends FieldStateProps<
      Omit<ListBoxItemProps, 'children' | 'value'> & { value: FieldValues<Schema>[TFieldName] },
      Schema,
      TFieldName,
      readonly T[]
    >,
    FieldProps,
    Omit<VariantProps<typeof MULTI_SELECTOR_STYLES>, 'disabled' | 'invalid'> {
  readonly items: readonly T[]
  readonly children?: (item: T) => string
  readonly columns?: number
  readonly className?: string
  readonly style?: CSSProperties
  readonly inputRef?: Ref<HTMLDivElement>
  readonly placeholder?: string
}

export const MULTI_SELECTOR_STYLES = tv({
  base: 'block w-full bg-transparent transition-[border-color,outline] duration-200',
  variants: {
    disabled: {
      true: { base: 'cursor-default opacity-50', textArea: 'cursor-default' },
      false: { base: 'cursor-text', textArea: 'cursor-text' },
    },
    readOnly: { true: 'cursor-default' },
    size: {
      medium: { base: '' },
    },
    rounded: {
      none: 'rounded-none',
      small: 'rounded-sm',
      medium: 'rounded-md',
      large: 'rounded-lg',
      xlarge: 'rounded-xl',
      xxlarge: 'rounded-2xl',
      xxxlarge: 'rounded-3xl',
      full: 'rounded-full',
    },
    variant: {
      outline: {
        base: 'border-[0.5px] border-primary/20',
      },
    },
  },
  defaultVariants: {
    size: 'medium',
    rounded: 'xxlarge',
    variant: 'outline',
  },
  slots: {
    listBox: 'grid',
  },
})

// This is a function, even though it does not contain function syntax.
// eslint-disable-next-line no-restricted-syntax, @typescript-eslint/no-explicit-any
const useReadonlyArrayField = Form.makeUseField<readonly any[]>()

/** A horizontal multi-selector. */
export const MultiSelector = forwardRef(function MultiSelector<
  Schema extends TSchema,
  TFieldName extends FieldPath<Schema, readonly T[]>,
  T,
>(props: MultiSelectorProps<Schema, TFieldName, T>, ref: ForwardedRef<HTMLDivElement>) {
  const {
    name,
    items,
    children = String,
    isDisabled = false,
    columns,
    form,
    defaultValue,
    inputRef,
    label,
    size,
    rounded,
    isRequired = false,
    ...inputProps
  } = props

  const privateInputRef = useRef<HTMLDivElement>(null)

  // eslint-disable-next-line no-restricted-syntax
  const { fieldState, formInstance } = useReadonlyArrayField({
    name,
    isDisabled,
    form,
    defaultValue,
  }) as unknown as ReturnType<ReturnType<typeof Form.makeUseField<readonly T[]>>>

  const classes = MULTI_SELECTOR_STYLES({
    size,
    rounded,
    readOnly: inputProps.readOnly,
    disabled: isDisabled || formInstance.formState.isSubmitting,
  })

  return (
    <Form.Field
      form={formInstance}
      name={name}
      fullWidth
      label={label}
      aria-label={props['aria-label']}
      aria-labelledby={props['aria-labelledby']}
      aria-describedby={props['aria-describedby']}
      isRequired={isRequired}
      isInvalid={fieldState.invalid}
      aria-details={props['aria-details']}
      ref={ref}
      style={props.style}
      className={props.className}
    >
      <div
        className={classes.base()}
        onClick={() => privateInputRef.current?.focus({ preventScroll: true })}
      >
        <Form.Controller
          control={formInstance.control}
          name={name}
          render={(renderProps) => {
            const { ref: fieldRef, value, onChange, ...field } = renderProps.field
            return (
              <ListBox
                ref={mergeRefs(inputRef, privateInputRef, fieldRef)}
                orientation="horizontal"
                selectionMode="multiple"
                {...(inputProps.id != null && { id: String(inputProps.id) })}
                {...mergeProps<ListBoxProps<FieldValues<Schema>[TFieldName]>>()(
                  {
                    className: classes.listBox(),
                    style: { gridTemplateColumns: `repeat(${columns ?? items.length}, 1fr)` },
                  },
                  unsafeRemoveUndefined(omit(inputProps, 'id')),
                  field,
                )}
                // eslint-disable-next-line no-restricted-syntax
                aria-label={props['aria-label'] ?? (typeof label === 'string' ? label : '')}
                // This is SAFE, as there is a constraint on `items` that prevents using keys
                // that do not correspond to array values.
                // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-call
                defaultSelectedKeys={value?.map((item: FieldValues<Schema>[TFieldName]) =>
                  items.indexOf(item),
                )}
                onSelectionChange={(selection) => {
                  onChange([...selection].map((key) => items[Number(key)]))
                }}
              >
                {items.map((item, i) => (
                  <MultiSelectorOption key={i} id={i} value={{ item }} label={children(item)} />
                ))}
              </ListBox>
            )
          }}
        />
      </div>
      <FieldError />
    </Form.Field>
  )
})
