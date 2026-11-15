// The parts of the bot-test harness that need no Minecraft client (PandiO/knk-workspace#540): failures, waiting, names
// and kick reasons. Kept free of Mineflayer so CI can unit-test the harness without installing it (node --test test/*.test.mjs).

/** A test step failed; the message is the one-line reason on the FAIL line. */
export class BotTestFailure extends Error {}

/** Fails the test with `reason` unless `condition` holds. */
export function expect (condition, reason) {
  if (!condition) {
    throw new BotTestFailure(reason)
  }
}

/** Resolves after `ms` milliseconds. */
export function sleep (ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * A bot name for this run: `prefix` plus a few random digits, at most 16 characters, so a rerun on the same server is a
 * new player (the server keeps player data and the plugin remembers accounts).
 */
export function freshName (prefix) {
  const digits = String(Math.floor(Math.random() * 1e6)).padStart(6, '0')
  return (prefix + digits).slice(0, 16)
}

export function matches (text, expected) {
  return expected instanceof RegExp ? expected.test(text) : text.includes(expected)
}

/** A kick reason (a chat component, JSON text or plain text) as one line of plain text. */
export function flatten (reason) {
  let value = reason
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      return value.replace(/\s+/g, ' ').trim()
    }
  }
  const parts = []
  const walk = node => {
    if (node === null || node === undefined) return
    if (typeof node === 'string') {
      parts.push(node)
      return
    }
    if (Array.isArray(node)) {
      node.forEach(walk)
      return
    }
    if (typeof node === 'object') {
      if (node.text !== undefined) walk(node.text)
      if (node.translate !== undefined && node.with === undefined) parts.push(node.translate)
      if (node.with !== undefined) walk(node.with)
      if (node.extra !== undefined) walk(node.extra)
      if (node.value !== undefined && node.type !== undefined) walk(node.value)
    }
  }
  walk(value)
  return parts.join('').replace(/\s+/g, ' ').trim()
}

/**
 * The Minecraft versions a Mineflayer or minecraft-protocol version file lists (its first array literal of quoted
 * strings, e.g. `const testedVersions = ['1.8.8', ..., '26.1']`), oldest first.
 */
export function listedVersions (source) {
  const match = source.match(/\[([^\]]*)\]/)
  return match ? [...match[1].matchAll(/'([^']+)'/g)].map(m => m[1]) : []
}
