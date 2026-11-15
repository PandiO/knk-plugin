package net.knightsandkings.knk.paper.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.core.teleport.CombatTagBook;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.npc.nav.NpcEntities;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;
import net.knightsandkings.knk.paper.user.JoinLoadingGuard;

/**
 * NAV-08 (PandiO/knk-workspace#490): player-shaped NPCs are {@code Player}s, so the damage handlers of the player
 * systems in this package return at once for an NPC victim or attacker. Each guard has an NPC case (no side effect)
 * and a player case (the guard lets players through). Damage events can't be built without a server, so they are
 * mocked.
 */
class NpcDamageGuardsTest {

    private static Player player(boolean npc) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasMetadata(NpcEntities.METADATA_KEY)).thenReturn(npc);
        return player;
    }

    private static EntityDamageByEntityEvent hit(Player attacker, Player victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        return event;
    }

    private static EntityDamageEvent damage(Player victim) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.DROWNING);
        return event;
    }

    // ---- AdminFreezeListener ----

    @Test
    void anNpcIsNeverLookedUpAsFrozen() {
        AdminFreezeManager freeze = mock(AdminFreezeManager.class);
        when(freeze.isFrozen(any())).thenReturn(true);
        EntityDamageEvent event = damage(player(true));
        new AdminFreezeListener(mock(Plugin.class), freeze, mock(UsersDataAccess.class)).onDamage(event);
        verifyNoInteractions(freeze);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void aFrozenPlayerStillTakesNoDamage() {
        AdminFreezeManager freeze = mock(AdminFreezeManager.class);
        when(freeze.isFrozen(any())).thenReturn(true);
        EntityDamageEvent event = damage(player(false));
        new AdminFreezeListener(mock(Plugin.class), freeze, mock(UsersDataAccess.class)).onDamage(event);
        verify(event).setCancelled(true);
    }

    // ---- CombatTagListener ----

    @Test
    void aFightWithAnNpcTagsNobody() {
        TeleportService teleports = mock(TeleportService.class);
        CombatTagListener listener = new CombatTagListener(teleports);
        listener.onDamage(hit(player(false), player(true)));
        listener.onDamage(hit(player(true), player(false)));
        verifyNoInteractions(teleports);
    }

    @Test
    void aFightBetweenPlayersStillTags() {
        TeleportService teleports = mock(TeleportService.class);
        when(teleports.combatTags()).thenReturn(mock(CombatTagBook.class));
        new CombatTagListener(teleports).onDamage(hit(player(false), player(false)));
        verify(teleports).now();
    }

    // ---- EnchantmentCombatListener ----

    @Test
    void anNpcWeaponRunsNoEnchantments() {
        EnchantmentRepository repository = mock(EnchantmentRepository.class);
        EnchantmentExecutor executor = mock(EnchantmentExecutor.class);
        Player npc = player(true);
        EntityDamageByEntityEvent event = hit(npc, player(false));
        when(event.getEntity()).thenReturn(mock(Zombie.class));
        new EnchantmentCombatListener(repository, executor, false).onEntityDamage(event);
        verify(npc, never()).getInventory();
        verifyNoInteractions(repository, executor);
    }

    @Test
    void aPlayerAttackerStillReachesTheWeaponCheck() {
        Player attacker = player(false);
        when(attacker.getInventory()).thenReturn(mock(PlayerInventory.class));
        EntityDamageByEntityEvent event = hit(attacker, player(false));
        new EnchantmentCombatListener(mock(EnchantmentRepository.class), mock(EnchantmentExecutor.class), false)
            .onEntityDamage(event);
        verify(attacker).getInventory();
    }

    // ---- JoinLoadingRestrictionListener ----

    @Test
    void anNpcAttackerIsTreatedLikeAMob() {
        JoinLoadingGuard guard = mock(JoinLoadingGuard.class);
        when(guard.isLoading(any())).thenReturn(true);
        EntityDamageByEntityEvent event = hit(player(true), player(false));
        new JoinLoadingRestrictionListener(guard).onDamage(event);
        verifyNoInteractions(guard);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void aLoadingPlayerStillCannotBeHitByAPlayer() {
        JoinLoadingGuard guard = mock(JoinLoadingGuard.class);
        Player victim = player(false);
        when(guard.isLoading(victim.getUniqueId())).thenReturn(true);
        EntityDamageByEntityEvent event = hit(player(false), victim);
        new JoinLoadingRestrictionListener(guard).onDamage(event);
        verify(event).setCancelled(true);
    }

    @Test
    void aLoadingPlayerStillCannotHitAnNpc() {
        JoinLoadingGuard guard = mock(JoinLoadingGuard.class);
        Player attacker = player(false);
        when(guard.isLoading(attacker.getUniqueId())).thenReturn(true);
        EntityDamageByEntityEvent event = hit(attacker, player(true));
        new JoinLoadingRestrictionListener(guard).onDamage(event);
        verify(event).setCancelled(true);
    }

    // ---- SiegeCombatListener ----

    @SuppressWarnings("unchecked")
    @Test
    void anNpcIsNoSiegeCombatant() {
        SiegeService siege = mock(SiegeService.class);
        Predicate<UUID> locked = mock(Predicate.class);
        SiegeCombatListener listener = new SiegeCombatListener(siege, locked);
        EntityDamageByEntityEvent npcHit = hit(player(false), player(true));
        EntityDamageByEntityEvent npcHits = hit(player(true), player(false));
        listener.onDamage(npcHit);
        listener.onDamage(npcHits);
        verifyNoInteractions(siege, locked);
        verify(npcHit, never()).setCancelled(anyBoolean());
        verify(npcHits, never()).setCancelled(anyBoolean());
    }

    @Test
    void playersStillGoThroughTheSiegeRules() {
        SiegeService siege = mock(SiegeService.class);
        Player attacker = player(false);
        new SiegeCombatListener(siege, id -> false).onDamage(hit(attacker, player(false)));
        verify(siege).combatantOf(attacker);
    }

    // ---- TeleportWarmupListener ----

    @Test
    void damageToAnNpcCancelsNoWarmup() {
        TeleportService teleports = mock(TeleportService.class);
        new TeleportWarmupListener(teleports).onDamage(damage(player(true)));
        verifyNoInteractions(teleports);
    }

    @Test
    void damageToAPlayerStillCancelsTheirWarmup() {
        TeleportService teleports = mock(TeleportService.class);
        Player player = player(false);
        new TeleportWarmupListener(teleports).onDamage(damage(player));
        verify(teleports).cancelWarmup(player.getUniqueId(), WarmupCancelReason.DAMAGED);
    }
}
