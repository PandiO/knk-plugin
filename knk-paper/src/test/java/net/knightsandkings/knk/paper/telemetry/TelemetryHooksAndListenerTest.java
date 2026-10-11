package net.knightsandkings.knk.paper.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.telemetry.TelemetryBuffer;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryCorrelation;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import net.knightsandkings.knk.paper.menu.MenuObserver;
import net.knightsandkings.knk.paper.user.PlayerUserData;

/**
 * Baseline families with a hook (KNG-34 link 6, acceptance criteria 1-2): sessions, AFK, menus,
 * command labels (never arguments), discoveries, API failures, gates; enhanced menu clicks, hits and
 * gate hits only for enhanced players. The World mock is held in a field (CLAUDE.md World-mock rule).
 */
class TelemetryHooksAndListenerTest {

    final TelemetryBuffer buffer = new TelemetryBuffer(100);
    final TelemetryEmitter emitter = TelemetryEmitterTest.emitter(buffer);
    final TelemetryHooks hooks = new TelemetryHooks(emitter);
    final List<Runnable> nextTick = new ArrayList<>();
    final TelemetryListener listener = new TelemetryListener(emitter, nextTick::add);
    final World world = mock(World.class);
    final Player alice = player(TelemetryEmitterTest.ALICE);
    final Player bob = player(TelemetryEmitterTest.BOB);

