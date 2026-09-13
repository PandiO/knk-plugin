package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.GateDoorsApi;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers item 6.3/6.4's GateDoorRegionCaptureHandler - specifically the untracked-player early
 * returns in {@link GateDoorRegionCaptureHandler#isHandling}, {@link
 * GateDoorRegionCaptureHandler#cancel}, and {@link GateDoorRegionCaptureHandler#onPlayerChat}.
 *
 * <p>NOTE: disabled for the same reason as {@code WorldGuardIntegrationTest} - WorldEdit is a
 * compileOnly dependency, unavailable on the test classpath. Unlike that class, none of the three
 * methods tested here actually call into the WorldEdit API on the untracked-player path being
 * exercised, but the JVM still fails to verify {@code GateDoorRegionCaptureHandler}'s bytecode
 * (its other methods reference WorldEdit types like {@code Region}/{@code RegionSelector} in
 * their signatures) the moment the class is loaded - confirmed directly: even bare construction
 * throws {@code NoClassDefFoundError} here, before any method body runs. So this suite can't run
 * even partially in this environment; it documents the intended behavior and will run once
 * WorldEdit is available on the test classpath (or if these tests are ever moved to a module that
 * has it). The functionality works at runtime, matching WorldGuardIntegrationTest's note.
 */
@Disabled("WorldEdit is compileOnly dependency, not available in test classpath - see class Javadoc")
class GateDoorRegionCaptureHandlerTest {
    private GateDoorRegionCaptureHandler handler;
    private Player mockPlayer;

    @BeforeEach
    void setUp() {
        Plugin mockPlugin = mock(Plugin.class);
        GateDoorsApi mockGateDoorsApi = mock(GateDoorsApi.class);
        handler = new GateDoorRegionCaptureHandler(mockPlugin, mockGateDoorsApi);
        mockPlayer = mock(Player.class);
    }

    @Test
    void isHandling_ForPlayerWithNoActiveCapture_ReturnsFalse() {
        assertFalse(handler.isHandling(mockPlayer));
    }

    @Test
    void cancel_ForPlayerWithNoActiveCapture_DoesNotThrowOrMessage() {
        assertDoesNotThrow(() -> handler.cancel(mockPlayer));
        verify(mockPlayer, never()).sendMessage(anyString());
    }

    @Test
    void onPlayerChat_ForPlayerWithNoActiveCapture_ReturnsFalseAndDoesNotConsumeMessage() {
        assertFalse(handler.onPlayerChat(mockPlayer, "save"));
        assertFalse(handler.onPlayerChat(mockPlayer, "cancel"));
        assertFalse(handler.onPlayerChat(mockPlayer, "anything else"));
    }
}
