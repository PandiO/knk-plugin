package net.knightsandkings.knk.core.minigame;

import net.knightsandkings.knk.core.minigame.Participation.Stage;
import net.knightsandkings.knk.core.statistics.StatisticsContext;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena duels Phase 0 (KNG-209, DESIGN §11): the minigame registry composes the minigames' participation. */
class MinigameRegistryTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    /** A minigame with fixed members; the rest follows the port's defaults unless set. */
    private static final class Fake implements MinigameParticipation {
        final String id;
        final Map<UUID, Stage> members = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean combat;
        double headshots = 1.0;
        boolean release = true;
        boolean failResume;
        TeleportDenial denial;
        Set<String> bypass = Set.of();

        Fake(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Optional<Participation> participationOf(UUID playerId) {
            Stage stage = members.get(playerId);
            return stage == null ? Optional.empty() : Optional.of(new Participation(playerId, id, "token-" + id, stage));
        }

        @Override
        public boolean allowsCombat(UUID attacker, UUID victim) {
            return combat;
        }

        @Override
        public double headshotMultiplier(UUID attacker, UUID victim) {
            return headshots;
        }

        @Override
        public boolean releaseForOutage(UUID playerId) {
            calls.add("release");
            return release;
        }

        @Override
        public void resumeAfterOutage() {
            calls.add("resume");
            if (failResume) throw new IllegalStateException("boom");
        }

        @Override
        public Optional<TeleportDenial> teleportRefusal(MinigameTeleportAttempt attempt) {
            return members.containsKey(attempt.subject()) ? Optional.ofNullable(denial) : Optional.empty();
        }

        @Override
        public Set<String> teleportBypassNodes() {
            return bypass;
        }
    }

    private static MinigameTeleportAttempt attempt(UUID subject) {
        return new MinigameTeleportAttempt(subject, "name", TeleportKind.SPAWN, true, null, null, null);
    }

    @Test
    void anEmptyRegistryKnowsNobodyLikeTheOldNullChecks() {
        MinigameRegistry registry = new MinigameRegistry();

        assertTrue(registry.participationOf(ALICE).isEmpty());
        assertTrue(registry.participationOf(null).isEmpty());
        assertFalse(registry.isMember(ALICE));
        assertFalse(registry.hasLeftOpenWorld(ALICE));
        assertFalse(registry.allowsCombat(ALICE, BOB));
        assertEquals(1.0, registry.headshotMultiplier(ALICE, BOB));
        assertTrue(registry.releaseForOutage(ALICE), "nobody to release: free to move");
        registry.resumeAfterOutage();
        assertFalse(registry.holdsLootDrops(ALICE));
        assertFalse(registry.keepsOwnScoreboard(ALICE));
        assertFalse(registry.excludesBackOnDeath(ALICE));
        assertTrue(registry.teleportRefusal(attempt(ALICE)).isEmpty());
        assertTrue(registry.teleportBypassNodes().isEmpty());
        assertTrue(registry.statisticsContextOf(ALICE).isEmpty());
        assertTrue(registry.hubRefusal(ALICE).isEmpty());
    }

    @Test
    void theStagesDriveTheSharedQuestions() {
        Fake siege = new Fake("siege");
        siege.members.put(ALICE, Stage.QUEUED);
        siege.members.put(BOB, Stage.STAGING);
        siege.members.put(CAROL, Stage.PLAYING);
        UUID dave = UUID.randomUUID();
        siege.members.put(dave, Stage.FINISHING);
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(siege);

        // navigation: any member
        assertTrue(registry.isMember(ALICE) && registry.isMember(BOB) && registry.isMember(CAROL) && registry.isMember(dave));
        // discovery, /hub, respawn: from the hub teleport until restored
        assertFalse(registry.hasLeftOpenWorld(ALICE));
        assertTrue(registry.hasLeftOpenWorld(BOB) && registry.hasLeftOpenWorld(CAROL) && registry.hasLeftOpenWorld(dave));
        assertEquals(Optional.empty(), registry.hubRefusal(ALICE));
        assertEquals(Optional.of("You are in a siege."), registry.hubRefusal(dave));
        // teleports, /back, scoreboard, lootbox deliveries: away (hub or match)
        for (UUID away : List.of(BOB, CAROL)) {
            assertTrue(registry.isAway(away) && registry.holdsLootDrops(away) && registry.keepsOwnScoreboard(away)
                && registry.excludesBackOnDeath(away));
        }
        for (UUID notAway : List.of(ALICE, dave)) {
            assertFalse(registry.isAway(notAway) || registry.holdsLootDrops(notAway) || registry.keepsOwnScoreboard(notAway)
                || registry.excludesBackOnDeath(notAway));
        }
        // statistics: only while playing
        assertEquals(Optional.of(StatisticsContext.SIEGE), registry.statisticsContextOf(CAROL));
        assertTrue(registry.statisticsContextOf(BOB).isEmpty());
    }

    @Test
    void eachMinigameAnswersForItsOwnMembers() {
        Fake siege = new Fake("siege");
        siege.members.put(ALICE, Stage.PLAYING);
        Fake duel = new Fake("duel");
        duel.members.put(BOB, Stage.PLAYING);
        duel.combat = true;
        duel.headshots = 1.5;
        duel.denial = TeleportDenial.of("duel", "You are in a duel.");
        duel.bypass = Set.of("knk.duel.bypass");
        siege.bypass = Set.of("knk.siege.bypass.commands");
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(siege);
        registry.register(duel);

        assertEquals("siege", registry.participationOf(ALICE).orElseThrow().minigame());
        assertEquals("token-duel", registry.participationOf(BOB).orElseThrow().matchToken());
        assertEquals(Optional.of(StatisticsContext.of("duel")), registry.statisticsContextOf(BOB));
        assertEquals(Optional.of("You are in a duel."), registry.hubRefusal(BOB));
        assertTrue(registry.allowsCombat(ALICE, BOB), "any minigame allowing the hit");
        assertFalse(registry.allowsCombat(null, BOB));
        assertEquals(1.5, registry.headshotMultiplier(ALICE, BOB));
        assertEquals("You are in a duel.", registry.teleportRefusal(attempt(BOB)).orElseThrow().message());
        assertTrue(registry.teleportRefusal(attempt(CAROL)).isEmpty());
        assertEquals(Set.of("knk.duel.bypass", "knk.siege.bypass.commands"), registry.teleportBypassNodes());
        assertEquals(List.of(siege, duel), registry.minigames());
        assertEquals(Optional.of(duel), registry.find("duel"));
    }

    @Test
    void anOutageReleaseAsksEveryMinigameAndNeedsAllOfThem() {
        Fake siege = new Fake("siege");
        Fake duel = new Fake("duel");
        siege.release = false;
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(siege);
        registry.register(duel);

        assertFalse(registry.releaseForOutage(ALICE));
        assertEquals(List.of("release"), duel.calls, "the second minigame is asked too");

        siege.release = true;
        assertTrue(registry.releaseForOutage(ALICE));
    }

    @Test
    void aFailedResumeDoesNotStopTheOthers() {
        Fake siege = new Fake("siege");
        siege.failResume = true;
        Fake duel = new Fake("duel");
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(siege);
        registry.register(duel);

        registry.resumeAfterOutage();

        assertEquals(List.of("resume"), siege.calls);
        assertEquals(List.of("resume"), duel.calls);
    }

    @Test
    void idsAreUniqueAndUnregisterRemoves() {
        MinigameRegistry registry = new MinigameRegistry();
        Fake siege = new Fake("siege");
        siege.members.put(ALICE, Stage.STAGING);
        registry.register(siege);

        assertThrows(IllegalStateException.class, () -> registry.register(new Fake("siege")));

        registry.unregister(siege);
        assertFalse(registry.isMember(ALICE));
        assertTrue(registry.find("siege").isEmpty());
    }

    @Test
    void thePortDefaultsFollowTheStage() {
        Fake bare = new Fake("bare");
        MinigameParticipation defaults = new MinigameParticipation() {
            @Override
            public String id() {
                return "bare";
            }

            @Override
            public Optional<Participation> participationOf(UUID playerId) {
                return bare.participationOf(playerId);
            }
        };
        bare.members.put(ALICE, Stage.STAGING);
        bare.members.put(BOB, Stage.QUEUED);

        assertTrue(defaults.holdsLootDrops(ALICE) && defaults.keepsOwnScoreboard(ALICE) && defaults.excludesBackOnDeath(ALICE));
        assertFalse(defaults.holdsLootDrops(BOB) || defaults.keepsOwnScoreboard(BOB) || defaults.excludesBackOnDeath(BOB));
        assertFalse(defaults.allowsCombat(ALICE, BOB));
        assertEquals(1.0, defaults.headshotMultiplier(ALICE, BOB));
        assertTrue(defaults.releaseForOutage(ALICE), "a minigame that doesn't say otherwise never holds up an outage action");
        assertTrue(defaults.teleportRefusal(attempt(ALICE)).isEmpty());
        assertTrue(defaults.teleportBypassNodes().isEmpty());
    }
}
