package net.knightsandkings.knk.paper.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.users.AccountLinkedNotice;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class AccountLinkedNotifierTest {
    private static PlayerNotification linked(AccountLinkedNotice notice) {
        return new PlayerNotification(5L, 7, "0f3c5a5e-1b7c-4d2a-9f1e-2a4b6c8d0e1f", "Steve",
            PlayerNotification.TYPE_ACCOUNT_LINKED, null, null, null, null, null, notice);
    }

    private static List<String> shown(Player player, int lines) {
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player, times(lines)).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    @Test
    void showsTheMaskedAddressAndTheWarning() {
        Player player = mock(Player.class);

        new AccountLinkedNotifier(null).accept(player, linked(new AccountLinkedNotice("st***@example.com")));

        assertEquals(List.of(
            "Your Minecraft account is now linked to the web account st***@example.com.",
            "Not you? Tell staff right away."), shown(player, 2));
    }

    @Test
    void withoutAPayloadTheAddressReadsStars() {
        Player player = mock(Player.class);

        new AccountLinkedNotifier("linked to {maskedEmail}").accept(player, linked(null));

        assertEquals(List.of("linked to ***"), shown(player, 1));
    }
}