    Player player(java.util.UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getWorld()).thenReturn(world);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return p;
    }

    List<TelemetryEvent> drain() {
        return buffer.drain(100);
    }

    TelemetryEvent only() {
        List<TelemetryEvent> events = drain();
        assertEquals(1, events.size(), events.toString());
        return events.get(0);
    }

    void enhance(int userId) {
        emitter.updateConfig(new TelemetryClientConfig(true, Set.of(userId), List.of(), Set.of(), Set.of(), Set.of()));
    }

    @AfterEach
    void endCorrelation() {
        nextTick.forEach(Runnable::run);
    }

    @Test
    void sessionJoinAndLeave_withKicksTold() {
        when(world.getName()).thenReturn("world");
        listener.onUserDataLoaded(new UserDataLoadedEvent(alice, mock(PlayerUserData.class)));
        TelemetryEvent join = only();
        assertEquals(TelemetryEventNames.SESSION_JOIN, join.name());
        assertEquals("world", join.payload().get("world"));
        assertEquals("survival", join.payload().get("gameMode"));

        PlayerKickEvent kick = mock(PlayerKickEvent.class);
        when(kick.getPlayer()).thenReturn(bob);
        listener.onKick(kick);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(bob);
        listener.onQuit(quit);
        listener.onQuit(quit);
        List<TelemetryEvent> leaves = drain();
        assertEquals(List.of("kick", "quit"), leaves.stream().map(TelemetryEvent::reasonCode).toList());
        assertEquals(8, leaves.get(0).userId());
    }

    @Test
    void commands_recordTheLabelOnly_andCorrelateUntilTheNextTick() {
        listener.onCommand(command(alice, "/pay Bob 100 thanks for the help", false));

        TelemetryEvent e = only();
        assertEquals("pay", e.payload().get("command"));
        assertFalse(e.toString().contains("Bob"), "no arguments anywhere in the event");
        assertFalse(e.toString().contains("thanks"));
        assertEquals(e.correlationId(), TelemetryCorrelation.current(), "the command's API calls share the id");
        nextTick.forEach(Runnable::run);
        nextTick.clear();
        assertNull(TelemetryCorrelation.current());

        listener.onCommand(command(alice, "/msg bob secret", true));
        TelemetryEvent denied = only();
        assertEquals(TelemetryEvent.Outcome.DENIED, denied.outcome());
        assertNull(TelemetryCorrelation.current());
    }

    static PlayerCommandPreprocessEvent command(Player player, String message, boolean cancelled) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        when(event.isCancelled()).thenReturn(cancelled);
        return event;
    }

    @Test
    void commandLabels_areSanitised() {
        assertEquals("siege", TelemetryListener.labelOf("/SIEGE join 2"));
        assertEquals("knk:stats", TelemetryListener.labelOf("/knk:stats"));
        assertNull(TelemetryListener.labelOf("/ "));
        assertNull(TelemetryListener.labelOf(null));
        assertEquals(32, TelemetryListener.labelOf("/" + "a".repeat(50)).length());
    }

    @Test
    void menus_openedActionsAndEnhancedClicks() {
        hooks.menuOpened(alice, "statistics.main", "profile.main");
        hooks.actionExecuted(alice, "siege.lobby", "siege.join", 13, MenuObserver.ActionOutcome.DENIED);
        hooks.slotClicked(alice, "siege.lobby", 13, "42", "LEFT");
        List<TelemetryEvent> baseline = drain();
        assertEquals(List.of(TelemetryEventNames.MENU_OPENED, TelemetryEventNames.MENU_ACTION), baseline.stream().map(TelemetryEvent::name).toList());
        assertEquals("profile.main", baseline.get(0).payload().get("parentMenuKey"));
        assertEquals(TelemetryEvent.Outcome.DENIED, baseline.get(1).outcome());
        assertEquals("condition_failed", baseline.get(1).reasonCode());

        enhance(7);
        hooks.slotClicked(alice, "siege.lobby", 13, "42", "LEFT");
        hooks.slotClicked(bob, "siege.lobby", 13, "42", "LEFT");
        TelemetryEvent click = only();
        assertEquals(TelemetryEventNames.MENU_CLICK, click.name());
        assertEquals(TelemetryEvent.Level.ENHANCED, click.level());
    }

    @Test
    void afkDiscoveriesAndApiFailures() {
        hooks.afkChanged(alice, true, false);
        DiscoveryGrant grant = mock(DiscoveryGrant.class);
        when(grant.domainId()).thenReturn(31);
        when(grant.domainType()).thenReturn("Town");
        DiscoveryGrantResult result = mock(DiscoveryGrantResult.class);
        when(result.granted()).thenReturn(List.of(grant));
        hooks.discoveriesGranted(alice, result);
        hooks.callFailed("PUT", "/api/Users/{id}/balances", 500, null, "corr-5");

        List<TelemetryEvent> events = drain();
        assertEquals("afk_idle", events.get(0).reasonCode());
        assertEquals(31, events.get(1).payload().get("domainId"));
        TelemetryEvent failure = events.get(2);
        assertEquals((TelemetryEventNames.API_CALL_FAILED), failure.name());
        assertEquals(("http_500"), failure.reasonCode());
        assertEquals("corr-5", failure.correlationId());
        assertEquals("/api/Users/{id}/balances", failure.payload().get("route"));
    }

    @Test
    void hits_onlyForEnhancedPlayers() {
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getDamager()).thenReturn(alice);
        when(hit.getEntity()).thenReturn(bob);
        when(hit.getEntityType()).thenReturn(EntityType.PLAYER);
        when(hit.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(hit.getFinalDamage()).thenReturn(4.567);

        listener.onDamage(hit);
        assertEquals(0, buffer.size());

        enhance(8);
        listener.onDamage(hit);
        TelemetryEvent e = only();
        assertEquals(TelemetryEventNames.COMBAT_HIT, e.name());
        assertEquals(8, e.userId(), "the enhanced player (the victim) owns the event");
        assertEquals(7, e.payload().get("attackerUserId"));
        assertEquals(4.6, e.payload().get("damage"));
    }

    @Test
    void gates_destroyedOnce_andHitsForEnhancedAttackers() {
        CachedGateDoor gate = mock(CachedGateDoor.class);
        when(gate.getId()).thenReturn(9);
        GateDoorDamageEvent damage = new GateDoorDamageEvent(gate, GateDoorDamageEvent.Cause.values()[0], alice, null);

        listener.onGateDamage(damage);
        assertEquals(0, buffer.size());

        when(gate.isDestroyed()).thenReturn(true);
        listener.onGateDamage(damage);
        listener.onGateDamage(damage);
        TelemetryEvent destroyed = only();
        assertEquals(TelemetryEventNames.SIEGE_GATE_DESTROYED, destroyed.name());
        assertEquals("9", destroyed.objectId());

        enhance(7);
        when(gate.isDestroyed()).thenReturn(false);
        listener.onGateDamage(damage);
        assertEquals(TelemetryEventNames.GATE_HIT, only().name());
        assertTrue(TelemetryListener.playerOf(alice) == alice);
    }
}
