package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;
import java.util.Set;

/**
 * What stands between an NPC and its goal under the current rules (implementation plan Phase 3; BEHAVIOR_MODEL
 * NAV-03, NAV-04): the first blocked element on the route the NPC would take without the rules. It travels in
 * {@link NavigationResult.Blocked}, so behavior can decide what to do at a closed gate or a district edge.
 *
 * @param kind        a gate, a domain's region, a closed road, or unknown
 * @param id          the gate door id, the domain id ({@code -1} when the NPC's own deny list blocks) or the flag
 *                    name, as text
 * @param name        what to call it in logs ("the West Gate", "Kardenna Castle")
 * @param gateDoorIds the gate doors of the blocked road stretch
 * @param regionIds   the WorldGuard regions of the blocked road stretch
 */
public record BlockingElement(Kind kind, String id, String name, Set<Integer> gateDoorIds, Set<String> regionIds) {

    public enum Kind {
        GATE,
        /** A domain's entry or exit rule, or a region on the NPC's deny list. */
        DOMAIN,
        /** A road closed by a static flag. */
        ROAD,
        UNKNOWN
    }

    public BlockingElement {
        Objects.requireNonNull(kind, "kind");
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        gateDoorIds = gateDoorIds == null ? Set.of() : Set.copyOf(gateDoorIds);
        regionIds = regionIds == null ? Set.of() : Set.copyOf(regionIds);
    }

    public static BlockingElement gate(int doorId, String name) {
        return new BlockingElement(Kind.GATE, Integer.toString(doorId), name, Set.of(doorId), Set.of());
    }

    public static BlockingElement unknown(String name) {
        return new BlockingElement(Kind.UNKNOWN, "", name, Set.of(), Set.of());
    }

    /** Whether this gate door is the blocking element or part of the blocked stretch. */
    public boolean hasGate(int doorId) {
        return gateDoorIds.contains(doorId);
    }

    /** For logs: {@code gate the West Gate (12)}. */
    public String describe() {
        String what = kind.name().toLowerCase(java.util.Locale.ROOT);
        return id.isEmpty() || id.equals(name) ? what + " " + name : what + " " + name + " (" + id + ")";
    }
}
