# Agent entrypoint — knk-plugin

Before editing, find `knk-workspace/docs/ai-agents/GLOBAL_AGENT_INSTRUCTIONS.md`
and `knk-workspace/docs/ACTIVE_SESSIONS.md` in the local workspace or
[online](https://github.com/PandiO/knk-workspace/tree/main/docs). They govern
cross-repo coordination for all agents. Refresh the tracker, check overlapping
claims, publish a feature-scoped claim, and use the feature's standing branch
from this repo's `next` branch once the global branch model applies (until
then from its current default branch). If access is unavailable, disclose it
and avoid conflicting remote edits. Recheck old handoffs against current code
and plans. See [CLAUDE.md](CLAUDE.md) for local Gradle/module conventions;
its `@...` import is Claude Code syntax and may depend on checkout layout.

This is the Paper plugin (Java 25, Minecraft 26.3, Gradle modules `knk-core`,
`knk-api-client`, `knk-paper`). Run `./gradlew test` for tests. A normal
`./gradlew build` depends on `deployToDevServer` and can copy a jar to the
configured dev server; use `./gradlew build -x deployToDevServer` to build
without that copy.

## Shipping a change

All documentation lives in `knk-workspace/docs/`; this repo keeps only its README, `AGENTS.md`,
`CLAUDE.md` and `.github/`. Follow `knk-workspace/docs/ai-agents/GLOBAL_AGENT_INSTRUCTIONS.md` § Shipping a change
([online](https://github.com/PandiO/knk-workspace/blob/main/docs/ai-agents/GLOBAL_AGENT_INSTRUCTIONS.md#shipping-a-change-documentation)):
fill in the PR template's Docs impact and Admin steps sections, and ship the essential docs (deploy notes, changelog
entry, status) before merge. At merge, close the feature issue, put the remaining docs in a `documentation`
sub-issue and start a new docs session for it right away with
[the docs-session prompt](https://github.com/PandiO/knk-workspace/blob/main/docs/ai-agents/docs-session-prompt.md).

Docs triggers in this repo: every change to `knk-paper/src/main/resources/config.yml` (added, renamed or removed
keys, changed defaults) goes into the PR's Admin steps with the YAML to paste, the changelog entry and the plugin
configuration reference (`knk-workspace/docs/guides/admin/plugin-configuration.md`, PandiO/knk-workspace#295). The plugin calls `saveDefaultConfig()`, so an existing server's `config.yml` never gets new
keys, and they must be merged by hand into the dev and alpha configs at each deploy. A command, permission node or
message a player sees means `guides/users/commands.md`, `architecture/plugin-permission-checks.md` and the player or
admin guide.
