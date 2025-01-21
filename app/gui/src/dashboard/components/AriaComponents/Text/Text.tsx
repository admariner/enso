/** @file Text component */
import * as React from 'react'

import * as aria from '#/components/aria'

import * as mergeRefs from '#/utilities/mergeRefs'
import * as twv from '#/utilities/tailwindVariants'

import { useEventCallback } from '#/hooks/eventCallbackHooks'
import { forwardRef } from '#/utilities/react'
import { memo } from 'react'
import type { TestIdProps } from '../types'
import * as textProvider from './TextProvider'
import * as visualTooltip from './useVisualTooltip'

/** Props for the Text component */
export interface TextProps
  extends Omit<aria.TextProps, 'color'>,
    twv.VariantProps<typeof TEXT_STYLE>,
    TestIdProps {
  readonly elementType?: keyof HTMLElementTagNameMap
  readonly lineClamp?: number
  readonly tooltip?: React.ReactElement | string | false | null
  readonly tooltipTriggerRef?: React.RefObject<HTMLElement>
  readonly tooltipDisplay?: visualTooltip.VisualTooltipProps['display']
  readonly tooltipPlacement?: aria.Placement
  readonly tooltipOffset?: number
  readonly tooltipCrossOffset?: number
}

export const TEXT_STYLE = twv.tv({
  base: '',
  variants: {
    color: {
      custom: '',
      primary: 'text-primary',
      danger: 'text-danger',
      success: 'text-accent-dark',
      muted: 'text-primary/40',
      disabled: 'text-disabled',
      invert: 'text-invert',
      inherit: 'text-inherit',
      current: 'text-current',
    },
    font: {
      default: '',
      naming: 'font-naming',
    },
    // we use custom padding for the text variants to make sure the text is aligned with the grid
    // leading is also adjusted to make sure the text is aligned with the grid
    // leading should always be after the text size to make sure it is not stripped by twMerge
    variant: {
      custom: '',
      body: 'text-xs leading-[20px] before:h-[2px] after:h-[2px] macos:before:h-[1px] macos:after:h-[3px] font-medium',
      // eslint-disable-next-line @typescript-eslint/naming-convention
      'body-sm':
        'text-[10.5px] leading-[16px] before:h-[2px] after:h-[2px] macos:before:h-[1px] macos:after:h-[3px] font-medium',
      h1: 'text-xl leading-[29px] before:h-0.5 after:h-[5px] macos:before:h-[3px] macos:after:h-[3px] font-bold',
      subtitle:
        'text-[13.5px] leading-[19px] before:h-[2px] after:h-[2px] macos:before:h-[1px] macos:after:h-[3px] font-bold',
      caption:
        'text-[8.5px] leading-[12px] before:h-[1px] after:h-[1px] macos:before:h-[0.5px] macos:after:h-[1.5px]',
      overline:
        'text-[8.5px] leading-[16px] before:h-[1px] after:h-[1px] macos:before:h-[0.5px] macos:after:h-[1.5px] uppercase',
    },
    weight: {
      custom: '',
      bold: 'font-bold',
      semibold: 'font-semibold',
      extraBold: 'font-extrabold',
      medium: 'font-medium',
      normal: 'font-normal',
      thin: 'font-thin',
    },
    balance: {
      true: 'text-balance',
    },
    transform: {
      none: '',
      normal: 'normal-case',
      capitalize: 'capitalize',
      lowercase: 'lowercase',
      uppercase: 'uppercase',
    },
    truncate: {
      /* eslint-disable @typescript-eslint/naming-convention */
      '1': 'block truncate ellipsis',
      '2': 'line-clamp-2 ellipsis',
      '3': 'line-clamp-3 ellipsis',
      '4': 'line-clamp-4 ellipsis',
      '5': 'line-clamp-5 ellipsis',
      '6': 'line-clamp-6 ellipsis',
      '7': 'line-clamp-7 ellipsis',
      '8': 'line-clamp-8 ellipsis',
      '9': 'line-clamp-9 ellipsis',
      custom: 'line-clamp-[var(--line-clamp)] ellipsis',
      /* eslint-enable @typescript-eslint/naming-convention */
    },
    monospace: { true: 'font-mono' },
    italic: { true: 'italic' },
    nowrap: { true: 'whitespace-nowrap', normal: 'whitespace-normal', false: '' },
    textSelection: {
      auto: '',
      none: 'select-none',
      word: 'select-text',
      all: 'select-all',
    },
    disableLineHeightCompensation: {
      true: 'before:hidden after:hidden before:w-0 after:w-0',
      false:
        'flex-col before:block after:block before:flex-none after:flex-none before:w-full after:w-full',
      top: 'flex-col before:hidden before:w-0 after:block after:flex-none after:w-full',
      bottom: 'flex-col before:block before:flex-none before:w-full after:hidden after:w-0',
    },
  },
  defaultVariants: {
    variant: 'body',
    font: 'default',
    weight: 'medium',
    transform: 'none',
    color: 'primary',
    italic: false,
    nowrap: false,
    monospace: false,
    disableLineHeightCompensation: false,
    textSelection: 'auto',
  },
})

