package net.knightsandkings.knk.core.regions.managed;

import java.util.List;

/**
 * The single source of truth for what a managed region looks like: its priority relative to its parent and the flags
 * its category carries. Region creation (after a world task finalizes a region) and the startup repair both go through
 * {@link ManagedRegionReconciler}, which asks this class - so a new region and a repaired one cannot drift apart.
 * <h3>Priority</h3>
 * {@code max(kind floor, parent priority + }{@link #PRIORITY_STEP}{@code )}, or just the floor when there is no
 * (resolvable) parent. So a region nested one level deeper than its category normally is (a Room under a House) ranks
 * above it, and a region whose parent is missing degrades to its floor instead of failing. The priority is therefore a
 * function of the region's actual place in the hierarchy, not a per-category constant. Exact v1 numbers were not
 * derivable (the v1 {@code regions.yml} was not available); a {@link RegionOverride} records any that matter.
 * <h3>Flags</h3>
 * Only flags the v1 inventory shows as category-wide are set. Greetings/farewells, rank-dependent entry text and
 * {@code allow-blocks} are deliberately not here (see the architecture doc).
 */
public final class ManagedRegionPolicy {

    /** Gap between a parent's priority and its child's, so two levels never tie. */
    public static final int PRIORITY_STEP = 10;

    /**
     * @param resourceProductionBlockBreak set {@code block-break allow} on {@link ManagedRegionKind#RESOURCE_PRODUCTION}
     *        regions. v1 paired it with a custom {@code allow-blocks: LOG;} flag that WorldGuard does not have, so alone it
     *        would let anyone break every block in the plot: off by default until a v3 block filter exists.
     * @param manageGlobalRegion apply the v1 {@code __global__} flags ({@code build deny}, ...): off by default because
     *        it changes what is buildable across the whole world.
     */
    public record Options(boolean resourceProductionBlockBreak, boolean manageGlobalRegion) {
        public static Options defaults() {
            return new Options(false, false);
        }
    }

    private static final FlagRule NO_MESSAGE = FlagRule.seed("deny-message", "");
    private static final FlagRule NO_ENTRY_MESSAGE = FlagRule.seed("entry-deny-message", "");

    private final Options options;

    public ManagedRegionPolicy() {
        this(Options.defaults());
    }

    public ManagedRegionPolicy(Options options) {
        this.options = options != null ? options : Options.defaults();
    }

    public Options options() {
        return options;
    }

    /** The priority for a region of {@code kind}; {@code parentPriority} is null when there is no resolvable parent. */
    public int priorityFor(ManagedRegionKind kind, Integer parentPriority) {
        if (parentPriority == null) {
            return kind.floorPriority();
        }
        return Math.max(kind.floorPriority(), parentPriority + PRIORITY_STEP);
    }

    /** The category flags for {@code kind} (v1 inventory, "WorldGuard region flags by category"). */
    public List<FlagRule> flagRules(ManagedRegionKind kind) {
        return switch (kind) {
            case GLOBAL -> options.manageGlobalRegion()
                    ? List.of(FlagRule.seed("build", FlagState.DENY), FlagRule.seed("damage-animals", FlagState.ALLOW),
                            FlagRule.seed("pvp", FlagState.ALLOW), NO_MESSAGE)
                    : List.of();
            // Not managed on Towns: greeting/farewell (v3 sends its own transition messages), entry and the rank
            // wording of entry-deny-message (v3 gates entry through Domain.AllowEntry + the plugin, not the flag).
            // pvp is SEED so `/rg flag <town> pvp allow` stays a working override (KNG-11's CombatSafezone).
            case TOWN -> List.of(FlagRule.seed("damage-animals", FlagState.ALLOW),
                    FlagRule.seed("entity-item-frame-destroy", FlagState.DENY),
                    FlagRule.seed("mob-spawning", FlagState.DENY), FlagRule.seed("pvp", FlagState.DENY), NO_MESSAGE);
            case HOUSE, ROOM -> List.of(FlagRule.seed("entry", FlagState.DENY), NO_ENTRY_MESSAGE,
                    FlagRule.seed("feed-amount", 20), FlagRule.seed("feed-delay", 1));
            case PROPERTY -> List.of(FlagRule.seed("entry", FlagState.ALLOW), NO_ENTRY_MESSAGE);
            case RESOURCE_PRODUCTION -> options.resourceProductionBlockBreak()
                    ? List.of(FlagRule.seed("entry", FlagState.ALLOW), NO_ENTRY_MESSAGE,
                            FlagRule.seed("block-break", FlagState.ALLOW))
                    : List.of(FlagRule.seed("entry", FlagState.ALLOW), NO_ENTRY_MESSAGE);
            case ARENA -> List.of(FlagRule.seed("entry", FlagState.ALLOW), NO_ENTRY_MESSAGE);
            // The exception that defines the category: fighting is allowed here whatever the town says.
            case BATTLEGROUND -> List.of(FlagRule.enforce("entry", FlagState.DENY), FlagRule.enforce("pvp", FlagState.ALLOW));
            case DISTRICT, STRUCTURE, GATE -> List.of();
        };
    }
}
