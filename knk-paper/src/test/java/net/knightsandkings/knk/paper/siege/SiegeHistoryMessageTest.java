package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.ports.api.SiegeMatchHistoryApi.PlayerMatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alpha run 1 (PandiO/knk-workspace#174): {@code /siege history}. */
class SiegeHistoryMessageTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static void collectUrls(Component c, List<String> out) {
        ClickEvent<?> click = c.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.OPEN_URL) out.add(((ClickEvent.Payload.Text) click.payload()).value());
        c.children().forEach(child -> collectUrls(child, out));
    }

    private static PlayerMatch match(long id, String outcome, Integer kills, Integer deaths) {
        return new PlayerMatch(id, "Siege - Cinix", "Siege of Cinix", Instant.parse("2026-10-25T19:10:00Z"), "InstantVictory",
                "Raiders", "RED", outcome, kills, deaths, 120, 12);
    }

    @Test
    void listsEachMatchWithItsLink_andThePlayersProfile() {
        Component msg = SiegeHistoryMessage.render("Carol", List.of(match(41, "Win", 4, 1), match(40, "Left", 0, 2)),
                "https://knk.example", ZoneOffset.UTC);

        String text = plain(msg);
        assertTrue(text.contains("25 Oct 19:10 Win Siege of Cinix (Raiders) 4 K / 1 D [details]"), text);
        assertTrue(text.contains("Left Siege of Cinix (Raiders) 0 K / 2 D"), text);
        List<String> urls = new ArrayList<>();
        collectUrls(msg, urls);
        assertEquals(List.of("https://knk.example/siege/matches/41", "https://knk.example/siege/matches/40",
                "https://knk.example/players/Carol"), urls);
    }

    @Test
    void withoutAPublicUrlThereAreNoLinks() {
        Component msg = SiegeHistoryMessage.render("Carol", List.of(match(41, "Win", null, null)), "", ZoneOffset.UTC);

        List<String> urls = new ArrayList<>();
        collectUrls(msg, urls);
        assertTrue(urls.isEmpty());
        assertTrue(plain(msg).contains("? K / ? D"), plain(msg));
    }

    @Test
    void noMatchesYet() {
        assertTrue(plain(SiegeHistoryMessage.render("Carol", List.of(), "https://knk.example", ZoneOffset.UTC))
                .contains("You haven't finished a siege match yet."));
    }
}
