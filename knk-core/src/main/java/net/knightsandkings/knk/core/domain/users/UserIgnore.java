package net.knightsandkings.knk.core.domain.users;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry on a player's ignore list (KNG-18 Phase 2, docs/specs/private-messages/DESIGN.md
 * §3.3.5). Mirrors knk-web-api's UserIgnoreDto.
 *
 * @param ignoredUuid the ignored player's Minecraft UUID; null for a web-only account (never in chat)
 */
public record UserIgnore(int ignoredUserId, String ignoredUsername, UUID ignoredUuid, OffsetDateTime createdAt) {
}
