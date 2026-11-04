package net.knightsandkings.knk.core.domain.item;

/**
 * An armour piece's trim as an item blueprint or item instance stores it (PandiO/knk-workspace#232): the
 * smithing-template pattern and the trim material, both Bukkit registry keys. Mirrors knk-web-api's
 * {@code ArmorTrimPattern} + {@code ArmorTrimMaterial}; the API only stores both or neither.
 *
 * @param pattern  trim pattern key, e.g. {@code minecraft:silence}
 * @param material trim material key, e.g. {@code minecraft:netherite}
 */
public record KnkArmorTrim(String pattern, String material) {

    /** Both keys present (the API never stores half a trim, but a hand-edited row might). */
    public boolean isComplete() {
        return pattern != null && !pattern.isBlank() && material != null && !material.isBlank();
    }
}
