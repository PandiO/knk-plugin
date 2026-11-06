package net.knightsandkings.knk.core.statistics;

/**
 * Classifies a death for the internal {@code deaths_by_cause.*} counters (DESIGN.md §F.7): a player
 * killer → {@code player}; otherwise the last damage came from an entity (a creature or a creature's
 * projectile) → {@code mob}; everything else (fall, lava, drowning, void, fire, …) → {@code environment}.
 * The Bukkit side reduces the death to the two inputs; this class holds the rule.
 */
public final class DeathCauseClassifier {

    private DeathCauseClassifier() {
    }

    /** Who dealt the last damage before the death, as far as the death cause cares. */
    public enum LastDamager {
        /** No entity (environmental damage) or the victim themselves. */
        NONE,
        /** Another player, directly or by projectile, without Bukkit crediting them as killer. */
        PLAYER,
        /** A non-player living entity, directly or by projectile. */
        MOB,
        /** A Survival match NPC (a mob tagged as a match actor), directly or by projectile (link S3). */
        MATCH_NPC
    }

    public enum Cause {
        PLAYER(StatisticsMetric.DEATHS_BY_CAUSE_PLAYER),
        MOB(StatisticsMetric.DEATHS_BY_CAUSE_MOB),
        ENVIRONMENT(StatisticsMetric.DEATHS_BY_CAUSE_ENVIRONMENT),
        /**
         * Killed by a Survival match NPC (Siege Survival link S3, PandiO/knk-workspace#41): a match event, not an
         * ordinary death cause, so it has no {@code deaths_by_cause.*} metric and {@link #recorded()} is false.
         */
        MATCH_NPC(null);

        private final StatisticsMetric metric;

        Cause(StatisticsMetric metric) {
            this.metric = metric;
        }

        /** The {@code deaths_by_cause.*} metric; null for a cause the ordinary statistics leave out. */
        public StatisticsMetric metric() {
            return metric;
        }

        /** Whether the ordinary statistics record this cause at all. */
        public boolean recorded() {
            return metric != null;
        }
    }

    /**
     * @param killedByOtherPlayer Bukkit credited another player as the killer ({@code Player#getKiller()},
     *                            not the victim themselves)
     * @param lastDamager         the entity behind the last damage event
     */
    public static Cause classify(boolean killedByOtherPlayer, LastDamager lastDamager) {
        if (killedByOtherPlayer || lastDamager == LastDamager.PLAYER) {
            return Cause.PLAYER;
        }
        if (lastDamager == LastDamager.MOB) {
            return Cause.MOB;
        }
        if (lastDamager == LastDamager.MATCH_NPC) {
            return Cause.MATCH_NPC;
        }
        return Cause.ENVIRONMENT;
    }
}
