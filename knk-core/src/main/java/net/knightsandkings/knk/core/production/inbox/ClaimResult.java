package net.knightsandkings.knk.core.production.inbox;

import java.util.List;

/**
 * The API's answer to a claim ({@code POST api/Inbox/{userId}/claim}): how many units moved out of the inbox
 * ({@code accepted}, the game server places exactly that many), why the rest did not, and the inbox afterwards.
 */
public record ClaimResult(String status, boolean replayed, long accepted, List<String> reasons, InboxView view) {

    public ClaimResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
