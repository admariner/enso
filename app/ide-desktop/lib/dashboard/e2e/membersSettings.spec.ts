/** @file Test the user settings tab. */
import * as test from '@playwright/test'

import * as actions from './actions'

test.test('members settings', async ({ page }) => {
  const { api } = await actions.mockAllAndLogin({ page })
  const localActions = actions.settings.members

  // Setup
  api.setCurrentOrganization(api.defaultOrganization)

  await localActions.go(page)
  await test
    .expect(localActions.locateMembersRows(page).locator('> :nth-child(1) > :nth-child(2)'))
    .toHaveText([api.currentUser()?.name ?? ''])

  const otherUserName = 'second.user_'
  const otherUser = api.addUser(otherUserName)
  await actions.relog({ page })
  await localActions.go(page)
  await test
    .expect(localActions.locateMembersRows(page).locator('> :nth-child(1) > :nth-child(2)'))
    .toHaveText([api.currentUser()?.name ?? '', otherUserName])

  api.deleteUser(otherUser.userId)
  await actions.relog({ page })
  await localActions.go(page)
  await test
    .expect(localActions.locateMembersRows(page).locator('> :nth-child(1) > :nth-child(2)'))
    .toHaveText([api.currentUser()?.name ?? ''])
})
