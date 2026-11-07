package net.knightsandkings.knk.core.survival.mysterybox;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteryBoxRollIdsTest {

    @Test
    void rollIdsCarryMatchPointAndUuidWithin64Characters() {
        UUID u = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String id = MysteryBoxRollIds.rollId(42, 7, u);
        assertEquals("m42-p7-123e4567-e89b-12d3-a456-426614174000", id);
        assertTrue(MysteryBoxRollIds.rollId(Long.MAX_VALUE, Integer.MAX_VALUE, u).length() <= MysteryBoxRollIds.MAX_LENGTH);
        assertTrue(MysteryBoxRollIds.rollId(1, 1).startsWith("m1-p1-"));
    }
}
