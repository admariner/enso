/** @file Test the login flow. */
import { test } from '@playwright/test'

import { INVALID_PASSWORD, mockAll, TEXT, VALID_EMAIL, VALID_PASSWORD } from './actions'

// Reset storage state for this file to avoid being authenticated
test.use({ storageState: { cookies: [], origins: [] } })

test('sign up without organization id', ({ page }) =>
  mockAll({ page })
    .goToPage.register()
    .registerThatShouldFail('invalid email', VALID_PASSWORD, VALID_PASSWORD, {
      assert: {
        emailError: TEXT.invalidEmailValidationError,
        passwordError: null,
        confirmPasswordError: null,
        formError: null,
      },
    })
    .registerThatShouldFail(VALID_EMAIL, INVALID_PASSWORD, INVALID_PASSWORD, {
      assert: {
        emailError: null,
        passwordError: TEXT.passwordValidationError,
        confirmPasswordError: null,
        formError: null,
      },
    })
    .registerThatShouldFail(VALID_EMAIL, VALID_PASSWORD, INVALID_PASSWORD, {
      assert: {
        emailError: null,
        passwordError: null,
        confirmPasswordError: TEXT.passwordMismatchError,
        formError: null,
      },
    })
    .register())
