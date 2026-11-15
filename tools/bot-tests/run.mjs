#!/usr/bin/env node
// Runs the bot tests in tests/ against a running server (PandiO/knk-workspace#540) and prints the KNK-BOT report lines
// (lib/harness.mjs). Exits 1 when a test failed.
//
//   node run.mjs [--host 127.0.0.1] [--port 25565] [--version 26.3] [--only <name>] [--timeout-ms 120000]
//
// The server must run in offline mode (the CI boot test's server.properties does). tools/paper-boot/boot-test.sh
// --bot-tests runs this while its server is up; .github/workflows/paper-boot.yml passes it when the repository variable
// BOT_TESTS_ENABLED is true.
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { connect } from './lib/harness.mjs'
import { loadTests, runTests } from './lib/runner.mjs'

const { values: options } = parseArgs({
  options: {
    host: { type: 'string', default: '127.0.0.1' },
    port: { type: 'string', default: '25565' },
    version: { type: 'string', default: process.env.BOT_MC_VERSION || '26.3' },
    only: { type: 'string' },
    'timeout-ms': { type: 'string', default: '120000' }
  }
})

const here = path.dirname(fileURLToPath(import.meta.url))
let tests = await loadTests(path.join(here, 'tests'))
if (options.only) {
  tests = tests.filter(test => test.name === options.only)
}
const ok = await runTests(tests, {
  host: options.host,
  port: Number(options.port),
  version: options.version,
  timeoutMs: Number(options['timeout-ms']),
  connect
})
process.exit(ok ? 0 : 1)
