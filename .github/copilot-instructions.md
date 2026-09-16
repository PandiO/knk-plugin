# Knights & Kings (knk-plugin) – Copilot Instructions

This repository is a multi-module Gradle project targeting Paper (Minecraft 1.21.x, Java 21).

## Architecture (must follow)
Modules:
- knk-core: domain models + application services + ports (interfaces)
  - MUST NOT depend on Bukkit/Paper.
  - MUST NOT perform HTTP/IO directly.
- knk-api-client: HTTP client implementation (OkHttp + Jackson) + DTOs + mapping + auth + retries
  - MUST NOT depend on Bukkit/Paper.
  - Implements knk-core ports (e.g., core.ports.api.*).
- knk-paper: Paper plugin adapter layer
  - Contains commands, listeners, UI/inventory menus, config loading, bootstrap wiring.
  - Must be the only module with Bukkit/Paper imports.

Package root: net.knightsandkings.knk

## Threading rules (critical)
- Never perform blocking I/O (HTTP, file, DB) on the Paper main thread.
- All HTTP calls must run via CompletableFuture using a dedicated ExecutorService.
- Any Bukkit/Paper world/state mutations must run on the main thread via the scheduler.

## Migration mode (critical)
- Entity migrations MUST follow `docs/MIGRATION_MODE_READONLY.md` unless an ADR explicitly allows write/hybrid scope.
- Default: READ-ONLY (search/getById only, no create/update/delete, no world mutations).
- Use `spec/api/swagger.json` as the source of truth for endpoints and DTO schemas.

## API integration rules
- Define ports in knk-core (core.ports.api.*) returning CompletableFuture.
- Implement ports in knk-api-client (api.impl.*) using OkHttp + Jackson.
- Use a single KnkApiClient entrypoint with:
  - baseUrl
  - auth provider (bearer/apikey/none)
  - ObjectMapper
  - OkHttpClient with timeouts
- Provide consistent error handling:
  - Throw ApiException for non-2xx responses including statusCode and a short response snippet.
  - Keep retries limited to idempotent requests (GET) with small backoff.

## Code style
- Java 21. Prefer records for immutable DTOs/domain where appropriate.
- Keep Paper/plugin classes thin: command/listener -> service call -> response.
- Use clear naming:
  - core.domain.*, core.services.*, core.ports.*
  - api.dto.*, api.mapper.*, api.auth.*, api.impl.*
  - paper.bootstrap.*, paper.commands.*, paper.listeners.*, paper.ui.*, paper.config.*

## Build & dependencies
- Use paper-api as compileOnly in knk-paper.
- Shade only what is required into the plugin jar (shadowJar).
- Avoid adding heavy frameworks.

## Tests
- Add unit tests in knk-core where possible (no Paper dependency).
- API-client mapping/parsing should be testable without Paper.

## When unsure
- Create TODO markers instead of inventing unknown API contract details.
- Ask for confirmation only if a decision materially changes architecture or public contracts.
