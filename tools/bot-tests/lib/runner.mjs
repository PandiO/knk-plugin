// Loads and runs bot tests and prints their report lines (PandiO/knk-workspace#540; the line format is in
// lib/harness.mjs). Mineflayer-free: the caller passes `connect`, so CI unit-tests this without installing it.
import { readdir } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { BotTestFailure } from './core.mjs'

const NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/

/** The bot tests in `directory` (its .mjs files, by file name). */
export async function loadTests (directory) {
  const files = (await readdir(directory)).filter(file => file.endsWith('.mjs')).sort()
  const tests = []
  for (const file of files) {
    const test = await import(pathToFileURL(path.join(directory, file)).href)
    if (!NAME.test(test.name ?? '') || typeof test.run !== 'function') {
      throw new Error(`${file}: a bot test exports a name (lower-case words joined by hyphens) and run(ctx)`)
    }
    tests.push(test)
  }
  return tests
}

/** One line, as the FAIL line needs. */
export function reason (error) {
  const text = error instanceof BotTestFailure ? error.message : `${error?.name ?? 'Error'}: ${error?.message ?? error}`
  return text.replace(/\s+/g, ' ').trim()
}

function withTimeout (promise, ms, what) {
  let timer
  return Promise.race([
    promise.finally(() => clearTimeout(timer)),
    new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new BotTestFailure(`${what} did not finish within ${ms} ms`)), ms)
    })
  ])
}

/**
 * Runs `tests` one after the other. Each gets `ctx` with the server's host, port and version and `join(username)`,
 * which connects a bot through `connect` (every bot a test joined is quit after it, also when it fails). Returns
 * whether every test passed.
 */
export async function runTests (tests, { host, port, version, timeoutMs = 120000, connect, log = console.log }) {
  let passed = 0
  let failed = 0
  for (const test of tests) {
    log(`KNK-BOT START ${test.name}`)
    const bots = []
    const ctx = {
      host,
      port,
      version,
      async join (username) {
        const bot = await connect({ host, port, version, username })
        bots.push(bot)
        return bot
      }
    }
    const started = Date.now()
    try {
      await withTimeout(Promise.resolve().then(() => test.run(ctx)), timeoutMs, `bot test ${test.name}`)
      passed++
      log(`KNK-BOT PASS ${test.name} (${Date.now() - started} ms)`)
    } catch (error) {
      failed++
      log(`KNK-BOT FAIL ${test.name}: ${reason(error)}`)
    } finally {
      await Promise.all(bots.map(bot => Promise.resolve().then(() => bot.quit()).catch(() => {})))
    }
  }
  log(`KNK-BOT SUMMARY passed=${passed} failed=${failed} total=${passed + failed}`)
  return failed === 0
}
