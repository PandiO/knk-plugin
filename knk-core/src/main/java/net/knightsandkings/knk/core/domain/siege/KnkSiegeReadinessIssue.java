package net.knightsandkings.knk.core.domain.siege;

/**
 * One readiness error or warning (API {@code SiegeReadinessIssueDto}).
 *
 * @param code       stable machine code, e.g. {@code TEAM_NO_SPAWNPOINT}
 * @param entityType the row it is about ("SiegeTeam"), or null
 * @param entityId   that row's id, or null
 */
public record KnkSiegeReadinessIssue(String code, String message, String entityType, Integer entityId) {
}
