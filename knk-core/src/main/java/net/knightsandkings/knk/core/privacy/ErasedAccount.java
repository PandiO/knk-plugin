package net.knightsandkings.knk.core.privacy;

import java.util.UUID;

/**
 * One account a GDPR erasure removed in knk-web-api (realtime S5, PandiO/knk-workspace#184): the {@code AccountErased}
 * push or a row of {@code GET api/privacy/erasures}. The game server kicks the player and deletes everything it keeps
 * about them.
 *
 * @param erasureId  the API's erasure feed sequence (positive)
 * @param userId     the erased knk account
 * @param formerUuid its Minecraft UUID before the erasure; null when it had none, or when the API no longer keeps it
 *                   (its retention ended): only what is filed under the user id can then be found
 */
public record ErasedAccount(long erasureId, int userId, UUID formerUuid) {
    public ErasedAccount {
        if (erasureId <= 0) {
            throw new IllegalArgumentException("erasureId must be positive");
        }
    }
}
