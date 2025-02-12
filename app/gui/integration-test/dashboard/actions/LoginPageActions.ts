/** @file Available actions for the login page. */
import { expect } from '@playwright/test'

import { TEXT, VALID_EMAIL, VALID_PASSWORD, passAgreementsDialog } from '.'
import BaseActions, { type LocatorCallback } from './BaseActions'
import DrivePageActions from './DrivePageActions'
import ForgotPasswordPageActions from './ForgotPasswordPageActions'
import RegisterPageActions from './RegisterPageActions'
import SetupUsernamePageActions from './SetupUsernamePageActions'

/** Available actions for the login page. */
export default class LoginPageActions<Context> extends BaseActions<Context> {
  /** Actions for navigating to another page. */
  get goToPage() {
    return {
      register: (): RegisterPageActions<Context> =>
        this.step("Go to 'register' page", async (page) =>
          page.getByRole('link', { name: TEXT.dontHaveAnAccount, exact: true }).click(),
        ).into(RegisterPageActions<Context>),
      forgotPassword: (): ForgotPasswordPageActions<Context> =>
        this.step("Go to 'forgot password' page", async (page) =>
          page.getByRole('link', { name: TEXT.forgotYourPassword, exact: true }).click(),
        ).into(ForgotPasswordPageActions<Context>),
    }
  }

  /** Perform a successful login. */
  login(email = VALID_EMAIL, password = VALID_PASSWORD) {
    return this.step('Login', async (page) => {
      await this.loginInternal(email, password)
      await passAgreementsDialog({ page })
    }).into(DrivePageActions<Context>)
  }

  /** Perform a login as a new user (a user that does not yet have a username). */
  loginAsNewUser(email = VALID_EMAIL, password = VALID_PASSWORD) {
    return this.step('Login (as new user)', async (page) => {
      await this.loginInternal(email, password)
      await passAgreementsDialog({ page })
    }).into(SetupUsernamePageActions<Context>)
  }

  /** Perform a failing login. */
  loginThatShouldFail(
    email = VALID_EMAIL,
    password = VALID_PASSWORD,
    {
      assert = {},
    }: {
      assert?: {
        emailError?: string | null
        passwordError?: string | null
        formError?: string | null
      }
    } = {},
  ) {
    const { emailError, passwordError, formError } = assert
    const next = this.step('Login (should fail)', () => this.loginInternal(email, password))
      .expectInputError('email-input', 'email', emailError)
      .expectInputError('password-input', 'password', passwordError)
    if (formError === undefined) {
      return next
    } else if (formError != null) {
      return next.step(`Expect form error to be '${formError}'`, async (page) => {
        await expect(page.getByTestId('form-submit-error')).toHaveText(formError)
      })
    } else {
      return next.step('Expect no form error', async (page) => {
        await expect(page.getByTestId('form-submit-error')).not.toBeVisible()
      })
    }
  }

  /** Fill the email input. */
  fillEmail(email: string) {
    return this.step(`Fill email with '${email}'`, (page) =>
      page.getByPlaceholder(TEXT.emailPlaceholder).fill(email),
    )
  }

  /** Interact with the email input. */
  withEmailInput(callback: LocatorCallback<Context>) {
    return this.step('Interact with email input', (page, context) =>
      callback(page.getByPlaceholder(TEXT.emailPlaceholder), context),
    )
  }

  /** Internal login logic shared between all public methods. */
  private async loginInternal(email: string, password: string) {
    await this.page.getByPlaceholder(TEXT.emailPlaceholder).fill(email)
    await this.page.getByPlaceholder(TEXT.passwordPlaceholder).fill(password)
    await this.page
      .getByRole('button', { name: TEXT.login, exact: true })
      .getByText(TEXT.login)
      .click()
    await expect(this.page.getByText(TEXT.loadingAppMessage)).not.toBeVisible()
  }
}
