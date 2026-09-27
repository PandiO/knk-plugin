package net.knightsandkings.knk.paper.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Currency smoke test: the Player manager's reason prompt printed
 * {@code &8[&6KnK&8] &rType 'cancel' to cancel.} - messages.prefix is written with {@code &} codes.
 */
class ChatCapturePrefixTest {

    @Test
    void thePrefixHasItsColourCodesApplied() {
        KnkConfig config = mock(KnkConfig.class);
        KnkConfig.MessagesConfig messages = mock(KnkConfig.MessagesConfig.class);
        when(messages.prefix()).thenReturn("&8[&6KnK&8] &r");
        when(config.messages()).thenReturn(messages);

        ChatCaptureManager manager = new ChatCaptureManager(null, config, Logger.getLogger("test"));

        assertEquals("§8[§6KnK§8] §r", manager.prefix());
    }
}
