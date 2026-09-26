package net.knightsandkings.knk.core.domain.siege;

import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A scenario team with its identity already resolved server-side (DESIGN §3.4): name, chat colour
 * and banner are the team's own values, else its clan's. Teams sharing an {@code allianceGroup} are
 * allies; every other team is an enemy.
 *
 * @param clanId       the clan the identity came from, or null for an ad-hoc team
 * @param chatColor    Bukkit ChatColor name, e.g. {@code GOLD}
 * @param bannerDesign the resolved banner, or null if the API sent none
 * @param spawnpoints  ordered by (sortOrder, id); the first one is the default spawn
 */
public record KnkSiegeTeam(
        int id,
        int sortOrder,
        SiegeTeamRole role,
        int allianceGroup,
        Integer clanId,
        String name,
        String chatColor,
        KnkBannerDesign bannerDesign,
        String startMessage,
        List<KnkSiegeSpawnpoint> spawnpoints
) {
    private static final Comparator<KnkSiegeSpawnpoint> SPAWN_ORDER =
            Comparator.comparingInt(KnkSiegeSpawnpoint::sortOrder).thenComparingInt(KnkSiegeSpawnpoint::id);

    public KnkSiegeTeam {
        if (role == null) role = SiegeTeamRole.ATTACKER;
        if (chatColor == null || chatColor.isBlank()) chatColor = "WHITE";
        spawnpoints = spawnpoints == null ? List.of() : spawnpoints.stream().sorted(SPAWN_ORDER).toList();
    }

    /** The default spawn (lowest sortOrder), used at match start and as the respawn fallback. */
    public Optional<KnkSiegeSpawnpoint> defaultSpawnpoint() {
        return spawnpoints.stream().findFirst();
    }

    public boolean isDefender() {
        return role == SiegeTeamRole.DEFENDER;
    }
}
