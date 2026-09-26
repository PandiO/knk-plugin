# CLAUDE.md — knk-plugin

## Global project context
@../../docs/ai-agents/GLOBAL_AGENT_INSTRUCTIONS.md

If the import above didn't load (e.g. this repo isn't checked out inside a
`knk-workspace` checkout at `Repository/knk-plugin` on this machine — that's
the current layout, but it may differ on other machines), here are the
essentials it contains:

- Knights and Kings V3 = `knk-web-app` (React/TS) + `knk-web-api`
  (ASP.NET Core) + `knk-plugin` (Spigot/Paper), one shared MySQL DB. Most
  features span all three repos.
- Current priority: reach MVP, siege minigame is the headline feature.
- The developer works on this evenings/weekends around a full-time job —
  don't require synchronous mid-week decisions; leave sessions in a clean,
  resumable state with clear handoff notes.
- Multiple sessions often run in parallel across repos on the same feature.
  Check and update `knk-workspace/docs/ACTIVE_SESSIONS.md` before and after
  working, and scope your claim by feature, not just by repo.
- Docs live in `knk-workspace` under `vision/ architecture/ guides/
  ai-agents/ specs/ backlog/ reports/ archive/` — don't scatter new docs
  elsewhere.

## Repo-specific conventions (knk-plugin)

**Stack:** Multi-module Gradle project (Kotlin DSL), Java 21 toolchain —
`knk-core` (shared domain/ports), `knk-api-client` (REST client to
knk-web-api), `knk-paper` (the actual Spigot/Paper plugin). No Maven —
confirmed no `pom.xml` anywhere in the repo. Targets Paper API
`1.21.10-R0.1-SNAPSHOT`.

**Common commands:**
- Build all modules: `./gradlew build`
- Test all modules: `./gradlew test` (integration tests tagged
  `integration`/`requires-bukkit` are excluded by default in `knk-paper`)
- Build + deploy the plugin jar to the local dev server:
  `./gradlew :knk-paper:dev` — runs `shadowJar` then copies the jar into the
  `plugins/` folder of the dev server at the path set by the
  `devServerDirectory` Gradle property (currently
  `gradle.properties` → `MinecraftServer/Servers/DEV_SERVER_1.21.10`)
- CI: `.github/workflows/build.yml` runs `./gradlew build` (all modules +
  unit tests) on every push and PR. It exists because cloud agent sessions
  can't reach `repo.papermc.io`, so they can't build locally — push and
  check the GitHub run instead. As of 2026-09-26 it's only on the feature
  branches (e.g. `claude/currency-payments`), not yet on `main`.

**Structure** (all under
`knk-paper/src/main/java/net/knightsandkings/knk/paper/` unless noted):
- Commands: `commands/`
- Listeners: `listeners/`
- Events: `events/`
- Client-side caching over the REST API: `cache/`, `dataaccess/`
- HTTP/API integration glue: `http/`, `integration/`
- Gate structures (relevant to the current siege-minigame/gate work):
  `gates/`
- Config: `src/main/resources/config.yml`, `plugin.yml`
- Inventory menus: `menu/` holds the menu engine (`MenuService`,
  `MenuRenderer`, `MenuFeature`/`MenuFeatureRegistries`, click/lifecycle
  listeners); the concrete menus are `MenuFeature` implementations in
  `menu/content/` (hub, profile, kits, items catalog, user manager, ...)
  plus feature-owned ones elsewhere (e.g. `siege/SiegeMenuFeature`). Spec:
  `docs/specs/inventory-menu/` in `knk-workspace`.
- Pollers: `tasks/HeadlessWorldTaskPoller` (player-less WorldTasks) and
  `tasks/PlayerNotificationPoller` (queued in-game notifications)
- REST client, DTOs, and auth live in the `knk-api-client` module under
  `net/knightsandkings/knk/api/` (auth providers in `api/auth/`:
  `NoAuthProvider`, `BearerAuthProvider`, `ApiKeyAuthProvider`)

**Conventions:**
- Talks to `knk-web-api` via direct REST calls from `knk-api-client`.
  Auth is set by `api.auth.type` in `config.yml` (`none`, `bearer`, or
  `apikey`). On `main` it ships as `none`: the plugin calls anonymously.
  With `apikey`, `ApiKeyAuthProvider` sends the `X-API-Key` header, which
  must match the API's `Security:PluginApiKey`. The API on `master` only
  checks that key once it's set there, and only in two places: before
  trusting the `X-Acting-User-Id` staff attribution header, and on the
  opt-in `[RequirePluginServiceKey]` endpoints (siege match writes, one
  gate-structure endpoint). `bearer` (`BearerAuthProvider`) is supported
  but isn't the plugin's model: the API issues JWTs to web-app users at
  login (`TokenService`), with no service token for the plugin.
  KNG-22 (unmerged branch `claude/currency-payments`) makes `apikey` the
  default. The plugin then refuses to start with an empty `api-key`, and
  the API rejects plugin calls to its protected routes without the key.
- The API never pushes to the plugin. The plugin polls it instead (see
  Pollers above: world tasks and player notifications). Both carry a
  TODO to switch to an API push, e.g. SignalR, once one exists.
- No local/legacy flat-file or embedded-DB storage remains:
  `dataaccess/DataAccessFactory.java` builds cache-first gateways
  (`FetchPolicy.CACHE_FIRST` by default, per-entity configurable, TTL +
  retry from `config.yml`) backed entirely by the REST API for every entity
  type it wires up (Users, Towns, Districts, Structures, Streets,
  Locations, EnchantmentDefinitions, ItemBlueprints,
  MinecraftMaterialRefs, Domains, Health) — this confirms the V2→V3
  storage migration is complete for those entities.
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
