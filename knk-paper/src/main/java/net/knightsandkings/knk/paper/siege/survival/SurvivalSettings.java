package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.targeting.TargetKind;
import net.knightsandkings.knk.core.targeting.TargetPolicy;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Plugin-side knobs of the survival runtime ({@code siege.survival:} in config.yml). These are the values the design
 * leaves to balance tests or doesn't fix at all (DESIGN §4: aggro radius, leash distance, preference, commitment and
 * switch margin; §6 retry budgets; §3c sample period); every one has a code default here. The difficulty profile's
 * aggro radius / leash distance override the two defaults per match when set (null = these).
 *
 * @param behaviourTicks        how often each mob's goal machine observes the world (ticks)
 * @param stuckSampleTicks      how often a progress sample goes to the stuck monitor (ticks)
 * @param breachHitTicks        ticks between two door hits of a breaching mob
 * @param repathTicks           ticks between two pathfinder moveTo calls toward the same destination
 * @param gateArriveDistance    distance (blocks) to the stand point that counts as arrived
 * @param laneArriveDistance    distance to a lane point that advances to the next
 * @param followRange           FOLLOW_RANGE the mobs get (the vanilla pathfinder's search radius)
 * @param blockedRetries        moveTo failures toward one destination before it counts as blocked
 * @param blockedCooldownMillis how long a blocked target stays aside
 * @param spawnSpreadRadius     random spread around a spawn point (blocks)
 */
public record SurvivalSettings(TargetPolicy targeting, int behaviourTicks, int stuckSampleTicks, int breachHitTicks,
                               int repathTicks, double gateArriveDistance, double laneArriveDistance, double followRange,
                               int blockedRetries, long blockedCooldownMillis, double spawnSpreadRadius) {

    public static final String SECTION = "siege.survival";

    /** Default slowest Mystery Box reel step (ticks), link S7b (the lootbox reel's {@code slowestStepTicks} default). */
    public static final int DEFAULT_REEL_MAX_STEP_TICKS = 8;

    public SurvivalSettings {
        if (targeting == null) targeting = TargetPolicy.defaults();
        behaviourTicks = Math.max(1, behaviourTicks);
        stuckSampleTicks = Math.max(1, stuckSampleTicks);
        breachHitTicks = Math.max(1, breachHitTicks);
        repathTicks = Math.max(1, repathTicks);
        gateArriveDistance = Math.max(0.5, gateArriveDistance);
        laneArriveDistance = Math.max(0.5, laneArriveDistance);
        followRange = Math.max(16, followRange);
        blockedRetries = Math.max(0, blockedRetries);
        blockedCooldownMillis = Math.max(0, blockedCooldownMillis);
        spawnSpreadRadius = Math.max(0, spawnSpreadRadius);
    }

    public static SurvivalSettings defaults() {
        return new SurvivalSettings(TargetPolicy.defaults(), 10, 20, 20, 10, 2.5, 2.5, 96, 3, 20_000L, 1.5);
    }

    /** {@code siege.survival.mystery-box.reel-max-step-ticks}: the slowest reel step (ticks), link S7b. */
    /** Default of {@code siege.survival.checkpoint-interval-seconds} (link S8b, DESIGN §10): 30 s; 0 turns the interval off. */
    public static final int DEFAULT_CHECKPOINT_INTERVAL_SECONDS = 30;

    /** {@code siege.survival.checkpoint-interval-seconds}: seconds between two periodic checkpoints (0 = state changes only). */
    public static int checkpointIntervalSeconds(ConfigurationSection root) {
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        return s == null ? DEFAULT_CHECKPOINT_INTERVAL_SECONDS : Math.max(0, s.getInt("checkpoint-interval-seconds", DEFAULT_CHECKPOINT_INTERVAL_SECONDS));
    }

    public static int reelMaxStepTicks(ConfigurationSection root) {
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        return s == null ? DEFAULT_REEL_MAX_STEP_TICKS : Math.max(1, s.getInt("mystery-box.reel-max-step-ticks", DEFAULT_REEL_MAX_STEP_TICKS));
    }

    /** Reads {@code siege.survival} (missing keys = the defaults). */
    public static SurvivalSettings fromConfig(ConfigurationSection root) {
        SurvivalSettings d = defaults();
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        if (s == null) return d;
        TargetPolicy t = d.targeting();
        ConfigurationSection targeting = s.getConfigurationSection("targeting");
        if (targeting != null) {
            t = new TargetPolicy(java.util.Map.of(
                    TargetKind.PLAYER, Math.max(0, targeting.getDouble("preference.player", t.preference(TargetKind.PLAYER))),
                    TargetKind.GATE, Math.max(0, targeting.getDouble("preference.gate", t.preference(TargetKind.GATE))),
                    TargetKind.OBJECTIVE, Math.max(0, targeting.getDouble("preference.objective", t.preference(TargetKind.OBJECTIVE)))),
                positive(targeting.getDouble("proximity-scale-blocks", t.proximityScale()), t.proximityScale()),
                Math.max(0, targeting.getLong("min-commitment-millis", t.minCommitmentMillis())),
                Math.max(0, targeting.getDouble("switch-margin", t.switchMargin())),
                Math.max(0, targeting.getDouble("aggro-radius", t.aggroRadius())),
                Math.max(0, targeting.getDouble("leash-distance", t.leashDistance())));
        }
        return new SurvivalSettings(t,
            s.getInt("behaviour-ticks", d.behaviourTicks()),
            s.getInt("stuck-sample-ticks", d.stuckSampleTicks()),
            s.getInt("breach-hit-ticks", d.breachHitTicks()),
            s.getInt("repath-ticks", d.repathTicks()),
            s.getDouble("gate-arrive-distance", d.gateArriveDistance()),
            s.getDouble("lane-arrive-distance", d.laneArriveDistance()),
            s.getDouble("follow-range", d.followRange()),
            s.getInt("blocked-retries", d.blockedRetries()),
            s.getLong("blocked-cooldown-millis", d.blockedCooldownMillis()),
            s.getDouble("spawn-spread-radius", d.spawnSpreadRadius()));
    }

    private static double positive(double value, double fallback) {
        return value > 0 && Double.isFinite(value) ? value : fallback;
    }

    /** The policy for a match: the difficulty's aggro radius / leash distance when set, else the config defaults. */
    public TargetPolicy policyFor(SurvivalConfiguration.DifficultySpec difficulty) {
        TargetPolicy policy = targeting;
        if (difficulty.aggroRadius().isPresent()) policy = policy.withAggroRadius(difficulty.aggroRadius().getAsDouble());
        if (difficulty.leashDistance().isPresent()) policy = policy.withLeashDistance(difficulty.leashDistance().getAsDouble());
        return policy;
    }
}
