package net.knightsandkings.knk.core.domain.users;

/**
 * A title bracket (user-features Phase 6 reference data; API {@code TitleBracketDto}): a player
 * holds the highest bracket whose {@code minExperience} their experience reaches.
 */
public record KnkTitleBracket(int id, String name, int minExperience) {
}
