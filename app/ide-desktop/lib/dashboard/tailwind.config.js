/** @file Configuration for Tailwind. */

// The names come from a third-party API and cannot be changed.
/* eslint-disable no-restricted-syntax, @typescript-eslint/naming-convention, @typescript-eslint/no-magic-numbers */
export default /** @satisfies {import('tailwindcss').Config} */ ({
  content: ['./src/**/*.tsx', './src/**/*.ts'],
  important: `:is(.enso-dashboard, .enso-chat)`,
  theme: {
    extend: {
      colors: {
        /** The default color of all text. */
        // This should be named "regular".
        primary: 'rgba(0, 0, 0, 0.60)',
        'not-selected': 'rgba(0, 0, 0, 0.40)',
        'icon-selected': 'rgba(0, 0, 0, 0.50)',
        'icon-not-selected': 'rgba(0, 0, 0, 0.30)',
        'ide-bg': '#ebeef1',
        selected: 'rgba(255, 255, 255, 0.40)',
        // Should be `#3e515f14`, but `bg-opacity` does not work with RGBA.
        label: '#f0f1f3',
        help: '#3f68ce',
        invite: '#0e81d4',
        cloud: '#0666be',
        share: '#64b526',
        inversed: '#ffffff',
        green: '#3e8b29',
        delete: 'rgba(243, 24, 10, 0.87)',
        v3: '#252423',
        youtube: '#c62421',
        discord: '#404796',
        danger: '#d33b0b',
        // Equivalent to `lch(70% 0 0 / 0.5)`
        'selection-brush': 'rgba(171 171 171 / 0.5)',
        dim: 'rgba(0, 0, 0, 0.25)',
        'dim-darker': 'rgba(0, 0, 0, 0.40)',
        frame: 'rgba(255, 255, 255, 0.40)',
        'frame-selected': 'rgba(255, 255, 255, 0.70)',
        'tag-text': 'rgba(255, 255, 255, 0.90)',
        'tag-text-2': 'rgba(0, 0, 0, 0.60)',
        'permission-owner': 'rgba(236, 2, 2, 0.70)',
        'permission-admin': 'rgba(252, 60, 0, 0.70)',
        'permission-edit': 'rgba(255, 138, 0, 0.90)',
        'permission-read': 'rgba(152, 174, 18, 0.80)',
        'permission-docs': 'rgba(91, 8, 226, 0.64)',
        'permission-exec': 'rgba(236, 2, 2, 0.70)',
        'permission-view': 'rgba(0, 0, 0, 0.10)',
        'label-running-project': '#257fd2',
        'label-low-resources': '#ff6b18',
        'call-to-action': '#fa6c08',
        'black-a5': 'rgba(0, 0, 0, 0.05)',
        'black-a10': 'rgba(0, 0, 0, 0.10)',
        'black-a16': 'rgba(0, 0, 0, 0.16)',
        'black-a30': 'rgba(0, 0, 0, 0.30)',
        'black-a50': 'rgba(0, 0, 0, 0.50)',
        'gray-350': '#b7bcc5',
      },
      fontSize: {
        xs: '0.71875rem',
        sm: '0.8125rem',
        xl: '1.1875rem',
        '4xl': '2.375rem',
      },
      borderRadius: {
        '2.5xl': '1.25rem',
        '4xl': '2rem',
      },
      lineHeight: {
        144.5: '144.5%',
        170: '170%',
      },
      spacing: {
        0.75: '0.1875rem',
        1.25: '0.3125rem',
        1.75: '0.4375rem',
        2.25: '0.5625rem',
        3.25: '0.8125rem',
        3.75: '0.9375rem',
        3.5: '0.875rem',
        4.5: '1.125rem',
        4.75: '1.1875rem',
        5.5: '1.375rem',
        6.5: '1.625rem',
        7.25: '1.75rem',
        7.5: '1.875rem',
        9.5: '2.375rem',
        9.75: '2.4375rem',
        13: '3.25rem',
        18: '4.5rem',
        25: '6.25rem',
        29: '7.25rem',
        30: '7.5rem',
        30.25: '7.5625rem',
        38.25: '9.5625rem',
        42: '10.5rem',
        45: '11.25rem',
        51: '12.75rem',
        51.5: '12.875rem',
        54: '13.5rem',
        57.5: '14.375rem',
        62: '15.5rem',
        70: '17.5rem',
        83.5: '20.875rem',
        84: '21rem',
        112.5: '28.125rem',
        115.25: '28.8125rem',
        120: '30rem',
        140: '35rem',
        155: '38.75rem',
        '10lh': '10lh',
      },
      minHeight: {
        '5lh': '5lh',
      },
      width: {
        container: '100cqw',
      },
      minWidth: {
        31.5: '7.875rem',
        32: '8rem',
        33.25: '8.3125rem',
        40: '10rem',
        61.25: '15.3125rem',
        80: '20rem',
        96: '24rem',
        120: '30rem',
      },
      maxWidth: {
        98.25: '24.5625rem',
        'screen-1/3': '33.333333vw',
      },
      opacity: {
        '1/3': '.33333333',
      },
      zIndex: {
        1: '1',
        3: '3',
      },
      backdropBlur: {
        xs: '2px',
      },
      borderWidth: { 0.5: '0.5px', 6: '6px' },
      boxShadow: {
        soft: `0 0.5px 2.2px 0px #00000008, 0 1.2px 5.3px 0px #0000000b, \
0 2.3px 10px 0 #0000000e, 0 4px 18px 0 #00000011, 0 7.5px 33.4px 0 #00000014, \
0 18px 80px 0 #0000001c`,
      },
      animation: {
        'spin-ease': 'spin cubic-bezier(0.67, 0.33, 0.33, 0.67) 1.5s infinite',
      },
      transitionProperty: {
        width: 'width',
        'min-width': 'min-width',
        'stroke-dasharray': 'stroke-dasharray',
        'grid-template-rows': 'grid-template-rows',
        'border-margin': 'border, margin',
      },
      transitionDuration: {
        400: '400ms',
        5000: '5000ms',
        90000: '90000ms',
      },
      gridTemplateRows: {
        '0fr': '0fr',
        '1fr': '1fr',
      },
      gridTemplateColumns: {
        'fill-60': 'repeat(auto-fill, minmax(15rem, 1fr))',
        'fill-75': 'repeat(auto-fill, minmax(18.75rem, 1fr))',
      },
    },
  },
})
