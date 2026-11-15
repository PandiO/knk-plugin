// Headless bot client for plugin tests (PandiO/knk-workspace#540): Mineflayer bots that join the CI Paper server in
// offline mode through the real Minecraft protocol (login, configuration, spawn), so they cover what the in-plugin
// fake players skip. Docs: docs/guides/developer/plugin-scenarios.md § 8 and ci.md § 7.5 in knk-workspace.
//
// A bot test is a module in tests/ that exports { name, description, run(ctx) }; run.mjs runs them one after the other
// and prints the report lines (the same contract as the in-plugin scenarios, read by tools/paper-boot/check_scenarios.py
// with --tag KNK-BOT):
//
//   KNK-BOT START <name>
//   KNK-BOT PASS <name> (<n> ms)
//   KNK-BOT FAIL <name>: <reason>
//   KNK-BOT SUMMARY passed=<p> failed=<f> total=<t>
import mineflayer from 'mineflayer'
import { BotTestFailure, expect, flatten, matches, sleep } from './core.mjs'

export { BotTestFailure, expect, freshName, sleep } from './core.mjs'

/**
 * One connected bot with the messages it received. Messages are plain text with their chat position (`chat`,
 * `system` or `game_info` for the action bar), recorded from the moment the connection starts.
 */
export class TestBot {
  constructor (bot) {
    this.bot = bot
    this.messages = []
    this.ended = null
    bot.on('messagestr', (text, position) => this.messages.push({ text, position }))
    bot.on('kicked', reason => { this.ended = `kicked: ${flatten(reason)}` })
    bot.on('end', reason => { this.ended = this.ended ?? `ended: ${reason}` })
  }

  get username () {
    return this.bot.username
  }

  /** Whether a message containing `text` (a string or a RegExp) was received. */
  received (text) {
    return this.messages.some(message => matches(message.text, text))
  }

  /** Waits until a message containing `text` (a string or a RegExp) arrives; fails after `timeoutMs`. */
  async waitForMessage (text, timeoutMs = 10000) {
    const deadline = Date.now() + timeoutMs
    while (!this.received(text)) {
      expect(this.ended === null, `${this.username} left while waiting for "${text}" (${this.ended})`)
      expect(Date.now() < deadline, `${this.username} did not receive "${text}" within ${timeoutMs} ms; got ${this.recent()}`)
      await sleep(50)
    }
  }

  /** Forgets the messages received so far. */
  clearMessages () {
    this.messages.length = 0
  }

  /** Says something in public chat. */
  chat (text) {
    this.bot.chat(text)
  }

  /** Runs a command as typed (leading / optional). */
  command (commandLine) {
    this.bot.chat(commandLine.startsWith('/') ? commandLine : `/${commandLine}`)
  }

  /** Waits for a window (a menu or a container) to open; fails after `timeoutMs`. Resolves with the window. */
  waitForWindow (timeoutMs = 10000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.bot.removeListener('windowOpen', onOpen)
        reject(new BotTestFailure(`no window opened for ${this.username} within ${timeoutMs} ms`))
      }, timeoutMs)
      const onOpen = window => {
        clearTimeout(timer)
        resolve(window)
      }
      this.bot.once('windowOpen', onOpen)
    })
  }

  /** Left-clicks a slot of the open window (slot numbers as the server counts them, top inventory first). */
  async clickWindowSlot (slot) {
    expect(this.bot.currentWindow, `${this.username} has no window open to click slot ${slot} in`)
    await this.bot.clickWindow(slot, 0, 0)
  }

  /** The last few messages, for a failure reason. */
  recent () {
    return JSON.stringify(this.messages.slice(-6).map(message => message.text))
  }

  /** Leaves the server and waits for the connection to close. */
  async quit () {
    if (this.ended !== null) {
      return
    }
    const closed = new Promise(resolve => this.bot.once('end', resolve))
    this.bot.quit()
    await Promise.race([closed, sleep(5000)])
  }
}

/**
 * Connects a bot in offline mode and waits until it has spawned. Fails with the server's refusal when it is kicked
 * before (an admission refusal, a full server) or with the connection error.
 */
export async function connect ({ host, port, version, username, timeoutMs = 30000 }) {
  const bot = mineflayer.createBot({ host, port, version, username, auth: 'offline', hideErrors: true })
  const testBot = new TestBot(bot)
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new BotTestFailure(`${username} did not spawn within ${timeoutMs} ms`)), timeoutMs)
    function finish (error) {
      clearTimeout(timer)
      bot.removeListener('spawn', onSpawn)
      bot.removeListener('kicked', onKicked)
      bot.removeListener('error', onError)
      if (error) {
        bot.end()
        reject(error)
      } else {
        resolve()
      }
    }
    const onSpawn = () => finish(null)
    const onKicked = reason => finish(new BotTestFailure(`${username} was refused: ${flatten(reason)}`))
    const onError = error => finish(new BotTestFailure(`${username} could not connect: ${error.message}`))
    bot.once('spawn', onSpawn)
    bot.once('kicked', onKicked)
    bot.once('error', onError)
  })
  return testBot
}