/** Text component that supports truncation and show a tooltip on hover when text is truncated */
// eslint-disable-next-line no-restricted-syntax
export const Text = memo(
  forwardRef(function Text(props: TextProps, ref: React.Ref<HTMLSpanElement>) {
    const {
      className,
      variant,
      font,
      italic,
      weight,
      nowrap,
      monospace,
      transform,
      truncate,
      lineClamp = 1,
      children,
      color,
      balance,
      testId,
      elementType: ElementType = 'span',
      tooltip: tooltipElement = children,
      tooltipDisplay = 'whenOverflowing',
      tooltipPlacement,
      tooltipOffset,
      tooltipCrossOffset,
      textSelection,
      disableLineHeightCompensation = false,
      ...ariaProps
    } = props

    const textElementRef = React.useRef<HTMLElement>(null)
    const textContext = textProvider.useTextContext()

    const textClasses = TEXT_STYLE({
      variant,
      font,
      weight,
      transform,
      monospace,
      italic,
      nowrap,
      truncate,
      color,
      balance,
      textSelection,
      disableLineHeightCompensation:
        disableLineHeightCompensation === false ?
          textContext.isInsideTextComponent
        : disableLineHeightCompensation,
      className,
    })

    const isTooltipDisabled = useEventCallback(() => {
      if (tooltipDisplay === 'whenOverflowing') {
        return !truncate
      } else if (tooltipDisplay === 'always') {
        return tooltipElement === false || tooltipElement == null
      } else {
        return false
      }
    })

    const { tooltip, targetProps } = visualTooltip.useVisualTooltip({
      isDisabled: isTooltipDisabled(),
      targetRef: textElementRef,
      display: tooltipDisplay,
      children: tooltipElement,
      ...(tooltipPlacement || tooltipOffset != null || tooltipCrossOffset != null ?
        {
          overlayPositionProps: {
            ...(tooltipPlacement && { placement: tooltipPlacement }),
            ...(tooltipOffset != null && { offset: tooltipOffset }),
            ...(tooltipCrossOffset != null && { crossOffset: tooltipCrossOffset }),
          },
        }
      : {}),
    })

    return (
      <textProvider.TextProvider value={{ isInsideTextComponent: true }}>
        <ElementType
          // @ts-expect-error This is caused by the type-safe `elementType` type.
          ref={(el) => {
            // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
            mergeRefs.mergeRefs(ref, textElementRef)(el)
          }}
          data-testid={testId}
          className={textClasses}
          {...aria.mergeProps<React.HTMLAttributes<HTMLElement>>()(
            ariaProps,
            targetProps,
            truncate === 'custom' ?
              // eslint-disable-next-line @typescript-eslint/naming-convention,no-restricted-syntax
              ({ style: { '--line-clamp': `${lineClamp}` } } as React.HTMLAttributes<HTMLElement>)
            : {},
          )}
        >
          {children}
        </ElementType>

        {tooltip}
      </textProvider.TextProvider>
    )
  }),
) as unknown as React.FC<React.RefAttributes<HTMLSpanElement> & TextProps> & {
  // eslint-disable-next-line @typescript-eslint/naming-convention
  Heading: typeof Heading
  // eslint-disable-next-line @typescript-eslint/naming-convention
  Group: React.FC<React.PropsWithChildren>
}

/** Heading props */
export interface HeadingProps extends Omit<TextProps, 'elementType'> {
  // eslint-disable-next-line @typescript-eslint/no-magic-numbers
  readonly level?: '1' | '2' | '3' | '4' | '5' | '6' | 1 | 2 | 3 | 4 | 5 | 6
}

/** Heading component */
// eslint-disable-next-line no-restricted-syntax
const Heading = memo(
  forwardRef(function Heading(props: HeadingProps, ref: React.Ref<HTMLHeadingElement>) {
    const { level = 1, ...textProps } = props
    return <Text ref={ref} elementType={`h${level}`} variant="h1" balance {...textProps} />
  }),
)

Text.Heading = Heading

/** Text group component. It's used to visually group text elements together */
Text.Group = function TextGroup(props: React.PropsWithChildren) {
  return (
    <textProvider.TextProvider value={{ isInsideTextComponent: true }}>
      {props.children}
    </textProvider.TextProvider>
  )
}
