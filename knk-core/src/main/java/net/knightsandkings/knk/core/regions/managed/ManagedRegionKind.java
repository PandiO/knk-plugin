package net.knightsandkings.knk.core.regions.managed;

import java.util.Locale;
import java.util.Optional;

/**
 * The categories of WorldGuard region Knights and Kings manages, and each one's priority floor. The v1 -> v3 mapping,
 * the overlap rules and the unresolved differences are documented in
 * {@code knk-workspace/docs/architecture/managed-worldguard-regions.md}; the parent + priority rules live in
 * {@link ManagedRegionPolicy}.
 * <p>
 * Higher priority wins where regions overlap without a parent link; a child region falls back to its parent's flags for
 * flags it leaves unset. The floors leave gaps so a category can be slotted in later without renumbering.
 */
public enum ManagedRegionKind {
    /** WorldGuard's {@code __global__} region: opt-in (see {@link ManagedRegionPolicy.Options#manageGlobalRegion()}). */
    GLOBAL(0),
    /** v1 {@code town_N} base region -> v3 {@code Town}. */
    TOWN(10),
    /** v1 town child regions (68 unflagged children of 5 towns) -> v3 {@code District}: inherits from its Town. */
    DISTRICT(20),
    /** A v3 {@code Structure} whose subtype has no v1 flag category (Shop, Warehouse, Keep, ...): hierarchy only. */
    STRUCTURE(30),
    /** v1 {@code house_N} -> v3 House structure (opt-in via an override until Structure gets a subtype). */
    HOUSE(30),
    /** v1 {@code property_N} -> v3 Resource-Property structure (opt-in via an override). */
    PROPERTY(30),
    /** The {@code property_47} exception: a {@link #PROPERTY} that is a resource production structure (wood farm). */
    RESOURCE_PRODUCTION(30),
    /** v3-only: {@code GateStructure}. v1 had no gate region category; hierarchy only. */
    GATE(30),
    /** v1 {@code room_N}: a sub-unit inside a house, so it sits above the structure tier. */
    ROOM(40),
    /** v1 {@code arena_N} base region (no v3 domain entity: config-declared only). */
    ARENA(40),
    /** v1 {@code arena_N-battleground...}: the PvP-allowed part of an arena; must beat the town's {@code pvp deny}. */
    BATTLEGROUND(50);

    private final int floorPriority;

    ManagedRegionKind(int floorPriority) {
        this.floorPriority = floorPriority;
    }

    /** The lowest priority a region of this kind gets, whatever its parent's priority is. */
    public int floorPriority() {
        return floorPriority;
    }

    /** Whether this is a Structure-tier kind, i.e. a v3 {@code Structure} (sub)type whose parent is a District. */
    public boolean isStructureTier() {
        return this == STRUCTURE || this == HOUSE || this == PROPERTY || this == RESOURCE_PRODUCTION || this == GATE;
    }

    /**
     * The kind for a concrete v3 {@code Domain} subtype name (as the API reports it in {@code domainType}, any case).
     * Unknown Structure subtypes are plain {@link #STRUCTURE}s, never dropped: they still need their parent and priority.
     * Empty only for a null/blank name.
     */
    public static Optional<ManagedRegionKind> fromDomainType(String domainType) {
        if (domainType == null || domainType.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(switch (normalise(domainType)) {
            case "TOWN" -> TOWN;
            case "DISTRICT" -> DISTRICT;
            case "GATE", "GATESTRUCTURE" -> GATE;
            case "HOUSE" -> HOUSE;
            case "PROPERTY", "RESOURCEPROPERTY" -> PROPERTY;
            default -> STRUCTURE;
        });
    }

    /** Parses a kind name from config ({@code resource_production}, {@code Resource-Production}, ...). */
    public static Optional<ManagedRegionKind> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = normalise(name);
        for (ManagedRegionKind kind : values()) {
            if (kind.name().replace("_", "").equals(wanted)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static String normalise(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }
}
