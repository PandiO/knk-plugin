package net.knightsandkings.knk.paper.npc;

import com.destroystokyo.paper.entity.RangedEntity;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.knightsandkings.knk.core.npc.ClampReport;
import net.knightsandkings.knk.core.npc.NpcStatPipeline;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.ResolvedSpeed;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * One {@link NpcStatPipeline} per plugin plus the Paper glue around it (NPC platform §5b): takes over a vanilla mob
 * (strips its vanilla movement and targeting goals, installs the platform's melee and bow goals), applies the resolved
 * movement speed as an attribute modifier and re-applies it when the actor's modifier revision changes, and logs
 * every clamp report once per revision. The profile id is the lower-cased entity type unless the consumer picks one.
 *
 * <p>Main thread. Nothing happens to a mob that was not {@link #adopt adopted}.
 */
public final class NpcPlatform {

    public static final String SPEED_MODIFIER_KEY = "npc_speed";
    public static final String MELEE_MODIFIER_KEY = "npc_melee_damage";
    /** Follow range the adopted mob gets so its own pathfinder can reach castle-scale destinations (default). */
    public static final double DEFAULT_FOLLOW_RANGE = 96.0;

    private final Plugin plugin;
    private final NpcStatPipeline pipeline;
    private final MobAttributes attributes;
    private final MobGoals goals;
    private final LongSupplier currentTick;
    private final Logger logger;
    private final NamespacedKey speedKey;
    private final NamespacedKey meleeKey;
    private final double followRange;
    private final Map<UUID, Adopted> adopted = new ConcurrentHashMap<>();

    /** Per-mob state the goals read. */
    public static final class Adopted {
        private final String profileId;
        private volatile long appliedRevision = -1;
        private volatile long reportedRevision = -1;

        Adopted(String profileId) {
            this.profileId = profileId;
        }

        public String profileId() {
            return profileId;
        }

        public long appliedRevision() {
            return appliedRevision;
        }
    }

    public NpcPlatform(Plugin plugin, NpcStatPipeline pipeline, MobAttributes attributes, MobGoals goals,
                       LongSupplier currentTick, double followRange) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        this.goals = goals;
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
        this.logger = plugin.getLogger();
        this.speedKey = new NamespacedKey(plugin, SPEED_MODIFIER_KEY);
        this.meleeKey = new NamespacedKey(plugin, MELEE_MODIFIER_KEY);
        this.followRange = followRange;
    }

    public NpcStatPipeline pipeline() {
        return pipeline;
    }

    public MobAttributes attributes() {
        return attributes;
    }

    public long currentTick() {
        return currentTick.getAsLong();
    }

    /** The profile id the platform uses for a mob: the lower-cased entity type key. */
    public static String profileIdOf(Mob mob) {
        return mob.getType().getKey().getKey().toLowerCase(Locale.ROOT);
    }

    /**
     * Takes over the mob: vanilla MOVE and TARGET goals go (the consumer's behaviour owns movement and targeting;
     * checks report §4: the melee/bow goals are replaced, §8: wandering and vanilla targeting would fight the mover),
     * the platform's melee goal (and the bow goal for a ranged mob) comes in, the follow range is raised and the
     * resolved speed applied. Idempotent per mob.
     */
    public Adopted adopt(Mob mob, String profileId) {
        Objects.requireNonNull(mob, "mob");
        String id = profileId == null || profileId.isBlank() ? profileIdOf(mob) : profileId;
        Adopted state = adopted.computeIfAbsent(mob.getUniqueId(), k -> new Adopted(id));
        installGoals(mob);
        attributes.setAddModifier(mob, Attribute.FOLLOW_RANGE, new NamespacedKey(plugin, "npc_follow_range"),
            Math.max(0, followRange - attributes.base(mob, Attribute.FOLLOW_RANGE, 16)));
        applySpeed(mob, state, true);
        return state;
    }

    /**
     * Re-installs the platform goals when a vanilla attack goal reappeared (vanilla {@code reassessWeaponGoal} re-adds
     * the melee/bow goals on an equipment change, checks report §4). Cheap: a {@code hasGoal} check per call.
     */
    public void installGoals(Mob mob) {
        if (goals == null) return;
        boolean vanillaBack = (mob instanceof org.bukkit.entity.Creature creature && goals.hasGoal(creature, VanillaGoal.MELEE_ATTACK))
            || (mob instanceof org.bukkit.entity.Monster monster && goals.hasGoal(monster, VanillaGoal.RANGED_BOW_ATTACK))
            || (mob instanceof org.bukkit.entity.AbstractSkeleton skeleton && goals.hasGoal(skeleton, VanillaGoal.SKELETON_MELEE))
            || goals.hasGoal(mob, VanillaGoal.NEAREST_ATTACKABLE);
        boolean oursMissing = !goals.hasGoal(mob, NpcMeleeGoal.KEY);
        if (!vanillaBack && !oursMissing) return;
        goals.removeAllGoals(mob, GoalType.MOVE);
        goals.removeAllGoals(mob, GoalType.TARGET);
        goals.removeGoal(mob, VanillaGoal.LOOK_AT_PLAYER);
        goals.removeGoal(mob, VanillaGoal.RANDOM_LOOK_AROUND);
        if (!goals.hasGoal(mob, NpcMeleeGoal.KEY)) goals.addGoal(mob, 1, new NpcMeleeGoal(this, mob));
        if (mob instanceof RangedEntity ranged && !goals.hasGoal(mob, NpcBowGoal.KEY)) {
            goals.addGoal(mob, 0, new NpcBowGoal(this, ranged));
        }
    }

    public boolean isAdopted(UUID mobId) {
        return adopted.containsKey(mobId);
    }

    public Adopted stateOf(UUID mobId) {
        return adopted.get(mobId);
    }

    /** The resolved combat stats of an adopted mob against {@code targetType} with the live engine bases. */
    public ResolvedCombat resolveCombat(Mob mob, String targetType, VanillaBases bases) {
        Adopted state = adopted.get(mob.getUniqueId());
        String profile = state == null ? profileIdOf(mob) : state.profileId;
        ResolvedCombat combat = pipeline.resolveCombat(mob.getUniqueId(), profile, targetType, bases);
        reportOnce(mob, state, combat.reports());
        return combat;
    }

    /** Whether the melee channel fires now (arms it when it does). */
    public boolean tryMelee(Mob mob, ResolvedCombat combat) {
        return pipeline.tryMelee(mob.getUniqueId(), currentTick.getAsLong(), combat);
    }

    public boolean tryShot(Mob mob, ResolvedCombat combat) {
        return pipeline.tryShot(mob.getUniqueId(), currentTick.getAsLong(), combat);
    }

    /**
     * Sets the mob's ATTACK_DAMAGE so the vanilla attack deals the resolved melee damage (the attack keeps its
     * knockback, enchantments and effects): a transient ADD_NUMBER modifier of (resolved − base).
     */
    public void applyMeleeDamage(Mob mob, ResolvedCombat combat) {
        double base = attributes.base(mob, Attribute.ATTACK_DAMAGE, 0);
        attributes.setAddModifier(mob, Attribute.ATTACK_DAMAGE, meleeKey, combat.meleeDamageValue() - base);
    }

    /** The vanilla melee damage base of a mob (its ATTACK_DAMAGE base, 0 when it has none). */
    public double vanillaMeleeDamage(Mob mob) {
        return attributes.base(mob, Attribute.ATTACK_DAMAGE, 0);
    }

    /**
     * Applies the resolved movement speed as a transient ADD_NUMBER modifier of (resolved − base) on MOVEMENT_SPEED,
     * only when the actor's modifier revision changed since the last application (or {@code force}).
     *
     * @return the resolved speed when (re)applied, null when nothing changed
     */
    public ResolvedSpeed applySpeed(Mob mob, Adopted state, boolean force) {
        if (state == null) return null;
        long revision = pipeline.revision(mob.getUniqueId());
        if (!force && revision == state.appliedRevision) return null;
        ResolvedSpeed speed = pipeline.resolveSpeed(mob.getUniqueId(), state.profileId);
        double base = attributes.base(mob, Attribute.MOVEMENT_SPEED, speed.value());
        attributes.setAddModifier(mob, Attribute.MOVEMENT_SPEED, speedKey, speed.value() - base);
        state.appliedRevision = revision;
        reportOnce(mob, state, speed.reports());
        return speed;
    }

    /** Re-applies speed if the revision moved (call from the consumer's periodic tick). */
    public void refresh(Mob mob) {
        Adopted state = adopted.get(mob.getUniqueId());
        if (state == null) return;
        applySpeed(mob, state, false);
        installGoals(mob);
    }

    private void reportOnce(Mob mob, Adopted state, List<ClampReport> reports) {
        if (reports.isEmpty()) return;
        long revision = pipeline.revision(mob.getUniqueId());
        if (state != null && state.reportedRevision == revision) return;
        if (state != null) state.reportedRevision = revision;
        StringBuilder text = new StringBuilder();
        for (ClampReport report : reports) {
            if (!text.isEmpty()) text.append("; ");
            text.append(report.describe());
        }
        logger.info("[NPC] " + mob.getType().getKey().getKey() + " " + mob.getUniqueId() + " revision " + revision
            + ": " + text);
    }

    /** Drops every per-actor state (death, despawn, match end). */
    public void forget(UUID mobId) {
        adopted.remove(mobId);
        pipeline.forget(mobId);
    }

    public int adoptedCount() {
        return adopted.size();
    }
}
