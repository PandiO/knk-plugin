package net.knightsandkings.knk.core.domain.clan;

/**
 * Minimal clan identity (Siege Phase 1, docs/specs/siege-minigame/DESIGN.md §3.2): name,
 * chat colour and banner. No membership yet.
 *
 * @param chatColor        Bukkit ChatColor name, e.g. {@code GOLD}
 * @param bannerDesign     the full banner, or null if the API didn't include it
 * @param defaultForTownId the town this is the default clan of, or null
 */
public record KnkClan(
        int id,
        String name,
        boolean npc,
        String chatColor,
        int bannerDesignId,
        KnkBannerDesign bannerDesign,
        Integer defaultForTownId,
        String defaultForTownName
) {
}
