package net.knightsandkings.knk.paper.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/**
 * {@link ModeService#addModeListener} (destination engine phase 4, PandiO/knk-workspace#62, DESIGN § 8, A24, A25):
 * a listener hears every change of a player's in-session mode (on and off, with or without the action bar), never a
 * re-apply of the same mode, and a failing listener does not stop the others or the switch.
 */
class ModeServiceListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private ModeService modes;
    private final List<String> heard = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        modes = new ModeService(MockBukkit.createMockPlugin(), mock(KnkPermissible.class), mock(UserCache.class), null);
        modes.addModeListener((p, mode) -> {
            throw new IllegalStateException("a failing listener");
        });
        modes.addModeListener((p, mode) -> heard.add(p.getName() + ":" + mode));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void everyChangeIsHeardOnceAndARepeatIsNot() {
        modes.applyMode(player, ActiveMode.STAFF, true);
        modes.applyMode(player, ActiveMode.STAFF, true);
        modes.applyMode(player, ActiveMode.NONE, false);
        assertEquals(List.of(player.getName() + ":STAFF", player.getName() + ":NONE"), heard);
        assertEquals(ActiveMode.NONE, modes.getActiveMode(player));
    }
}
