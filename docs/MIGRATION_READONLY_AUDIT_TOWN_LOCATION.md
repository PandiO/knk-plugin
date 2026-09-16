# MIGRATION READ-ONLY AUDIT – Towns & Locations

Scope: confirm Town and Location flows remain read-only (no create/update/delete, no world-binding). Items are classified as KEEP (read), REMOVE (write/hybrid), or TBD (unclear).

## Findings

| Area | Path | Notes | Class |
| --- | --- | --- | --- |
| Port | [knk-core/src/main/java/net/knightsandkings/knk/core/ports/api/TownsQueryApi.java](knk-core/src/main/java/net/knightsandkings/knk/core/ports/api/TownsQueryApi.java#L10-L12) | Only `search` and `getById` read methods. | KEEP |
| Port | [knk-core/src/main/java/net/knightsandkings/knk/core/ports/api/LocationsQueryApi.java](knk-core/src/main/java/net/knightsandkings/knk/core/ports/api/LocationsQueryApi.java#L10-L11) | Only `search` and `getById` read methods. | KEEP |
| Client impl | [knk-api-client/src/main/java/net/knightsandkings/knk/api/impl/TownsQueryApiImpl.java](knk-api-client/src/main/java/net/knightsandkings/knk/api/impl/TownsQueryApiImpl.java#L29-L240) | Implements read-only search (POST to `/Towns/search`) and get-by-id (GET `/Towns/{id}`); no write endpoints. | KEEP |
| Client impl | [knk-api-client/src/main/java/net/knightsandkings/knk/api/impl/LocationsQueryApiImpl.java](knk-api-client/src/main/java/net/knightsandkings/knk/api/impl/LocationsQueryApiImpl.java#L24-L214) | Implements read-only search (POST to `/Locations/search`) and get-by-id (GET `/Locations/{id}`); no write endpoints. | KEEP |
| DTOs | [knk-api-client/src/main/java/net/knightsandkings/knk/api/dto/TownDto.java](knk-api-client/src/main/java/net/knightsandkings/knk/api/dto/TownDto.java#L5-L19), [knk-api-client/src/main/java/net/knightsandkings/knk/api/dto/TownListDto.java](knk-api-client/src/main/java/net/knightsandkings/knk/api/dto/TownListDto.java#L5-L10), [knk-api-client/src/main/java/net/knightsandkings/knk/api/dto/LocationDto.java](knk-api-client/src/main/java/net/knightsandkings/knk/api/dto/LocationDto.java#L5-L13) | View DTOs only; no Create/Update DTOs present for towns/locations. | KEEP |
| Paper command | [knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/TownsDebugCommand.java](knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/TownsDebugCommand.java#L27-L214) | `/knk towns list` and `/knk town <id>` call query API asynchronously; no mutations. | KEEP |
| Paper command | [knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/LocationsDebugCommand.java](knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/LocationsDebugCommand.java#L17-L113) | `/knk locations list|<id>` reads via query API only. | KEEP |
| Paper command | [knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/LocationDebugCommand.java](knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/LocationDebugCommand.java#L11-L33) | `/knk location here` prints player position; no persistence. | KEEP |
| Paper wiring | [knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/KnkAdminCommand.java](knk-paper/src/main/java/net/knightsandkings/knk/paper/commands/KnkAdminCommand.java#L17-L120) | Registers only read/debug commands for towns, districts, locations; no write flows. | KEEP |
| Mapper | [knk-paper/src/main/java/net/knightsandkings/knk/paper/mapper/PaperLocationMapper.java](knk-paper/src/main/java/net/knightsandkings/knk/paper/mapper/PaperLocationMapper.java#L9-L27) | Pure conversion Bukkit Location -> domain; no world changes. | KEEP |

## Not Found / Verified Absent
- No Town/Location Create or Update DTOs or ports were found.
- No write-side ports or commands (create/update/delete) for Towns or Locations.
- No world-binding/workflow code such as PendingWorldBinding, WorldTask, WorldGuard region create/update, or location creation in knk-plugin.
