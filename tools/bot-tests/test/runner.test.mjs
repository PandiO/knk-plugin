// Unit tests of the bot-test harness that need no Minecraft client or server (PandiO/knk-workspace#540).
// Run: node --test tools/bot-tests/test/*.test.mjs (CI runs them in the paper-boot job, also while the bot tests are off).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BotTestFailure, expect, flatten, freshName, listedVersions } from '../lib/core.mjs'
import { runTests } from '../lib/runner.mjs'

function fakeConnect (joined) {
  return async ({ username }) => {
    const bot = { username, quitCalled: false, quit: async () => { bot.quitCalled = true } }
    joined.push(bot)
    return bot
  }
}

async function run (tests, extra = {}) {
  const lines = []
  const joined = []
  const ok = await runTests(tests, {
    host: '127.0.0.1', port: 25565, version: '26.3', connect: fakeConnect(joined), log: line => lines.push(line), ...extra
  })
  return { ok, lines, joined }
}

test('a passing and a failing test give the report lines the boot check reads', async () => {
  const { ok, lines } = await run([
    { name: 'passes', run: async () => {} },
    { name: 'fails', run: async () => expect(false, 'the bot was\nnot held') }
  ])
  assert.equal(ok, false)
  assert.equal(lines[0], 'KNK-BOT START passes')
  assert.match(lines[1], /^KNK-BOT PASS passes \(\d+ ms\)$/)
  assert.equal(lines[2], 'KNK-BOT START fails')
  assert.equal(lines[3], 'KNK-BOT FAIL fails: the bot was not held')
  assert.equal(lines[4], 'KNK-BOT SUMMARY passed=1 failed=1 total=2')
})

test('an unexpected error fails the test with its type and message on one line', async () => {
  const { lines } = await run([{ name: 'throws', run: () => { throw new TypeError('bad') } }])
  assert.equal(lines[1], 'KNK-BOT FAIL throws: TypeError: bad')
})

test('a test that takes too long fails, and its bots are quit', async () => {
  const { ok, lines, joined } = await run([{
    name: 'slow',
    run: async ctx => {
      await ctx.join('KnkBotSlow')
      await new Promise(() => {})
    }
  }], { timeoutMs: 50 })
  assert.equal(ok, false)
  assert.equal(lines[1], 'KNK-BOT FAIL slow: bot test slow did not finish within 50 ms')
  assert.equal(joined[0].quitCalled, true)
})

test('every bot a test joined is quit after it passes', async () => {
  const { joined } = await run([{ name: 'two-bots', run: async ctx => { await ctx.join('A1b'); await ctx.join('B2c') } }])
  assert.deepEqual(joined.map(bot => bot.quitCalled), [true, true])
})

test('kick reasons become one line of plain text', () => {
  assert.equal(flatten('{"text":"","extra":[{"text":"You need an invitation"},{"text":"\\nGo to "},{"text":"the site"}]}'),
    'You need an invitation Go to the site')
  assert.equal(flatten({ translate: 'multiplayer.disconnect.server_full' }), 'multiplayer.disconnect.server_full')
  assert.equal(flatten('plain reason'), 'plain reason')
})

test('fresh names are new each run and at most 16 characters', () => {
  const name = freshName('KnkBot')
  assert.match(name, /^KnkBot\d{6}$/)
  assert.ok(freshName('AVeryLongPrefixName').length <= 16)
})

test('the supported versions are read from a version file', () => {
  const source = "const testedVersions = ['1.8.8', '1.21.11', '26.1']\nmodule.exports = { testedVersions }"
  assert.deepEqual(listedVersions(source), ['1.8.8', '1.21.11', '26.1'])
  assert.deepEqual(listedVersions('nothing here'), [])
})

test('BotTestFailure is what expect throws', () => {
  assert.throws(() => expect(false, 'no'), BotTestFailure)
})
