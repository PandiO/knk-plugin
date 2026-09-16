# Migration Mode: READ-ONLY (Default)

## Scope

By default, all entity migrations (Towns, Locations, Districts, Streets, etc.) are **read-only**. This means:
- Display data from Web API
- Navigate/browse existing records
- No create, update, or delete operations
- No world-binding workflows (WorldGuard region creation, location persistence, etc.)

## Hard Guardrails

**Enforce these rules strictly:**

1. **Ports**: Only `search()` and `getById()` methods allowed in `knk-core/ports/api/*QueryApi.java`
2. **API Implementations**: Only GET and POST (for search) HTTP calls in `knk-api-client/impl/*QueryApiImpl.java`
3. **DTOs**: Only view DTOs (e.g., `TownDto`, `TownListDto`); **no** `CreateTownDto`, `UpdateTownDto`, or similar
4. **Commands**: Only list/get/debug commands in `knk-paper/commands/*DebugCommand.java`
5. **No World Mutations**: No WorldGuard region creation/updates, no location creation flows, no `PendingWorldBinding` or `WorldTask` workflows

## Source of Truth

**`spec/api/swagger.json`** defines:
- Available endpoints (use only GET and search endpoints)
- DTO schemas (map exactly to JSON schema definitions)
- Field names, types, nullability

Do **not** invent fields or endpoints. If Swagger doesn't define it, don't implement it.

## Legacy Usage

Use `knk-v2-archive` **only** for:
- Understanding business rules (e.g., validation logic, permissions, workflow sequences)
- Identifying missing behavior that should be documented as TODOs
- Clarifying domain concepts

Do **not** use legacy for:
- Copying code structure or layers
- Replicating Hibernate/DB patterns
- Porting UI/inventory menu implementations directly

## Migration Workflow

1. **Audit**: Verify entity has only read operations (use `docs/MIGRATION_READONLY_AUDIT_*.md` template)
2. **Implement**: Create ports, DTOs, API client, Paper commands (read-only)
3. **Test**: Ensure all operations are async and run on correct threads (see `docs/API_CLIENT_PATTERN.md`)
4. **Document**: Note any missing write flows as TODOs in `spec/` (not in code)

## Future: Hybrid Mode

Hybrid create/edit flows are **out of scope** for initial migrations. Document requirements in:
- `spec/CREATE_FLOW_SPLIT_TOWNS.md`
- `spec/REQUIREMENTS_HYBRID_CREATE_EDIT_FLOW.md`

Implement hybrid mode only after explicit approval and design review.
