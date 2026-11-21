package net.knightsandkings.knk.core.production.harvest;

import java.util.List;
import java.util.Locale;

/**
 * One enabled harvest rule as the API's catalogue sends it (knk-web-api {@code HarvestRuleDto}, PandiO/knk-workspace#685,
 * PRODUCTION_KINDS § 6.1): what players may break or kill in the areas of its production kind, who may, with which tool,
 * and what happens to the block afterwards (§ 7). Requirement, tool and cooldown are checked here, on the game server;
 * the API only stores them.
 *
 * @param targets           upper-case material or entity type names; one may start or end with {@code *}
 * @param minBlockAge       the lowest crop age that counts (0 = any)
 * @param requiredPermission a permission node the player needs, or null
 * @param minTitleBracketId the lowest title bracket the player needs, or null
 * @param cooldownSeconds   per player and area (0 = none)
 * @param placeholderMaterial the depleted block, or null for the world's default (P-D7)
 * @param regenSeconds      when the block comes back before the API's answer (the answer's value wins)
 */
public record HarvestRule(int id, String name, int productionKindId, TargetKind targetKind, List<String> targets,
                          int minBlockAge, String requiredPermission, Integer minTitleBracketId, ToolClass toolClass,
                          ToolTier minToolTier, int cooldownSeconds, int yieldItemBlueprintId, Placeholder placeholder,
                          String placeholderMaterial, int regenSeconds) {

    public enum TargetKind { BLOCK, ENTITY }

    /** DepletedBlock, Replant, Air, Sapling or None (§ 7). */
    public enum Placeholder { DEPLETED_BLOCK, REPLANT, AIR, SAPLING, NONE }

    public enum ToolClass { ANY, PICKAXE, AXE, SHOVEL, HOE, SHEARS, SWORD, FISHING_ROD }

    /** In the API's order; copper sits between stone and iron (its mining level). */
    public enum ToolTier { ANY, WOOD, STONE, COPPER, IRON, GOLD, DIAMOND, NETHERITE }

    public HarvestRule {
        targets = targets == null ? List.of() : targets.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.trim().toUpperCase(Locale.ROOT))
                .toList();
        targetKind = targetKind == null ? TargetKind.BLOCK : targetKind;
        toolClass = toolClass == null ? ToolClass.ANY : toolClass;
        minToolTier = minToolTier == null ? ToolTier.ANY : minToolTier;
        placeholder = placeholder == null ? Placeholder.NONE : placeholder;
        minBlockAge = Math.max(0, minBlockAge);
        cooldownSeconds = Math.max(0, cooldownSeconds);
        regenSeconds = Math.max(0, regenSeconds);
        requiredPermission = requiredPermission == null || requiredPermission.isBlank() ? null : requiredPermission.trim();
        minTitleBracketId = minTitleBracketId == null || minTitleBracketId <= 0 ? null : minTitleBracketId;
        placeholderMaterial = placeholderMaterial == null || placeholderMaterial.isBlank() ? null
                : placeholderMaterial.trim().toUpperCase(Locale.ROOT);
    }

    /** Whether {@code name} (a material or entity type name) is one of the targets. */
    public boolean targets(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toUpperCase(Locale.ROOT);
        for (String target : targets) {
            if (matches(target, n)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String target, String name) {
        if (target.equals("*")) {
            return true;
        }
        boolean head = target.startsWith("*");
        boolean tail = target.endsWith("*");
        String core = target.substring(head ? 1 : 0, target.length() - (tail && target.length() > 1 ? 1 : 0));
        if (head && tail) {
            return name.contains(core);
        }
        if (head) {
            return name.endsWith(core);
        }
        if (tail) {
            return name.startsWith(core);
        }
        return name.equals(target);
    }

    /** Parses an API enum name ({@code DepletedBlock}, {@code FishingRod}) into {@code type}, or {@code fallback}. */
    public static <E extends Enum<E>> E parse(Class<E> type, String apiName, E fallback) {
        if (apiName == null || apiName.isBlank()) {
            return fallback;
        }
        String snake = apiName.trim().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, snake);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** A short label for logs and staff messages. */
    public String label() {
        return "#" + id + (name == null || name.isBlank() ? "" : " " + name);
    }
}
