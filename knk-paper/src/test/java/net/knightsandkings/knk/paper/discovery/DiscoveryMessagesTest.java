package net.knightsandkings.knk.paper.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.users.RewardMultiplier;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class DiscoveryMessagesTest {
    private static final String TEMPLATE = KnkConfig.DiscoveryConfig.MessagesConfig.defaults().discovered();
    private static final String SUMMARY = KnkConfig.DiscoveryConfig.MessagesConfig.defaults().replaySummary();
    private static final RewardMultiplier PERSONAL_1 = new RewardMultiplier("personal", 1.0, null, null, false, null, null);
    private static final RewardMultiplier ROYAL = new RewardMultiplier("rank", 1.2, 20, "Royal", true, "&6", "&e");

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(DiscoveryMessagesTest::plain).toList();
    }

    private static DiscoveryGrant town() {
        return new DiscoveryGrant(1, "town_rivia", "Rivia", "Town", null, "RegionEnter", 3120, 6, 60, 2600, 5, 50);
    }

    private static DiscoveryGrant district() {
        return new DiscoveryGrant(4, "district_market", "Market", "District", "Rivia", "RegionEnter", 0, 0, 12, 0, 0, 10);
    }

    private static DiscoveryGrantResult result(List<DiscoveryGrant> granted, List<RewardMultiplier> coinMultipliers) {
        int coins = granted.stream().mapToInt(DiscoveryGrant::coins).sum();
        int coinsBase = granted.stream().mapToInt(DiscoveryGrant::coinsBase).sum();
        int gems = granted.stream().mapToInt(DiscoveryGrant::gems).sum();
        int gemsBase = granted.stream().mapToInt(DiscoveryGrant::gemsBase).sum();
        int exp = granted.stream().mapToInt(DiscoveryGrant::exp).sum();
        int expBase = granted.stream().mapToInt(DiscoveryGrant::expBase).sum();
        return new DiscoveryGrantResult(granted, List.of(), List.of(), coins, gems, exp, coinsBase, gemsBase, expBase,
                coinMultipliers, List.of(PERSONAL_1), List.of(new RewardMultiplier("personal", 1.2, null, null, false, null, null)),
                1, 0, 0, 0, null);
    }

    @Test
    void aTownHasNoParentAndShowsEachCurrencyWithItsMultipliers() {
        List<String> lines = plain(DiscoveryMessages.grantLines(TEMPLATE, town(), result(List.of(town()), List.of(PERSONAL_1, ROYAL))));

        assertEquals(List.of(
                "You discovered the town Rivia!",
                "  Reward: 2,600 ×1.2 Royal = +3,120 coins",
                "  Reward: +6 gems",
                "  Reward: 50 ×1.2 Personal = +60 XP"), lines);
    }

    @Test
    void aDistrictNamesItsTownAndZeroAmountsAreLeftOut() {
        List<String> lines = plain(DiscoveryMessages.grantLines(TEMPLATE, district(), result(List.of(district()), List.of())));

        assertEquals(List.of(
                "You discovered the district Market in Rivia!",
                "  Reward: 10 ×1.2 Personal = +12 XP"), lines);
    }

    @Test
    void usesV1Colours() {
        TextComponent line = (TextComponent) DiscoveryMessages.discovered(TEMPLATE, district());

        List<TextComponent> parts = new java.util.ArrayList<>();
        parts.add(line);
        line.children().forEach(c -> parts.add((TextComponent) c));
        TextComponent name = parts.stream().filter(p -> p.content().equals("Market")).findFirst().orElseThrow();
        TextComponent intro = parts.stream().filter(p -> p.content().startsWith("You discovered")).findFirst().orElseThrow();
        assertEquals(NamedTextColor.GREEN, name.color());
        assertEquals(NamedTextColor.AQUA, intro.color());
    }

    @Test
    void typePhrases() {
        assertEquals("the town", DiscoveryMessages.typePhrase("Town"));
        assertEquals("the district", DiscoveryMessages.typePhrase("District"));
        assertEquals("the structure", DiscoveryMessages.typePhrase("Structure"));
        assertEquals("the gate", DiscoveryMessages.typePhrase("GateStructure"));
        assertEquals("the place", DiscoveryMessages.typePhrase(null));
    }

    @Test
    void replaySummaryCountsThePlacesAndShowsTheTotals() {
        List<String> lines = plain(DiscoveryMessages.replaySummary(SUMMARY, result(List.of(town(), district()), List.of(ROYAL))));

        assertEquals(List.of(
                "While the server was busy you discovered 2 place(s):",
                "  Total: 2,600 ×1.2 Royal = +3,120 coins",
                "  Total: +6 gems",
                "  Total: 60 ×1.2 Personal = +72 XP"), lines);
    }
}
