// The real login path of a new player (PandiO/knk-workspace#540): a bot that never played on the server connects
// through the Minecraft protocol in offline mode (login, configuration, spawn), so the admission gate and the account
// lookup at pre-login, the server's spawn preparation and the plugin's join handling all run as for a real client.
// The bot spawns, is held while its account loads ("Loading your account...") and is let go ("You're all set!").
// Leaving and joining again (a rejoin) holds it again. With the API down (CI) the account load ends with its fallback.
import { expect, freshName } from '../lib/harness.mjs'

export const name = 'first-join'
export const description = 'A new player logs in through the real protocol, is held while the account loads, and rejoins'

export async function run (ctx) {
  const username = freshName('KnkBot')
  const first = await ctx.join(username)
  await first.waitForMessage('Loading your account', 15000)
  await first.waitForMessage("You're all set!", 30000)
  await first.quit()

  const again = await ctx.join(username)
  await again.waitForMessage('Loading your account', 15000)
  await again.waitForMessage("You're all set!", 30000)
  expect(again.bot.game.gameMode === 'survival', `after the hold ${username} is in ${again.bot.game.gameMode}, expected survival`)
}
