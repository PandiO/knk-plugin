package net.knightsandkings.knk.core.domain.clan;

/**
 * One pattern layer of a {@link KnkBannerDesign} (Siege Phase 1,
 * docs/specs/siege-minigame/DESIGN.md §3.1).
 *
 * @param patternKey banner-pattern registry key, e.g. {@code minecraft:stripe_top}
 * @param color      dye colour name, e.g. {@code RED} (org.bukkit.DyeColor name)
 */
public record KnkBannerLayer(int id, int sortOrder, String patternKey, String color) {
}
