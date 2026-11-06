package net.knightsandkings.knk.paper.npc;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.knightsandkings.knk.core.npc.profile.TargetTypes;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;

/**
 * The platform's melee attack goal (NPC platform §5b; replaces vanilla {@code MeleeAttackGoal}/{@code ZombieAttackGoal},
 * checks report §4): active while the mob has a living target, walks to it with the mob's own pathfinder and, in
 * reach, swings and attacks whenever {@code pipeline.tryMelee} says the resolved cooldown has elapsed, with the
 * resolved melee damage set on the mob's attack attribute. The vanilla attack call keeps knockback, enchantments
 * and mob effects, so the mob looks vanilla.
 */
public final class NpcMeleeGoal implements Goal<Mob> {

    public static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, NamespacedKey.fromString("knightsandkings:npc_melee"));
    /** Re-path interval (ticks) while chasing; vanilla re-paths every 4–10 ticks. */
    static final int REPATH_TICKS = 8;
    /** Pathfinder speed multiplier (1 = the attribute speed, which carries the scaling). */
    static final double CHASE_SPEED = 1.0;

    private final NpcPlatform platform;
    private final Mob mob;
    private int sinceRepath;

    public NpcMeleeGoal(NpcPlatform platform, Mob mob) {
        this.platform = platform;
        this.mob = mob;
    }

    @Override
    public boolean shouldActivate() {
        LivingEntity target = mob.getTarget();
        return target != null && !target.isDead() && target.isValid() && mob.isValid();
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
        sinceRepath = REPATH_TICKS;
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        mob.setAggressive(true);
        if (++sinceRepath >= REPATH_TICKS) {
            sinceRepath = 0;
            mob.getPathfinder().moveTo(target, CHASE_SPEED);
        }
        Location me = mob.getLocation();
        Location them = target.getLocation();
        if (me.getWorld() != them.getWorld()) return;
        if (me.distanceSquared(them) > reachSquared(target)) return;
        mob.lookAt(target);
        ResolvedCombat combat = platform.resolveCombat(mob, targetTypeOf(target), VanillaBases.melee(platform.vanillaMeleeDamage(mob)));
        if (!platform.tryMelee(mob, combat)) return;
        platform.applyMeleeDamage(mob, combat);
        mob.swingMainHand();
        mob.attack(target);
    }

    /** Vanilla's attack reach: (2 × width)² + the target's width. */
    double reachSquared(LivingEntity target) {
        double w = mob.getWidth() * 2.0;
        return w * w + target.getWidth();
    }

    static String targetTypeOf(LivingEntity target) {
        return target instanceof Player ? TargetTypes.PLAYER : TargetTypes.NONE;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }
}
