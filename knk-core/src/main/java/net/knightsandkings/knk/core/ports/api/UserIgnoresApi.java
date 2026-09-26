package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.users.UserIgnore;

/**
 * A player's ignore list, stored by knk-web-api (UserIgnoresController, {@code api/users/{id}/ignores};
 * docs/specs/private-messages/DESIGN.md §3.2). The API enforces the rules; {@link AddResult} says
 * which one refused an add.
 */
public interface UserIgnoresApi {

    enum AddResult {
        /** Now ignored, or already was. */
        IGNORED,
        USER_NOT_FOUND,
        SELF_IGNORE,
        /** The target holds {@code knk.msg.unignorable}. */
        CANNOT_IGNORE_STAFF,
        /** The list is full (100 entries). */
        LIMIT_REACHED
    }

    /** Everyone {@code userId} ignores, oldest first. */
    CompletableFuture<List<UserIgnore>> list(int userId);

    /** Idempotent. Fails exceptionally only for transport/server errors, not for a rule refusal. */
    CompletableFuture<AddResult> add(int userId, int ignoredUserId);

    /** Idempotent. */
    CompletableFuture<Void> remove(int userId, int ignoredUserId);
}
