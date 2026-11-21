package net.knightsandkings.knk.core.production.harvest;

import java.util.Locale;

import net.knightsandkings.knk.core.production.harvest.HarvestRule.ToolClass;
import net.knightsandkings.knk.core.production.harvest.HarvestRule.ToolTier;

/** A held item's tool class and tier from its material name ({@code IRON_PICKAXE}), for a rule's tool check. */
public final class HarvestTools {

    private HarvestTools() {
    }

    /** The tool class of a material, or {@link ToolClass#ANY} for something that isn't a tool (a hand, a block). */
    public static ToolClass classOf(String material) {
        if (material == null) {
            return ToolClass.ANY;
        }
        String m = material.toUpperCase(Locale.ROOT);
        if (m.equals("SHEARS")) {
            return ToolClass.SHEARS;
        }
        if (m.equals("FISHING_ROD")) {
            return ToolClass.FISHING_ROD;
        }
        if (m.endsWith("_PICKAXE")) {
            return ToolClass.PICKAXE;
        }
        if (m.endsWith("_AXE")) {
            return ToolClass.AXE;
        }
        if (m.endsWith("_SHOVEL")) {
            return ToolClass.SHOVEL;
        }
        if (m.endsWith("_HOE")) {
            return ToolClass.HOE;
        }
        if (m.endsWith("_SWORD")) {
            return ToolClass.SWORD;
        }
        return ToolClass.ANY;
    }

    /** The tier of a tiered tool, or {@link ToolTier#ANY} (no tier: shears, a rod, a hand). */
    public static ToolTier tierOf(String material) {
        if (material == null) {
            return ToolTier.ANY;
        }
        String m = material.toUpperCase(Locale.ROOT);
        if (m.startsWith("WOODEN_")) {
            return ToolTier.WOOD;
        }
        if (m.startsWith("STONE_")) {
            return ToolTier.STONE;
        }
        if (m.startsWith("COPPER_")) {
            return ToolTier.COPPER;
        }
        if (m.startsWith("IRON_")) {
            return ToolTier.IRON;
        }
        if (m.startsWith("GOLDEN_")) {
            return ToolTier.GOLD;
        }
        if (m.startsWith("DIAMOND_")) {
            return ToolTier.DIAMOND;
        }
        if (m.startsWith("NETHERITE_")) {
            return ToolTier.NETHERITE;
        }
        return ToolTier.ANY;
    }

    /** Why {@code material} doesn't do for {@code rule}, or null when it does. */
    public static String unmet(HarvestRule rule, String material) {
        ToolClass wanted = rule.toolClass();
        if (wanted != ToolClass.ANY && classOf(material) != wanted) {
            String a = article(wanted);
            return "needs " + (a.isEmpty() ? "" : a + " ") + words(wanted.name());
        }
        ToolTier tier = rule.minToolTier();
        if (tier != ToolTier.ANY && tierOf(material).ordinal() < tier.ordinal()) {
            return "needs a " + words(tier.name()) + " tool or better";
        }
        return null;
    }

    private static String article(ToolClass tool) {
        return tool == ToolClass.AXE ? "an" : tool == ToolClass.SHEARS ? "" : "a";
    }

    static String words(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
