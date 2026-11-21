package net.knightsandkings.knk.core.production.harvest;

import java.util.List;
import java.util.UUID;

/**
 * Decides one block break or entity kill in the yield areas (PRODUCTION_KINDS § 6.1, PandiO/knk-workspace#134), on the
 * main thread from caches only:
 * <ol>
 *   <li>{@link Kind#IGNORE}: not a harvest (no catalogue yet, the API's flag off, outside every area, or not a target of
 *       any rule of the area's kind). The break is left to WorldGuard and vanilla, as without harvesting;</li>
 *   <li>{@link Kind#ALLOW}: a target of a rule whose conditions the player meets (crop age, requirement, tool, cooldown,
 *       the structure's production on, the pool not exhausted when {@code deny-at-empty-pool}). The rule's drops replace
 *       the vanilla ones and the block regenerates;</li>
 *   <li>{@link Kind#DENY}: a target the player may not harvest (with the first unmet condition as the reason), or a block
 *       waiting to regenerate.</li>
 * </ol>
 * Shared areas can overlap: the areas are tried lowest id first, and the first rule the player meets wins.
 */
public final class HarvestGate {

    public enum Kind { IGNORE, ALLOW, DENY }

    public record Decision(Kind kind, HarvestRule rule, HarvestArea area, String reason) {

        public static final Decision IGNORE = new Decision(Kind.IGNORE, null, null, null);

        static Decision deny(HarvestRule rule, HarvestArea area, String reason) {
            return new Decision(Kind.DENY, rule, area, reason);
        }
    }

    /** What the player holds. {@code material} null = an empty hand. */
    public record Tool(String material, int fortuneLevel) {
        public static final Tool HAND = new Tool(null, 0);
    }

    /** Positions held by a regeneration job (thread-safe). */
    @FunctionalInterface
    public interface Regenerating {
        boolean at(String world, int x, int y, int z);

        Regenerating NONE = (world, x, y, z) -> false;
    }

    private final HarvestCatalog catalog;
    private final HarvestAreaIndex index;
    private final RequirementEvaluator requirements;
    private final HarvestCooldowns cooldowns;
    private final Regenerating regenerating;
    private final boolean denyAtEmptyPool;
    private final java.util.function.ToDoubleFunction<HarvestArea> poolFill;

    public HarvestGate(HarvestCatalog catalog, HarvestAreaIndex index, RequirementEvaluator requirements,
                       HarvestCooldowns cooldowns, Regenerating regenerating, boolean denyAtEmptyPool) {
        this(catalog, index, requirements, cooldowns, regenerating, denyAtEmptyPool, HarvestArea::poolFill);
    }

    /** @param poolFill an area's fill now (the latest report answer), else the catalogue's */
    public HarvestGate(HarvestCatalog catalog, HarvestAreaIndex index, RequirementEvaluator requirements,
                       HarvestCooldowns cooldowns, Regenerating regenerating, boolean denyAtEmptyPool,
                       java.util.function.ToDoubleFunction<HarvestArea> poolFill) {
        this.poolFill = poolFill == null ? HarvestArea::poolFill : poolFill;
        this.catalog = catalog == null ? HarvestCatalog.EMPTY : catalog;
        this.index = index == null ? HarvestAreaIndex.EMPTY : index;
        this.requirements = requirements == null ? RequirementEvaluator.ALWAYS : requirements;
        this.cooldowns = cooldowns == null ? new HarvestCooldowns() : cooldowns;
        this.regenerating = regenerating == null ? Regenerating.NONE : regenerating;
        this.denyAtEmptyPool = denyAtEmptyPool;
    }

    public HarvestCatalog catalog() {
        return catalog;
    }

    public HarvestAreaIndex index() {
        return index;
    }

    /** A block break at (x, y, z) of {@code world}; {@code blockData} as Bukkit writes it ({@code minecraft:wheat[age=7]}). */
    public Decision block(UUID player, String world, int x, int y, int z, String blockData, Tool tool, long nowMillis) {
        if (regenerating.at(world, x, y, z)) {
            return Decision.deny(null, null, "this block is regenerating");
        }
        String material = BlockStates.material(blockData);
        return decide(player, HarvestRule.TargetKind.BLOCK, material, BlockStates.age(blockData), world, x, y, z, tool,
                nowMillis);
    }

    /** A kill of an entity of {@code entityType} (its Bukkit name, {@code COW}) standing at (x, y, z). */
    public Decision entity(UUID player, String world, int x, int y, int z, String entityType, Tool tool, long nowMillis) {
        return decide(player, HarvestRule.TargetKind.ENTITY, entityType, -1, world, x, y, z, tool, nowMillis);
    }

    private Decision decide(UUID player, HarvestRule.TargetKind kind, String name, int age, String world, int x, int y,
                            int z, Tool tool, long nowMillis) {
        if (!catalog.featureEnabled() || name == null || !catalog.anyRuleTargets(kind, name)) {
            return Decision.IGNORE;
        }
        Decision firstDenial = null;
        for (HarvestAreaIndex.Entry entry : index.at(world, x, y, z)) {
            HarvestArea area = entry.area();
            List<HarvestRule> rules = catalog.rulesFor(area.productionKindId(), kind, name);
            for (HarvestRule rule : rules) {
                String reason = unmet(player, rule, area, age, tool == null ? Tool.HAND : tool, nowMillis);
                if (reason == null) {
                    return new Decision(Kind.ALLOW, rule, area, null);
                }
                if (firstDenial == null) {
                    firstDenial = Decision.deny(rule, area, reason);
                }
            }
        }
        return firstDenial == null ? Decision.IGNORE : firstDenial;
    }

    private String unmet(UUID player, HarvestRule rule, HarvestArea area, int age, Tool tool, long nowMillis) {
        if (!area.productionEnabled()) {
            return "production at " + structureName(area) + " is switched off";
        }
        if (rule.minBlockAge() > 0 && age >= 0 && age < rule.minBlockAge()) {
            return "it isn't ripe yet";
        }
        String requirement = requirements.unmet(player,
                new RequirementEvaluator.Requirement(rule.requiredPermission(), rule.minTitleBracketId()));
        if (requirement != null) {
            return requirement;
        }
        String toolReason = HarvestTools.unmet(rule, tool.material());
        if (toolReason != null) {
            return toolReason;
        }
        long wait = cooldowns.remaining(player, area.areaId(), rule, nowMillis);
        if (wait > 0) {
            return "wait " + Math.max(1, (wait + 999) / 1000) + " s";
        }
        if (denyAtEmptyPool && poolFill.applyAsDouble(area) <= 0) {
            return structureName(area) + " is exhausted for now; come back later";
        }
        return null;
    }

    private static String structureName(HarvestArea area) {
        String name = area.spec().structureName();
        return name == null || name.isBlank() ? "structure #" + area.structureId() : name;
    }
}
