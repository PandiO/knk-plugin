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
- No dedicated `gui/`/`menus/` package exists yet — searched for
  Menu/Gui-named classes and found none. Inventory menus (see
  `docs/specs/inventory-menu` in `knk-workspace`) don't appear to be
  implemented in V3 yet.
- REST client, DTOs, and auth live in the `knk-api-client` module under
  `net/knightsandkings/knk/api/` (auth: `api/auth/BearerAuthProvider.java`)

**Conventions:**
- Talks to `knk-web-api` via direct REST calls from `knk-api-client`,
  authenticated with a JWT bearer token (`BearerAuthProvider`), matching
  the API's JWT setup — no polling/webhook indirection found.
- No local/legacy flat-file or embedded-DB storage remains:
  `dataaccess/DataAccessFactory.java` builds cache-first gateways
  (`FetchPolicy.CACHE_FIRST` by default, per-entity configurable, TTL +
  retry from `config.yml`) backed entirely by the REST API for every entity
  type it wires up (Users, Towns, Districts, Structures, Streets,
  Locations, EnchantmentDefinitions, ItemBlueprints,
  MinecraftMaterialRefs, Domains, Health) — this confirms the V2→V3
  storage migration is complete for those entities.
- WorldGuard/WorldEdit are still declared as `compileOnly` dependencies in
  `knk-paper` (carried over from V1/V2). <<ASK HUMAN: are these still
  actively used, or a leftover? Confirming would need a call-site search
  across the codebase, which is out of scope for this doc pass — the
  upcoming codebase scan should settle it.>>
- Target Minecraft version: 1.21.10 (Paper).
