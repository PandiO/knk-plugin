package net.knightsandkings.knk.core.domain.location;

/**
 * Payload of a LocationOrphanDigest player notification (KNG-80): a Location retention run found
 * orphaned Locations staff haven't seen yet. One per run, never one per Location. Mirrors
 * knk-web-api's LocationOrphanDigestNotificationDto.
 */
public record LocationOrphanDigest(int runId, int newCount, int openCount) {
}
