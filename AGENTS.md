# Agent entrypoint — knk-plugin

Before editing, find `knk-workspace/docs/ai-agents/GLOBAL_AGENT_INSTRUCTIONS.md`
and `knk-workspace/docs/ACTIVE_SESSIONS.md` in the local workspace or
[online](https://github.com/PandiO/knk-workspace/tree/main/docs). They govern
cross-repo coordination for all agents. Refresh the tracker, check overlapping
claims, publish a feature-scoped claim, and use the feature's standing branch
from this repo's current default branch. If access is unavailable, disclose it
and avoid conflicting remote edits. Recheck old handoffs against current code
and plans. See [CLAUDE.md](CLAUDE.md) for local Gradle/module conventions;
its `@...` import is Claude Code syntax and may depend on checkout layout.

This is the Paper plugin (Java 21, Gradle modules `knk-core`,
`knk-api-client`, `knk-paper`). Run `./gradlew test` for tests. A normal
`./gradlew build` depends on `deployToDevServer` and can copy a jar to the
configured dev server; use `./gradlew build -x deployToDevServer` to build
without that copy.
