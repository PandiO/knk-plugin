package net.knightsandkings.knk.paper.enchantment.display;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#211: the invisibility ability hides the invoker's armour and held items from every tracker
 * that doesn't see through the invisibility, with per-viewer equipment packets (so the invoker stays in the tab list),
 * and gives the real equipment back when it ends.
 */
class InvisibleEquipmentTest {

    /** Stand-ins for the Invisibility and Speed effects ({@code PotionEffectType} needs a running server). */
    private final Set<PotionEffect> invisibilities = new java.util.HashSet<>();
    private final List<Player> ended = new ArrayList<>();
    private final ItemStack empty = mock(ItemStack.class);
    private final ItemStack sword = mock(ItemStack.class);
    private final ItemStack helmet = mock(ItemStack.class);
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private final Set<Player> trackers = new LinkedHashSet<>();
    private final List<PotionEffect> invokerEffects = new ArrayList<>();
    private final Scoreboard shared = FakeBoards.board();

    private Player invoker;
    private Player enemy;
    private Player teammate;
    private InvisibleEquipment mask;

    @BeforeEach
    void setUp() {
        invoker = player("Sneaky");
        enemy = player("Enemy");
        teammate = player("Mate");
        when(invoker.getTrackedBy()).thenReturn(trackers);
        when(invoker.getActivePotionEffects()).thenReturn(invokerEffects);
        invokerEffects.add(invisibility());
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(equipment.getItem(EquipmentSlot.HAND)).thenReturn(sword);
        when(equipment.getItem(EquipmentSlot.HEAD)).thenReturn(helmet);
        when(invoker.getEquipment()).thenReturn(equipment);
        trackers.add(enemy);
        trackers.add(teammate);
        trackers.add(invoker); // the server never lists the entity itself, but a stray self entry must be skipped
        mask = new InvisibleEquipment(online::get, nextTick::add, () -> empty, invisibilities::contains, ended::add);
    }

