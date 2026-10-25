package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;
import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownSummary;

/**
 * Builds the {@link ManagedRegionSpec}s implied by the v3 domain data: Town -> District -> Structure, each backed by the
 * WorldGuard region in its {@code wgRegionId}. A Structure's subtype (Gate, Arena, ...) comes from {@code domainTypes},
 * the {@code domainType} the API reports per domain id. An Arena's areas (arena duels rev. 6: battlegrounds, spectator
 * areas, the lobby; not domains) come from the arenas list: one spec per area region, under the Arena's region.
 */
public final class DomainRegionSpecs {

    /** A domain that cannot be managed (no region id): counted as skipped, with why. */
    public record Skipped(String label, String reason) {
    }

    public record Result(List<ManagedRegionSpec> specs, List<Skipped> skipped, List<String> warnings) {
    }

    private DomainRegionSpecs() {
    }

    public static Result build(List<TownSummary> towns, List<DistrictSummary> districts, List<StructureSummary> structures,
                               Map<Integer, String> domainTypes) {
        return build(towns, districts, structures, domainTypes, List.of());
    }

    public static Result build(List<TownSummary> towns, List<DistrictSummary> districts, List<StructureSummary> structures,
                               Map<Integer, String> domainTypes, List<KnkArena> arenas) {
        List<ManagedRegionSpec> specs = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, String> townRegions = new HashMap<>();
        Map<Integer, String> districtRegions = new HashMap<>();
        // KNG-112: each region in its domain's world; a domain without one yet takes its parent's (a parent never
        // crosses worlds), else stays world-less and is looked up in the loaded worlds in turn
        Map<Integer, String> townWorlds = new HashMap<>();
        Map<Integer, String> districtWorlds = new HashMap<>();

        for (TownSummary town : nullSafe(towns)) {
            String label = "Town#" + town.id() + " '" + town.name() + "'";
            if (isBlank(town.wgRegionId())) {
                skipped.add(new Skipped(label, "no wgRegionId"));
                continue;
            }
            townRegions.put(town.id(), town.wgRegionId());
            townWorlds.put(town.id(), town.worldName());
            specs.add(new ManagedRegionSpec(town.wgRegionId(), ManagedRegionKind.TOWN, null, label, town.worldName()));
        }
        for (DistrictSummary district : nullSafe(districts)) {
            String label = "District#" + district.id() + " '" + district.name() + "'";
            if (isBlank(district.wgRegionId())) {
                skipped.add(new Skipped(label, "no wgRegionId"));
                continue;
            }
            districtRegions.put(district.id(), district.wgRegionId());
            String world = firstNonBlank(district.worldName(), district.townId() != null ? townWorlds.get(district.townId()) : null);
            districtWorlds.put(district.id(), world);
            String parent = district.townId() != null ? townRegions.get(district.townId()) : null;
            if (parent == null) {
                warnings.add(label + ": its Town (id " + district.townId() + ") is unknown or has no region; no parent set");
            }
            specs.add(new ManagedRegionSpec(district.wgRegionId(), ManagedRegionKind.DISTRICT, parent, label, world));
        }
        for (StructureSummary structure : nullSafe(structures)) {
            String type = domainTypes != null ? domainTypes.get(structure.id()) : null;
            String label = (type != null ? type : "Structure") + "#" + structure.id() + " '" + structure.name() + "'";
            if (isBlank(structure.wgRegionId())) {
                skipped.add(new Skipped(label, "no wgRegionId"));
                continue;
            }
            String parent = structure.districtId() != null ? districtRegions.get(structure.districtId()) : null;
            if (parent == null) {
                warnings.add(label + ": its District (id " + structure.districtId() + ") is unknown or has no region; no parent set");
            }
            // An Arena is a Structure subtype with its own kind (KNG-210).
            ManagedRegionKind kind = ManagedRegionKind.fromDomainType(type)
                    .filter(k -> k.isStructureTier() || k == ManagedRegionKind.ARENA)
                    .orElse(ManagedRegionKind.STRUCTURE);
            String world = firstNonBlank(structure.worldName(),
                    structure.districtId() != null ? districtWorlds.get(structure.districtId()) : null);
            specs.add(new ManagedRegionSpec(structure.wgRegionId(), kind, parent, label, world));
        }
        for (KnkArena arena : nullSafe(arenas)) {
            String arenaLabel = "Arena#" + arena.id() + " '" + arena.name() + "'";
            if (isBlank(arena.wgRegionId()) && !arena.areas().isEmpty()) {
                warnings.add(arenaLabel + ": no region; its areas' regions get no parent");
            }
            String parent = isBlank(arena.wgRegionId()) ? null : arena.wgRegionId();
            for (KnkArenaArea area : arena.areas()) {
                ManagedRegionKind kind = kindOf(area.kind());
                for (KnkArenaAreaRegion region : area.regions()) {
                    String label = "ArenaArea#" + area.id() + " " + area.kind() + " '" + area.displayName() + "' region#" + region.id();
                    if (isBlank(region.wgRegionId())) {
                        skipped.add(new Skipped(label, "no wgRegionId"));
                        continue;
                    }
                    specs.add(new ManagedRegionSpec(region.wgRegionId(), kind, parent, label,
                            firstNonBlank(region.worldName(), arena.worldName())));
                }
            }
        }
        return new Result(specs, skipped, warnings);
    }

    /** The managed kind of an arena area's regions (rev. 6). */
    public static ManagedRegionKind kindOf(KnkArenaArea.Kind kind) {
        return switch (kind) {
            case BATTLEGROUND -> ManagedRegionKind.BATTLEGROUND;
            case SPECTATOR -> ManagedRegionKind.SPECTATOR_AREA;
            case LOBBY -> ManagedRegionKind.LOBBY;
        };
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : !isBlank(second) ? second : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
