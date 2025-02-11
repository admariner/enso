import { expect, test } from 'vitest'
import { combineFileParts, splitFileContents } from '../ensoFile'

const cases = [
  `foo`,
  `foo


#### METADATA ####
[]
{}`,
  `a "#### METADATA ####"`,
  `a


#### METADATA ####


#### METADATA ####
[]
{}`,
]

test.each(cases)('File split and combine roundtrip $#', contents => {
  const parts = splitFileContents(contents)
  const combined = combineFileParts(parts)
  expect(combined).toEqual(contents)
})