    private Player player(String name) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.getScoreboard()).thenReturn(shared);
        online.put(id, p);
        return p;
    }

    private PotionEffect invisibility() {
        PotionEffect effect = mock(PotionEffect.class);
        invisibilities.add(effect);
        return effect;
    }

    private static PotionEffect speed() {
        return mock(PotionEffect.class);
    }

    @SuppressWarnings("unchecked")
    private Map<EquipmentSlot, ItemStack> lastSentTo(Player viewer) {
        ArgumentCaptor<Map<EquipmentSlot, ItemStack>> sent = ArgumentCaptor.forClass(Map.class);
        verify(viewer, org.mockito.Mockito.atLeastOnce()).sendEquipmentChange(eq(invoker), sent.capture());
        return sent.getValue();
    }

    private void assertMasked(Map<EquipmentSlot, ItemStack> items) {
        assertEquals(Set.copyOf(InvisibleEquipment.SLOTS), items.keySet());
        items.values().forEach(item -> assertSame(empty, item));
    }

    /** A siege board: the viewer and the invoker on one team that sees friendly invisibles. */
    private void sameSiegeTeam(Player viewer) {
        Scoreboard own = FakeBoards.board();
        Team team = own.registerNewTeam("siege_red");
        team.setCanSeeFriendlyInvisibles(true);
        team.addEntry(viewer.getName());
        team.addEntry(invoker.getName());
        when(viewer.getScoreboard()).thenReturn(own);
    }

    @Test
    void everyTrackerGetsEmptyArmourAndHands_TheInvokerIsNeverHiddenOrRemoved() {
        mask.hide(invoker);

        assertMasked(lastSentTo(enemy));
        assertMasked(lastSentTo(teammate));
        verify(invoker, never()).sendEquipmentChange(any(), anyMap());
        verify(enemy, never()).hidePlayer(any(), any());
        verify(enemy, never()).hideEntity(any(), any());
        assertTrue(mask.isHidden(invoker.getUniqueId()));
    }

    @Test
    void sameRankOnTheSharedBoardIsMasked_BecauseRankTeamsDontSeeFriendlyInvisibles() {
        Team rank = shared.registerNewTeam("tier_12");
        rank.setCanSeeFriendlyInvisibles(false);
        rank.addEntry("Sneaky");
        rank.addEntry("Enemy");

        mask.hide(invoker);

        assertMasked(lastSentTo(enemy));
    }

    @Test
    void siegeTeammatesAndSpectatorsKeepSeeingTheEquipment_TheOtherTeamDoesnt() {
        sameSiegeTeam(teammate);
        Scoreboard enemyBoard = FakeBoards.board();
        Team red = enemyBoard.registerNewTeam("siege_red");
        red.setCanSeeFriendlyInvisibles(false); // the enemy's own board shows the invoker's team as foes
        red.addEntry("Sneaky");
        when(enemy.getScoreboard()).thenReturn(enemyBoard);
        Player spectator = player("Watcher");
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        trackers.add(spectator);

        mask.hide(invoker);

        assertMasked(lastSentTo(enemy));
        verify(teammate, never()).sendEquipmentChange(any(), anyMap());
        verify(spectator, never()).sendEquipmentChange(any(), anyMap());
    }

    @Test
    void aViewerWhoJoinsTheInvokersTeamGetsTheRealEquipmentBack() {
        mask.hide(invoker);
        assertMasked(lastSentTo(teammate));
        sameSiegeTeam(teammate);
        clearInvocations(teammate);

        mask.tick();

        Map<EquipmentSlot, ItemStack> real = lastSentTo(teammate);
        assertSame(sword, real.get(EquipmentSlot.HAND));
        assertSame(helmet, real.get(EquipmentSlot.HEAD));
        assertSame(empty, real.get(EquipmentSlot.CHEST), "an empty slot is sent as empty");
    }

    @Test
    void milkExpiryOrDeathClearingInvisibilityGivesEveryMaskedViewerTheRealEquipment() {
        mask.hide(invoker);
        clearInvocations(enemy, teammate);

        EntityPotionEffectEvent other = potionEvent(speed(), EntityPotionEffectEvent.Action.CLEARED);
        mask.onPotionEffect(other);
        assertTrue(mask.isHidden(invoker.getUniqueId()), "another effect ending changes nothing");
        EntityPotionEffectEvent renewed = potionEvent(invisibility(), EntityPotionEffectEvent.Action.CHANGED);
        mask.onPotionEffect(renewed);
        assertTrue(mask.isHidden(invoker.getUniqueId()), "using the ability again keeps it");

        mask.onPotionEffect(potionEvent(invisibility(), EntityPotionEffectEvent.Action.CLEARED)); // milk

        assertFalse(mask.isHidden(invoker.getUniqueId()));
        assertSame(sword, lastSentTo(enemy).get(EquipmentSlot.HAND));
        assertSame(sword, lastSentTo(teammate).get(EquipmentSlot.HAND));
    }

    @Test
    void expiryAlsoEnds() {
        mask.hide(invoker);
        mask.onPotionEffect(potionEvent(invisibility(), EntityPotionEffectEvent.Action.REMOVED));
        assertFalse(mask.isHidden(invoker.getUniqueId()));
    }

    private EntityPotionEffectEvent potionEvent(PotionEffect old, EntityPotionEffectEvent.Action action) {
        EntityPotionEffectEvent event = mock(EntityPotionEffectEvent.class);
        when(event.getEntity()).thenReturn(invoker);
        when(event.getOldEffect()).thenReturn(old);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    @Test
    void deathEndsIt() {
        mask.hide(invoker);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(invoker);

        mask.onDeath(death);

        assertFalse(mask.isHidden(invoker.getUniqueId()));
        assertSame(sword, lastSentTo(enemy).get(EquipmentSlot.HAND));
    }

    @Test
    void quitEndsItAndRemovesTheAbilitysInvisibility_SoARelogIsVisible() {
        mask.hide(invoker);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(invoker);

        mask.onQuit(quit);

        assertFalse(mask.isHidden(invoker.getUniqueId()));
        assertEquals(List.of(invoker), ended);
    }

    @Test
    void aPlayerWhoWasNeverHiddenKeepsTheirPotionOnQuit() {
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(invoker);

        mask.onQuit(quit);

        assertTrue(ended.isEmpty());
    }

    @Test
    void pluginDisableShowsEveryoneAgainAndEndsTheInvisibility() {
        mask.hide(invoker);
        clearInvocations(enemy);

        mask.revealAll();

        assertFalse(mask.isHidden(invoker.getUniqueId()));
        assertSame(sword, lastSentTo(enemy).get(EquipmentSlot.HAND));
        assertEquals(List.of(invoker), ended);
    }

    @Test
    void aNewTrackerOrAnEquipmentChangeIsMaskedAgainOnTheNextTick() {
        mask.hide(invoker);
        Player newcomer = player("Newcomer");
        trackers.add(newcomer);
        PlayerTrackEntityEvent track = mock(PlayerTrackEntityEvent.class);
        when(track.getEntity()).thenReturn(invoker);
        when(track.getPlayer()).thenReturn(newcomer);

        mask.onTrack(track);
        verify(newcomer, never()).sendEquipmentChange(any(), anyMap()); // the spawn packets aren't out yet
        assertEquals(1, nextTick.size());
        nextTick.removeFirst().run();
        assertMasked(lastSentTo(newcomer));

        clearInvocations(enemy);
        EntityEquipmentChangedEvent changed = mock(EntityEquipmentChangedEvent.class);
        when(changed.getEntity()).thenReturn(invoker);
        mask.onEquipmentChanged(changed);
        nextTick.removeFirst().run();
        assertMasked(lastSentTo(enemy));
    }

    @Test
    void eventsAboutOtherEntitiesOrAfterTheEndDoNothing() {
        PlayerTrackEntityEvent zombie = mock(PlayerTrackEntityEvent.class);
        when(zombie.getEntity()).thenReturn(mock(Zombie.class));
        mask.onTrack(zombie);
        PlayerTrackEntityEvent notHidden = mock(PlayerTrackEntityEvent.class);
        when(notHidden.getEntity()).thenReturn(invoker);
        mask.onTrack(notHidden);
        assertTrue(nextTick.isEmpty());

        mask.hide(invoker);
        PlayerTrackEntityEvent track = mock(PlayerTrackEntityEvent.class);
        when(track.getEntity()).thenReturn(invoker);
        mask.onTrack(track);
        mask.reveal(invoker);
        clearInvocations(enemy);
        nextTick.removeFirst().run(); // ended before the tick came

        verify(enemy, never()).sendEquipmentChange(any(), anyMap());
    }

    @Test
    void tickDropsAnInvokerWhoLostInvisibilityWithoutAnEvent() {
        mask.hide(invoker);
        invokerEffects.clear();
        clearInvocations(enemy);

        mask.tick();

        assertFalse(mask.isHidden(invoker.getUniqueId()));
        assertSame(sword, lastSentTo(enemy).get(EquipmentSlot.HAND));
    }

    @Test
    void tickResendsTheMaskAndForgetsViewersOutOfRange() {
        mask.hide(invoker);
        trackers.remove(enemy);
        clearInvocations(teammate, enemy);

        mask.tick();
        assertMasked(lastSentTo(teammate));
        verify(enemy, never()).sendEquipmentChange(any(), anyMap());

        mask.reveal(invoker);
        verify(enemy, never()).sendEquipmentChange(any(), anyMap()); // its next spawn already shows the real items
        verify(teammate, times(2)).sendEquipmentChange(eq(invoker), anyMap());
    }

    @Test
    void anInvokerWhoWentOfflineIsForgottenOnTick() {
        mask.hide(invoker);
        online.remove(invoker.getUniqueId());

        mask.tick();

        assertFalse(mask.isHidden(invoker.getUniqueId()));
    }
}
