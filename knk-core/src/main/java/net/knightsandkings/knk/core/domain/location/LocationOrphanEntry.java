package net.knightsandkings.knk.core.domain.location;

/**
 * One orphaned Location under review (KNG-80), as {@code /knk location orphans} lists it: the
 * snapshot taken when it was flagged, and the earlier Keep decision when it was flagged again.
 * Mirrors the fields of knk-web-api's LocationOrphanDto the game needs.
 */
public record LocationOrphanEntry(
    int id,
    int locationId,
    String status,
    String world,
    double x,
    double y,
    double z,
    String flaggedAt,
    String previouslyKeptBy, // null unless re-flagged after a Keep
    String previousNote
) {
}
