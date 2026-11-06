package net.knightsandkings.knk.paper.npc;

import com.destroystokyo.paper.entity.RangedEntity;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;

/**
 * The platform's bow goal (NPC platform §5b; replaces vanilla {@code RangedBowAttackGoal}, checks report §4): active
 * while a bow-wielding ranged mob has a target. Within range it draws for the resolved draw ticks, then releases when
 * {@code pipeline.tryShot} allows (the resolved ranged cooldown); the arrow damage is set by
 * {@link NpcProjectileDamageListener} from the shoot event. Out of range it closes in; without a bow it does nothing
 * and the melee goal takes over.
 */
public final class NpcBowGoal implements Goal<Mob> {

    public static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, NamespacedKey.fromString("knightsandkings:npc_bow"));
    /** Vanilla skeleton shooting range (15 blocks). */
    static final double RANGE = 15.0;
    static final int REPATH_TICKS = 10;

    private final NpcPlatform platform;
    private final RangedEntity mob;
    private int drawTicks;
    private int sinceRepath;

    public NpcBowGoal(NpcPlatform platform, RangedEntity mob) {
        this.platform = platform;
        this.mob = mob;
    }

    static boolean holdsBow(Mob mob) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) return false;
        ItemStack hand = equipment.getItemInMainHand();
        return hand != null && hand.getType() == Material.BOW;
    }

    @Override
    public boolean shouldActivate() {
        LivingEntity target = mob.getTarget();
        return target != null && !target.isDead() && target.isValid() && mob.isValid() && holdsBow(mob);
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
        drawTicks = -1;
        sinceRepath = REPATH_TICKS;
    }

    @Override
    public void stop() {
        drawTicks = -1;
        mob.setChargingAttack(false);
        mob.setAggressive(false);
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        Location me = mob.getLocation();
        Location them = target.getLocation();
        if (me.getWorld() != them.getWorld()) return;
        double distanceSquared = me.distanceSquared(them);
        boolean inRange = distanceSquared <= RANGE * RANGE && mob.hasLineOfSight(target);
        if (!inRange) {
            if (++sinceRepath >= REPATH_TICKS) {
                sinceRepath = 0;
                mob.getPathfinder().moveTo(target, 1.0);
            }
            if (drawTicks >= 0) {
                drawTicks = -1;
                mob.setChargingAttack(false);
            }
            return;
        }
        mob.getPathfinder().stopPathfinding();
        mob.lookAt(target);
        ResolvedCombat combat = platform.resolveCombat(mob, NpcMeleeGoal.targetTypeOf(target), VanillaBases.ZERO);
        if (drawTicks < 0) {
            // Start drawing only when the cooldown allows a release at the end of the draw.
            if (!platform.pipeline().cooldowns().isReady(mob.getUniqueId(), "ranged", platform.currentTick())) return;
            drawTicks = 0;
            mob.setChargingAttack(true);
            mob.setAggressive(true);
            return;
        }
        drawTicks++;
        if (drawTicks < combat.drawTicks()) return;
        if (!platform.tryShot(mob, combat)) return;
        float charge = Math.min(1.0f, (float) drawTicks / 20.0f);
        mob.rangedAttack(target, charge);
        mob.setChargingAttack(false);
        drawTicks = -1;
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
