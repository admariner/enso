/** @file Actions for the "setup" page. */
import { TEXT } from '.'
import BaseActions from './BaseActions'
import SetupPlanPageActions from './SetupPlanPageActions'

/** Actions for the "setup" page. */
export default class SetupUsernamePageActions<Context> extends BaseActions<Context> {
  /** Set the username for a new user that does not yet have a username. */
  setUsername(username: string) {
    return this.step(`Set username to '${username}'`, async (page) => {
      await page.getByPlaceholder(TEXT.usernamePlaceholder).fill(username)
      await page.getByText(TEXT.next).click()
    }).into(SetupPlanPageActions<Context>)
  }
}
