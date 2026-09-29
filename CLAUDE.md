# CLAUDE.md — knk-plugin

## Global project context
@../../docs/ai-agents/GLOBAL_AGENT_INSTRUCTIONS.md

Read `AGENTS.md` and the current `knk-workspace/docs/ACTIVE_SESSIONS.md`
before editing. The import above is Claude Code syntax for the nested
`knk-workspace/Repository/knk-plugin` layout. If it does not resolve, locate
the shared file in the workspace or open it from knk-workspace's current
default branch. Do not rely on a dated handoff without checking the current
branches, issue, plan and tracker.

## Repo-specific conventions (knk-plugin)

**Stack:** Multi-module Gradle project (Kotlin DSL), Java 21 toolchain —
`knk-core` (shared domain/ports), `knk-api-client` (REST client to
knk-web-api), `knk-paper` (the actual Spigot/Paper plugin). No Maven —
confirmed no `pom.xml` anywhere in the repo. Targets Paper API
`1.21.10-R0.1-SNAPSHOT`.

**Common commands:**
- Build all modules without copying the jar to the configured dev server:
  `./gradlew build -x deployToDevServer`. The normal `./gradlew build`
  depends on `deployToDevServer` in `knk-paper/build.gradle.kts`.
- Test all modules: `./gradlew test` (integration tests tagged
  `integration`/`requires-bukkit` are excluded by default in `knk-paper`)
- Build + deploy the plugin jar to the local dev server:
  `./gradlew :knk-paper:dev` — runs `shadowJar` then copies the jar into the
  `plugins/` folder of the dev server at the path set by the
  `devServerDirectory` Gradle property (currently
  `gradle.properties` → `MinecraftServer/Servers/DEV_SERVER_1.21.10`)

**Structure** (all under
`knk-paper/src/main/java/net/knightsandkings/knk/paper/` unless noted):
- Commands: `commands/`
- Listeners: `listeners/`
- Events: `events/`
- Client-side caching over the REST API: `cache/`, `dataaccess/`
- HTTP/API integration glue: `http/`, `integration/`
- Gate structures (relevant to the current siege-minigame/gate work):
  `gates/`
- Managed WorldGuard regions (parent/priority/category flags + startup repair):
  policy and reconciler in `knk-core/.../core/regions/managed/`, WorldGuard adapter and
  wiring in `regions/managed/`, `/knk regions repair` in `commands/RegionsAdminCommand.java`;
  see `docs/architecture/managed-worldguard-regions.md` in `knk-workspace`
- Config: `src/main/resources/config.yml`, `plugin.yml`
- Inventory menu runtime lives in `knk-paper/.../paper/menu/`, with feature
  implementations in `menu/content/` and reusable logic in `knk-core`.
- REST client, DTOs, and auth live in `knk-api-client` under
  `net/knightsandkings/knk/api/` (including `api/auth/`).

**Conventions:**
- Talks to `knk-web-api` via `knk-api-client` REST calls. The auth provider
  configured by `api.auth.type` can be `apikey` (`ApiKeyAuthProvider`) or
  `bearer` (`BearerAuthProvider`); protected game-server writes require the
  configured plugin service key (`Security:PluginApiKey`) unless the API's
  explicit development bypass applies. `PlayerNotificationPoller` polls
  the API for player notifications; consult the actual endpoint attributes
  when changing authentication.
- `dataaccess/DataAccessFactory.java` builds REST-backed, cache-first
  gateways (`FetchPolicy.CACHE_FIRST` by default, per-entity configurable).
  The plugin also uses local files for runtime state such as siege recovery;
  inspect the owning feature before assuming all storage is remote.
- WorldGuard/WorldEdit are actively used, not V1/V2 leftovers — confirmed
  by the developer and by call sites: `WorldGuardIntegration.java`
  (`integration/`) wraps WorldGuard's `RegionManager`/`RegionContainer` and
  WorldEdit's `BukkitAdapter` for region-feasibility checks, and
  `WorldGuardRegionTracker.java` (`regions/`) plus several `tasks/` handlers
  (`GateDoorRegionCaptureHandler`, `GateBlockScanTaskHandler`,
  `WgRegionIdTaskHandler`, `LocationTaskHandler`, `TempRegionRetentionTask`,
  `GateRegionDataFormat`) build on it for gate-structure and world-task
  region tracking.
- Target Minecraft version: 1.21.10 (Paper).
- Tests: never build a `Location` around an inline `mock(World.class)`.
  `Location` holds its world only through a `WeakReference` and
  `getWorld()` throws `IllegalArgumentException("World unloaded")` once it's
  collected, so the test flakes on GC timing. Keep the mocked `World` in a
  field (see `WorldGuardCombatSafezonesTest`).
