/** @file Test the login flow. */
import * as test from '@playwright/test'

import { INVALID_PASSWORD, mockAll, TEXT, VALID_EMAIL, VALID_PASSWORD } from './actions'

// =============
// === Tests ===
// =============

// Reset storage state for this file to avoid being authenticated
test.test.use({ storageState: { cookies: [], origins: [] } })

test.test('login screen', ({ page }) =>
  mockAll({ page })
    .loginThatShouldFail('invalid email', VALID_PASSWORD, {
      assert: {
        emailError: TEXT.invalidEmailValidationError,
        passwordError: null,
        formError: null,
      },
    })
    // Technically it should not be allowed, but
    .login(VALID_EMAIL, INVALID_PASSWORD)
    .withDriveView(async (driveView) => {
      await test.expect(driveView).toBeVisible()
    }),
)
