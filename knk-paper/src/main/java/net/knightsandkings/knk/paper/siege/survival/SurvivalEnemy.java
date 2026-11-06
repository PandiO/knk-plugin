package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine;
import org.bukkit.entity.Mob;

import java.util.Objects;
import java.util.UUID;

/** One live enemy of the match: its planned identity, its mob, its goal machine and mover, and the per-tick bookkeeping. */
public final class SurvivalEnemy {

    private final PlannedEnemy planned;
    private final Mob mob;
    private final EnemyGoalMachine goals;
    private final MobMover mover;
    private final String zoneId;
    private int breachTicks;
    private int behaviourTicks;
    private int sampleTicks;
    private double lastHealth;
    private boolean dealtDamage;
    private boolean glowing;
    private boolean pursuesDirectly;
    private String lastTargetDescription = "";

    public SurvivalEnemy(PlannedEnemy planned, Mob mob, EnemyGoalMachine goals, MobMover mover, String zoneId) {
        this.planned = Objects.requireNonNull(planned, "planned");
        this.mob = Objects.requireNonNull(mob, "mob");
        this.goals = Objects.requireNonNull(goals, "goals");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.zoneId = zoneId == null ? "" : zoneId;
        this.lastHealth = mob.getHealth();
    }

    public PlannedEnemy planned() {
        return planned;
    }

    public long id() {
        return planned.id();
    }

    public Mob mob() {
        return mob;
    }

    public UUID mobId() {
        return mob.getUniqueId();
    }

    public EnemyGoalMachine goals() {
        return goals;
    }

    public MobMover mover() {
        return mover;
    }

    public String zoneId() {
        return zoneId;
    }

    /** Counts down the breach timer; true when a hit is due (resets the timer). */
    public boolean breachHitDue(int intervalTicks) {
        if (++breachTicks < intervalTicks) return false;
        breachTicks = 0;
        return true;
    }

    public boolean behaviourDue(int intervalTicks) {
        if (++behaviourTicks < intervalTicks) return false;
        behaviourTicks = 0;
        return true;
    }

    public boolean sampleDue(int intervalTicks) {
        if (++sampleTicks < intervalTicks) return false;
        sampleTicks = 0;
        return true;
    }

    /** Damage dealt or taken since the previous sample (consumes both flags). */
    public boolean consumeDamageProgress() {
        boolean took = mob.isValid() && mob.getHealth() < lastHealth - 1e-6;
        lastHealth = mob.isValid() ? mob.getHealth() : lastHealth;
        boolean dealt = dealtDamage;
        dealtDamage = false;
        return took || dealt;
    }

    public void markDealtDamage() {
        dealtDamage = true;
    }

    public boolean glowing() {
        return glowing;
    }

    public void setGlowing(boolean glowing) {
        this.glowing = glowing;
    }

    public boolean pursuesDirectly() {
        return pursuesDirectly;
    }

    public void setPursuesDirectly(boolean pursuesDirectly) {
        this.pursuesDirectly = pursuesDirectly;
    }

    public String lastTargetDescription() {
        return lastTargetDescription;
    }

    public void setLastTargetDescription(String description) {
        this.lastTargetDescription = description == null ? "" : description;
    }
}
