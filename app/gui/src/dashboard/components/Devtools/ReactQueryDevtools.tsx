/** @file Show the React Query Devtools. */
import * as React from 'react'

import * as reactQuery from '@tanstack/react-query'
import * as errorBoundary from 'react-error-boundary'
import { useShowDevtools } from './EnsoDevtoolsProvider'

const ReactQueryDevtoolsProduction = React.lazy(() =>
  import('@tanstack/react-query-devtools/build/modern/production.js').then((d) => ({
    default: d.ReactQueryDevtools,
  })),
)

/** Show the React Query Devtools and provide the ability to show them in production. */
export function ReactQueryDevtools() {
  const showDevtools = useShowDevtools()
  // It is safer to pass the client directly to the devtools
  // since there might be a chance that we have multiple versions of `react-query`,
  // in case we forget to update the devtools, npm messes up the versions,
  // or there are hoisting issues.
  const client = reactQuery.useQueryClient()

  return (
    <errorBoundary.ErrorBoundary
      fallbackRender={({ resetErrorBoundary }) => {
        resetErrorBoundary()
        return null
      }}
    >
      {showDevtools === true && (
        <React.Suspense fallback={null}>
          <ReactQueryDevtoolsProduction client={client} />
        </React.Suspense>
      )}
    </errorBoundary.ErrorBoundary>
  )
}
