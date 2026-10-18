package net.knightsandkings.knk.paper.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.users.RewardMultiplier;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class HeldDiscoveriesTest {
    private static final RewardMultiplier ROYAL = new RewardMultiplier("rank", 1.2, 20, "Royal", true, "&6", "&e");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private static DiscoveryGrant place(int id, String name, int coins, int exp) {
        return new DiscoveryGrant(id, "r" + id, name, "District", "Rivia", "RegionEnter", coins, 0, exp, coins, 0, exp);
    }

    private static DiscoveryGrantResult result(int newCoins, DiscoveryGrant... granted) {
        int coins = List.of(granted).stream().mapToInt(DiscoveryGrant::coins).sum();
        int exp = List.of(granted).stream().mapToInt(DiscoveryGrant::exp).sum();
        return new DiscoveryGrantResult(List.of(granted), List.of(), List.of(), coins, 0, exp, coins, 0, exp,
            List.of(ROYAL), List.of(), List.of(), null, newCoins, 0, 0, null);
    }

    @Test
    void onlyPlayersWhoAreNoLongerQuietAreReleased() {
        HeldDiscoveries held = new HeldDiscoveries();
        DiscoveryGrantResult first = result(100, place(1, "Market", 100, 10));
        held.hold(alice, first);
        held.hold(bob, result(50, place(2, "Docks", 50, 5)));

        Map<UUID, DiscoveryGrantResult> released = held.releaseWhere(Set.of(bob)::contains);

        assertEquals(Set.of(alice), released.keySet());
        assertSame(first, released.get(alice), "a single answer is shown as it came");
        assertEquals(Set.of(bob), held.releaseWhere(id -> false).keySet());
        assertTrue(held.isEmpty());
    }

    @Test
    void severalAnswersMergeIntoOneSummaryWithoutABreakdown() {
        DiscoveryGrantResult merged = HeldDiscoveries.merge(List.of(
            result(100, place(1, "Market", 100, 10)),
            result(350, place(2, "Docks", 200, 20), place(3, "Keep", 50, 5))));

        assertEquals(3, merged.granted().size());
        assertEquals(350, merged.totalCoins());
        assertEquals(35, merged.totalExp());
        assertEquals(0, merged.totalCoinsBase());
        assertTrue(merged.coinMultipliers().isEmpty());
        assertEquals(350, merged.newCoins(), "the last answer's balance");

        String summary = KnkConfig.OnboardingConfig.defaults().discoverySettledSummary();
        List<String> lines = DiscoveryMessages.replaySummary(summary, merged).stream()
            .map((Component line) -> PlainTextComponentSerializer.plainText().serialize(line)).toList();
        assertEquals("While settling in you discovered 3 place(s). See them all with /discoveries.", lines.get(0));
        assertEquals(3, lines.size(), "the count, then the coin and XP totals");
    }
}
