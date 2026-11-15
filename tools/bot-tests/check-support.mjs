#!/usr/bin/env node
// Says whether the pinned Mineflayer and minecraft-protocol versions (package-lock.json) list a Minecraft version, and
// with --latest also whether the newest ones on npm do (PandiO/knk-workspace#540). The bot tests stay off until they
// list the CI server's version (26.3); this tells when to bump the pins and set BOT_TESTS_ENABLED (ci.md § 7.5 in
// knk-workspace).
//
//   node check-support.mjs [--version 26.3] [--latest] [--require]
//
// Needs no npm install: it reads the versions from the lock file and each package's version list from its npm tarball
// (npm pack). Prints one KNK-BOT-SUPPORT line per package and a verdict; with --require exits 1 when a pinned package
// doesn't list the version (the enabled CI step uses it to fail with that reason instead of a protocol error).
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { listedVersions } from './lib/core.mjs'

const { values: options } = parseArgs({
  options: {
    version: { type: 'string', default: process.env.BOT_MC_VERSION || '26.3' },
    latest: { type: 'boolean', default: false },
    require: { type: 'boolean', default: false }
  }
})

// Where each package lists the Minecraft versions it supports.
const PACKAGES = {
  mineflayer: { file: 'package/lib/version.js', list: 'testedVersions' },
  'minecraft-protocol': { file: 'package/src/version.js', list: 'supportedVersions' }
}

const here = path.dirname(fileURLToPath(import.meta.url))
const lock = JSON.parse(readFileSync(path.join(here, 'package-lock.json'), 'utf8'))

function fetchListed (pkg, version) {
  const dir = mkdtempSync(path.join(tmpdir(), 'knk-bot-support-'))
  try {
    const tarball = execFileSync('npm', ['pack', `${pkg}@${version}`, '--silent', '--pack-destination', dir],
      { encoding: 'utf8' }).trim().split('\n').pop()
    const source = execFileSync('tar', ['-xzf', path.join(dir, tarball), '-O', PACKAGES[pkg].file], { encoding: 'utf8' })
    return listedVersions(source)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

function latestVersion (pkg) {
  return execFileSync('npm', ['view', pkg, 'version'], { encoding: 'utf8' }).trim()
}

let pinnedOk = true
for (const pkg of Object.keys(PACKAGES)) {
  const pinned = lock.packages?.[`node_modules/${pkg}`]?.version
  const rows = [['pinned', pinned]]
  if (options.latest) {
    rows.push(['latest', latestVersion(pkg)])
  }
  for (const [kind, version] of rows) {
    const listed = fetchListed(pkg, version)
    const supported = listed.includes(options.version)
    if (kind === 'pinned' && !supported) {
      pinnedOk = false
    }
    console.log(`KNK-BOT-SUPPORT ${pkg} ${kind}=${version} lists ${options.version}: ${supported ? 'yes' : 'no'}` +
      ` (newest listed: ${listed[listed.length - 1] ?? 'none'})`)
  }
}
console.log(pinnedOk
  ? `The pinned bot client supports Minecraft ${options.version}: the bot tests can be switched on (BOT_TESTS_ENABLED).`
  : `The pinned bot client does not list Minecraft ${options.version} yet: keep the bot tests off, or bump the pins once a release lists it.`)
if (options.require && !pinnedOk) {
  process.exit(1)
}
