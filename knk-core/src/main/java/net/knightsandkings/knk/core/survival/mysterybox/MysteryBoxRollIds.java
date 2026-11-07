package net.knightsandkings.knk.core.survival.mysterybox;

import java.util.UUID;

/** Roll ids of the match-scoped roll endpoint (link S7a): {@code m{matchId}-p{pointId}-{uuid}}, at most 64 characters. */
public final class MysteryBoxRollIds {

    public static final int MAX_LENGTH = 64;

    private MysteryBoxRollIds() {
    }

    public static String rollId(long matchId, int pointId, UUID unique) {
        String id = "m" + matchId + "-p" + pointId + "-" + unique;
        return id.length() <= MAX_LENGTH ? id : id.substring(0, MAX_LENGTH);
    }

    public static String rollId(long matchId, int pointId) {
        return rollId(matchId, pointId, UUID.randomUUID());
    }
}
