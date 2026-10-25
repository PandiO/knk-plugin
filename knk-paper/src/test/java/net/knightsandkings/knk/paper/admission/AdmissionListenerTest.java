package net.knightsandkings.knk.paper.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.admission.AdmissionPolicy.Mode;
import net.knightsandkings.knk.core.domain.admission.AdmissionResult;
import net.knightsandkings.knk.core.ports.api.AdmissionApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class AdmissionListenerTest {
    private static final UUID PLAYER = UUID.fromString("0f3c5a5e-1b7c-4d2a-9f1e-2a4b6c8d0e1f");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AdmissionResult CODE = new AdmissionResult(false, AdmissionResult.REASON_NOT_ADMITTED,
        "K7QH-M2XA", NOW.plusSeconds(10 * 60));

    private final AtomicInteger calls = new AtomicInteger();

    private static AdmissionSettings settings(Mode mode, long timeoutMs) {
        return new AdmissionSettings(mode, timeoutMs, "https://knightsandkings.net/link", null, null, null, null);
    }

    private AdmissionApi answering(CompletableFuture<AdmissionResult> answer) {
        return (uuid, name) -> {
            calls.incrementAndGet();
            return answer;
        };
    }

    private static AsyncPlayerPreLoginEvent event() {
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getLoginResult()).thenReturn(Result.ALLOWED);
        when(event.getUniqueId()).thenReturn(PLAYER);
        when(event.getName()).thenReturn("Steve");
        return event;
    }

    private static String disallowedWith(AsyncPlayerPreLoginEvent event) {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(event).disallow(org.mockito.ArgumentMatchers.eq(Result.KICK_WHITELIST), message.capture());
        return PlainTextComponentSerializer.plainText().serialize(message.getValue());
    }

    @Test
    void aStrangerIsDisallowedWithTheCodeOnTheScreen() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 3000),
            answering(CompletableFuture.completedFuture(CODE)), uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        String screen = disallowedWith(event);
        assertTrue(screen.startsWith("Knights & Kings is invite-only.\n"), screen);
        assertTrue(screen.contains("Go to https://knightsandkings.net/link and enter\nK7QH-M2XA\n(valid 10 minutes)"), screen);
        assertTrue(screen.contains("Never give this code to anyone"), screen);
    }

    @Test
    void theCodeIsNotLogged() {
        Logger logger = Logger.getLogger(AdmissionListener.class.getName());
        StringBuilder log = new StringBuilder();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                log.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            new AdmissionListener(settings(Mode.INVITE, 3000), answering(CompletableFuture.completedFuture(CODE)),
                uuid -> false, uuid -> false, CLOCK).onPreLogin(event());
        } finally {
            logger.removeHandler(capture);
        }
        assertTrue(log.toString().contains("admission.kick"), log.toString());
        assertFalse(log.toString().contains("K7QH"), log.toString());
    }

    @Test
    void anAdmittedPlayerIsLetThrough() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 3000),
            answering(CompletableFuture.completedFuture(AdmissionResult.admittedResult())), uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        verify(event, never()).disallow(any(Result.class), any(Component.class));
    }

    @Test
    void anOperatorBypassesWithoutAnApiCall() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 3000),
            answering(CompletableFuture.completedFuture(CODE)), uuid -> true, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        verify(event, never()).disallow(any(Result.class), any(Component.class));
        assertEquals(0, calls.get());
    }

    @Test
    void whitelistModeDoesNothingAndNeverCallsTheApi() {
        var listener = new AdmissionListener(settings(Mode.WHITELIST, 3000),
            answering(CompletableFuture.completedFuture(CODE)), uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        verify(event, never()).disallow(any(Result.class), any(Component.class));
        assertEquals(0, calls.get());
    }

    @Test
    void aTimeoutLetsAKnownPlayerIn() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 50),
            answering(new CompletableFuture<>()), uuid -> false, uuid -> true, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        verify(event, never()).disallow(any(Result.class), any(Component.class));
    }

    @Test
    void aTimeoutTurnsAStrangerAwayWithUnavailable() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 50),
            answering(new CompletableFuture<>()), uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        assertTrue(disallowedWith(event).startsWith("Can't check your access right now."));
    }

    @Test
    void anApiErrorUsesTheOfflinePath() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 3000),
            answering(CompletableFuture.failedFuture(new RuntimeException("connection refused"))),
            uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        assertTrue(disallowedWith(event).startsWith("Can't check your access right now."));
    }

    @Test
    void aDeactivatedAccountIsTurnedAway() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 3000),
            answering(CompletableFuture.completedFuture(
                new AdmissionResult(false, AdmissionResult.REASON_DEACTIVATED, null, null))),
            uuid -> false, uuid -> true, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        assertTrue(disallowedWith(event).startsWith("This account is deactivated."));
    }

    @Test
    void anAlreadyDisallowedLoginIsLeftAlone() {
        var listener = new AdmissionListener(settings(Mode.INVITE, 3000),
            answering(CompletableFuture.completedFuture(CODE)), uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();
        when(event.getLoginResult()).thenReturn(Result.KICK_BANNED);

        listener.onPreLogin(event);

        verify(event, never()).disallow(any(Result.class), any(Component.class));
        assertEquals(0, calls.get());
    }

    @Test
    void withoutALinkPageTheMessageSaysTheWebsite() {
        var listener = new AdmissionListener(new AdmissionSettings(Mode.INVITE, 3000, "", null, null, null, null),
            answering(CompletableFuture.completedFuture(CODE)), uuid -> false, uuid -> false, CLOCK);
        AsyncPlayerPreLoginEvent event = event();

        listener.onPreLogin(event);

        assertTrue(disallowedWith(event).contains("Go to the website and enter"));
    }
}
