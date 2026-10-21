package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.duel.GearMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat lines of arena duels P5 (DESIGN §6.4). */
class DuelChatTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> commands(Component c) {
        List<String> out = new ArrayList<>();
        if (c.clickEvent() != null && c.clickEvent().action() == ClickEvent.Action.RUN_COMMAND) {
            out.add(((ClickEvent.Payload.Text) c.clickEvent().payload()).value());
        }
        for (Component child : c.children()) out.addAll(commands(child));
        return out;
    }

    @Test
    void theOfferLineMatchesTheDesignExample() {
        Component line = DuelChat.offer(new DuelChat.OfferText(42, "Pandi", false, "the Arena", 1, 3, 60,
                GearMode.OWN_GEAR_DROP, null, "500 coins", "Diamond Sword ★★★, 200 coins", true));
        String text = plain(line);
        assertTrue(text.startsWith("⚔ Pandi challenges you to a duel at the Arena (offer 1/3, 60 s) — "), text);
        assertTrue(text.contains("OWN GEAR, THE LOSER'S INVENTORY DROPS TO THE WINNER"), text);
        assertTrue(text.contains("You stake: 500 coins"), text);
        assertTrue(text.contains("Pandi stakes: Diamond Sword ★★★, 200 coins"), text);
        assertEquals(List.of("/duel view 42", "/duel accept 42", "/duel counter 42", "/duel decline 42"), commands(line));
    }

    @Test
    void theLastOfferAndItsWarningHaveNoCounter() {
        Component line = DuelChat.offer(new DuelChat.OfferText(7, "Bob", true, "the Arena", 3, 3, 60,
                GearMode.OWN_GEAR_KEEP, null, "nothing", "nothing", false));
        assertTrue(plain(line).contains("(last offer, 60 s)"));
        assertEquals(List.of("/duel view 7", "/duel accept 7", "/duel decline 7"), commands(line));
        assertEquals(List.of("/duel view 7", "/duel accept 7", "/duel decline 7"),
                commands(DuelChat.expiryWarning(7, "Bob", 10, false)));
    }

    @Test
    void stakesAndKitPrompt() {
        assertEquals("nothing", DuelChat.stake(0, List.of()));
        assertEquals("Sword, 1,500 coins", DuelChat.stake(1_500, List.of("Sword")));
        Component prompt = DuelChat.kitPrompt(List.of(1, 2), id -> id == 1 ? "Knight" : "Archer", 1, "Bob", 2);
        assertTrue(plain(prompt).contains("[✔ Knight]"));
        assertTrue(plain(prompt).contains("Bob picked Archer."));
        assertEquals(List.of("/duel kit 1", "/duel kit 2", "/duel kit"), commands(prompt));
    }
}
