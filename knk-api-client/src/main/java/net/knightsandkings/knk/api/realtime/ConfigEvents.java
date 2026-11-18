package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The S3 events of the game hub (PandiO/knk-workspace#182, DESIGN §6.2–§6.4): configuration and entity changes the
 * game server follows, as sent by {@code knk-web-api/Realtime/Contracts/ConfigContracts.cs}. Each {@code parse} returns
 * null for a payload it can't use; the handler then ignores the event, and the next resync catches up.
 */
public final class ConfigEvents {

    public static final String ENTITY_CHANGED = "EntityChanged";
    public static final String GATE_CHANGED = "GateChanged";
    public static final String DOMAIN_ACCESS_CHANGED = "DomainAccessChanged";
    public static final String GAME_SETTINGS_CHANGED = "GameSettingsChanged";
    public static final String SIEGE_CONFIG_CHANGED = "SiegeConfigChanged";
    public static final String LOOTBOX_CONFIG_CHANGED = "LootboxConfigChanged";
    public static final String TELEPORT_DESTINATIONS_CHANGED = "TeleportDestinationsChanged";
    public static final String NAVIGATION_SETTINGS_CHANGED = "NavigationSettingsChanged";
    public static final String ROAD_NETWORK_CHANGED = "RoadNetworkChanged";
    public static final String TELEMETRY_CONFIG_CHANGED = "TelemetryConfigChanged";

    /** Every API → game server type above, version 1. */
    public static final List<String> TYPES = List.of(ENTITY_CHANGED, GATE_CHANGED, DOMAIN_ACCESS_CHANGED,
        GAME_SETTINGS_CHANGED, SIEGE_CONFIG_CHANGED, LOOTBOX_CONFIG_CHANGED, TELEPORT_DESTINATIONS_CHANGED,
        NAVIGATION_SETTINGS_CHANGED, ROAD_NETWORK_CHANGED, TELEMETRY_CONFIG_CHANGED);

    /** Game server → API: the loaded worlds, the body of {@code PUT api/GameSettings/runtime-worlds}. */
    public static final String REPORT_WORLDS = "ReportWorlds";

    public static final String CREATED = "Created";
    public static final String UPDATED = "Updated";
    public static final String DELETED = "Deleted";

    private ConfigEvents() {
    }

    /**
     * {@code EntityChanged}: an entity of a type the plugin caches changed.
     *
     * @param type entity type name ({@code Town}, {@code ItemBlueprint}, ...)
     * @param id   its id; null when many of that type changed at once (drop everything of that type)
     * @param op   {@link #CREATED}, {@link #UPDATED} or {@link #DELETED}
     * @param key  the natural key some caches use (a user's UUID, a domain's region id, a menu template's key), or null
     */
    public record EntityChanged(String type, Integer id, String op, String key) {
        public static EntityChanged parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            String type = textField(p, "type");
            if (type == null || type.isBlank()) {
                return null;
            }
            Integer id = intField(p, "id");
            if (id != null && id <= 0) {
                return null;
            }
            String op = textField(p, "op");
            return new EntityChanged(type, id, op == null ? UPDATED : op, textField(p, "key"));
        }

        /** True when the event names no single entity (drop the whole type). */
        public boolean all() {
            return id == null;
        }
    }

    /** {@code GateChanged}: a gate structure's definition (or a door's) changed; {@code op} Deleted = the structure is gone. */
    public record GateChanged(int gateStructureId, String op) {
        public static GateChanged parse(RealtimeEnvelope envelope) {
            Integer id = intField(envelope.payload(), "gateStructureId");
            if (id == null || id <= 0) {
                return null;
            }
            String op = textField(envelope.payload(), "op");
            return new GateChanged(id, op == null ? UPDATED : op);
        }

        public boolean deleted() {
            return DELETED.equals(op);
        }
    }

    /** {@code DomainAccessChanged}: a domain's AllowEntry/AllowExit, region or sub-regions changed. */
    public record DomainAccessChanged(int domainId) {
        public static DomainAccessChanged parse(RealtimeEnvelope envelope) {
            Integer id = intField(envelope.payload(), "domainId");
            return id == null || id <= 0 ? null : new DomainAccessChanged(id);
        }
    }

    /** The "refresh this section" events ({@code GameSettingsChanged}, {@code SiegeConfigChanged}, ...): {@code scope} is informational. */
    public record ScopeChanged(String scope) {
        public static ScopeChanged parse(RealtimeEnvelope envelope) {
            return new ScopeChanged(textField(envelope.payload(), "scope"));
        }
    }

    /** {@code RoadNetworkChanged}: the road tiles of {@code world} changed; null = the profiles or every world. */
    public record RoadNetworkChanged(String world) {
        public static RoadNetworkChanged parse(RealtimeEnvelope envelope) {
            String world = textField(envelope.payload(), "world");
            return new RoadNetworkChanged(world == null || world.isBlank() ? null : world);
        }
    }

    /**
     * The {@code ReportWorlds} argument, with the JSON names of the API's {@code GameSettingsRuntimeWorldsUpdateDto}.
     *
     * @param serverId the {@code game-settings.server-id} the REST report sends (null = the API's default id)
     * @param worlds   one map per world: {@code worldName}, {@code folderName}, {@code environment}, {@code loaded},
     *                 {@code playerCount}, {@code isPrimary}
     */
    public static Map<String, Object> worldsReport(String serverId, List<Map<String, Object>> worlds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverId", serverId == null || serverId.isBlank() ? null : serverId.trim());
        body.put("runtimeWorlds", worlds == null ? List.of() : new ArrayList<>(worlds));
        return body;
    }

    /** One {@code runtimeWorlds} entry. */
    public static Map<String, Object> world(String worldName, String folderName, String environment, boolean loaded,
                                            int playerCount, boolean primary) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("worldName", worldName);
        world.put("folderName", folderName);
        world.put("environment", environment);
        world.put("loaded", loaded);
        world.put("playerCount", playerCount);
        world.put("isPrimary", primary);
        return world;
    }

    private static Integer intField(JsonNode p, String field) {
        JsonNode node = p == null ? null : p.get(field);
        return node != null && node.canConvertToInt() && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static String textField(JsonNode p, String field) {
        JsonNode node = p == null ? null : p.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
