package net.knightsandkings.knk.core.npc.nav;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An NPC's configured access until NPCs are permission holders of their own (plan Phase 2, decision Q3; Phase 5
 * replaces it): which regions it may always or never enter, whether it ignores domain rules and whether it opens
 * doors. An NPC never passes a closed gate door: pass-through for NPCs is later work (decision Q5).
 *
 * @param id                the profile's name (a template's, later)
 * @param bypassDomainRules ignore every domain's entry and exit rule
 * @param openDoors         may open hand-openable doors on local legs
 * @param allowRegions      WorldGuard region ids the NPC may enter and leave whatever the domain says
 * @param denyRegions       region ids the NPC never enters (wins over {@code allowRegions})
 */
public record NpcAccessProfile(String id, boolean bypassDomainRules, boolean openDoors, Set<String> allowRegions,
                               Set<String> denyRegions) {

    /** Follows every domain's rules, opens doors, no lists. */
    public static final NpcAccessProfile DEFAULT = new NpcAccessProfile("default", false, true, Set.of(), Set.of());

    public NpcAccessProfile {
        Objects.requireNonNull(id, "id");
        allowRegions = normalize(allowRegions);
        denyRegions = normalize(denyRegions);
    }

    /** Region ids compare case-insensitively (WorldGuard lower-cases them). */
    public AccessSubject.RegionOverride override(String regionId) {
        if (regionId == null) {
            return AccessSubject.RegionOverride.RULES;
        }
        String id = regionId.toLowerCase(Locale.ROOT);
        if (denyRegions.contains(id)) {
            return AccessSubject.RegionOverride.DENY;
        }
        return allowRegions.contains(id) ? AccessSubject.RegionOverride.ALLOW : AccessSubject.RegionOverride.RULES;
    }

    private static Set<String> normalize(Set<String> ids) {
        return ids == null ? Set.of()
            : ids.stream().filter(Objects::nonNull).map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
