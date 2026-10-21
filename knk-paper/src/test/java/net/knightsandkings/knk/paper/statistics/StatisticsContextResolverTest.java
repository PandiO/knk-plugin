package net.knightsandkings.knk.paper.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.minigame.MinigameParticipation;
import net.knightsandkings.knk.core.minigame.MinigameRegistry;
import net.knightsandkings.knk.core.minigame.Participation;
import net.knightsandkings.knk.core.minigame.Participation.Stage;
import net.knightsandkings.knk.core.statistics.StatisticsContext;

/** The statistics context comes from the minigame registry: {@code duel} for duellists in their fight (KNG-221). */
class StatisticsContextResolverTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final UUID DAVE = UUID.randomUUID();

    private static final class Minigame implements MinigameParticipation {
        private final String id;
        private final Map<UUID, Stage> members = new HashMap<>();

        Minigame(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Optional<Participation> participationOf(UUID playerId) {
            Stage stage = members.get(playerId);
            return stage == null ? Optional.empty() : Optional.of(new Participation(playerId, id, "match-1", stage));
        }
    }

    @Test
    void duellistsInTheirFight_CountInTheDuelContext() {
        Minigame duel = new Minigame("duel");
        duel.members.put(ALICE, Stage.PLAYING);
        duel.members.put(BOB, Stage.STAGING); // warmup in the lobby: still the open world
        Minigame siege = new Minigame("siege");
        siege.members.put(CAROL, Stage.PLAYING);
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(siege);
        registry.register(duel);
        StatisticsContextResolver resolver = StatisticsContextResolver.of(registry);

        assertSame(StatisticsContext.DUEL, resolver.contextOf(ALICE));
        assertEquals("duel", resolver.contextOf(ALICE).key());
        assertSame(StatisticsContext.OPEN_WORLD, resolver.contextOf(BOB));
        assertSame(StatisticsContext.SIEGE, resolver.contextOf(CAROL));
        assertSame(StatisticsContext.OPEN_WORLD, resolver.contextOf(DAVE));
        assertSame(StatisticsContext.OPEN_WORLD, resolver.contextOf((UUID) null));
    }

    @Test
    void withoutADuelRuntime_EveryoneOutsideASiegeIsInTheOpenWorld() {
        MinigameRegistry registry = new MinigameRegistry();
        assertSame(StatisticsContext.OPEN_WORLD, StatisticsContextResolver.of(registry).contextOf(ALICE));
    }
}
