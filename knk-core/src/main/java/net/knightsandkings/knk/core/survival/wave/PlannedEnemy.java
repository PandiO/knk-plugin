package net.knightsandkings.knk.core.survival.wave;

/**
 * One enemy of a round's planned spawn queue (DESIGN §3 step 3). The id is unique within the match and survives
 * stuck respawns (§3c: "the same planned enemy"), so a respawned enemy is never counted twice.
 *
 * @param id          match-unique enemy id
 * @param round       the round it belongs to
 * @param monsterType the selected vanilla monster type (S42), the admin's identifier, e.g. {@code ZOMBIE}
 * @param special     true when it comes from a special-wave profile (S14/S53)
 */
public record PlannedEnemy(long id, int round, String monsterType, boolean special) {

    public PlannedEnemy {
        if (round < 1) throw new IllegalArgumentException("round must be >= 1: " + round);
        if (monsterType == null || monsterType.isBlank()) throw new IllegalArgumentException("monsterType is required");
    }
}
