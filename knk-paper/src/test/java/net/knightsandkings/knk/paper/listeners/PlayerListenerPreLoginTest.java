package net.knightsandkings.knk.paper.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;

/** Invitations (KNG-214): a login the admission gate turned away creates no account. */
class PlayerListenerPreLoginTest {
    private static final UUID PLAYER = UUID.fromString("0f3c5a5e-1b7c-4d2a-9f1e-2a4b6c8d0e1f");

    private static AsyncPlayerPreLoginEvent event(Result result) {
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getLoginResult()).thenReturn(result);
        when(event.getUniqueId()).thenReturn(PLAYER);
        when(event.getName()).thenReturn("Steve");
        return event;
    }

    private static PlayerListener listener(UsersDataAccess users) {
        return new PlayerListener(users, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void aDisallowedLoginCreatesNothing() {
        UsersDataAccess users = mock(UsersDataAccess.class);

        listener(users).onValidateLogin(event(Result.KICK_WHITELIST));

        verifyNoInteractions(users);
    }

    @Test
    void anAllowedLoginLoadsTheAccount() {
        UsersDataAccess users = mock(UsersDataAccess.class);

        listener(users).onValidateLogin(event(Result.ALLOWED));

        verify(users).getByUuidAsync(org.mockito.ArgumentMatchers.eq(PLAYER), any());
    }

    /** #486: the erasure catch-up runs before the account is looked up (or created) for a connecting player. */
    @Test
    void theErasureCatchUp_RunsBeforeTheAccountLookup() {
        UsersDataAccess users = mock(UsersDataAccess.class);
        List<String> order = new ArrayList<>();
        when(users.getByUuidAsync(org.mockito.ArgumentMatchers.eq(PLAYER), any())).thenAnswer(inv -> {
            order.add("lookup");
            return null;
        });
        PlayerListener listener = listener(users);
        listener.setErasureHooks(uuid -> order.add("catch-up " + uuid), uuid -> false);

        listener.onValidateLogin(event(Result.ALLOWED));

        assertEquals(List.of("catch-up " + PLAYER, "lookup"), order);
    }

    @Test
    void aDisallowedLogin_DoesNotWaitForTheCatchUp() {
        List<UUID> waited = new ArrayList<>();
        PlayerListener listener = listener(mock(UsersDataAccess.class));
        listener.setErasureHooks(waited::add, uuid -> false);

        listener.onValidateLogin(event(Result.KICK_WHITELIST));

        assertEquals(List.of(), waited);
    }
}
