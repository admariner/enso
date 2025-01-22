/** @file Tests for `dateTime.ts`. */
import * as v from 'vitest'

import {
  formatDateTimeChatFriendly,
  MINUTE_MS,
  toRfc3339,
} from 'enso-common/src/utilities/data/dateTime'

/** Remove all UTC offset from a {@link Date}. Daylight savings-aware. */
function convertUTCToLocal(date: Date) {
  const offsetMins = date.getTimezoneOffset()
  return new Date(Number(date) + offsetMins * MINUTE_MS)
}
/** Adds a UTC offset to a {@link Date}. Daylight savings-aware. */
function convertLocalToUTC(date: Date) {
  const offsetMins = date.getTimezoneOffset()
  return new Date(Number(date) - offsetMins * MINUTE_MS)
}

v.test.each([
  { date: new Date(0), string: '1970-01-01T00:00:00.000Z' },
  {
    date: convertLocalToUTC(new Date(2001, 1, 3)),
    string: '2001-02-03T00:00:00.000Z',
  },
])('Date and time serialization', ({ date, string }) => {
  v.expect(toRfc3339(date)).toBe(string)
})

v.test.each([
  {
    date: convertUTCToLocal(new Date(0)),
    chatString: `01/01/1970 00:00 AM`,
  },
  {
    date: new Date(2001, 1, 3),
    chatString: `03/02/2001 00:00 AM`,
  },
])('Date and time serialization', ({ date, chatString }) => {
  v.expect(formatDateTimeChatFriendly(date)).toBe(chatString)
})
