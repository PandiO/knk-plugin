package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import net.knightsandkings.knk.paper.listeners.DuelCombatListener;
import net.knightsandkings.knk.paper.listeners.DuelCommandFilterListener;
import net.knightsandkings.knk.paper.listeners.DuelDeathRespawnListener;
import net.knightsandkings.knk.paper.listeners.DuelInventoryGuardListener;
import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels Phase 4 (KNG-217): the combat listener matrix (DESIGN §8.1) and the other duel listeners on a real
 * runtime - WorldGuard's PvP block, battleground entry/confinement, the countdown freeze, the command filter, the
 * inventory guard and the death handling.
 */
class DuelListenersTest {

    @TempDir
    Path dataFolder;

    private DuelHarness h;
    private final Player outsider = mock(Player.class);
    private final Set<UUID> frozen = new java.util.HashSet<>();

    private DuelHarness harness() {
        h = new DuelHarness(dataFolder);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        return h;
    }

    private DuelMatch duel() {
        return h.duel(h.contract(GearMode.OWN_GEAR_KEEP, null, Stake.NONE, Stake.NONE)).match();
    }

    private static EntityDamageByEntityEvent hit(Object damager, Player victim, boolean cancelledBefore) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn((org.bukkit.entity.Entity) damager);
        when(event.isCancelled()).thenReturn(cancelledBefore);
        when(event.getFinalDamage()).thenReturn(4.0);
        return event;
    }

    private DuelCombatListener combat() {
        return new DuelCombatListener(h.service, frozen::contains, (player, message) -> { });
    }

    // ===== the combat matrix =====

    @Test
    void theFightingPairIsUncancelledEvenInANoPvpRegion() {
        harness();
        duel();
        h.toFight();
        EntityDamageByEntityEvent event = hit(h.alice.player(), h.bob.player(), true);

        combat().onDamage(event);

        verify(event).setCancelled(false);
    }

    @Test
    void anArrowCountsAsItsShooter() {
        harness();
        duel();
        h.toFight();
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(h.alice.player());
        EntityDamageByEntityEvent event = hit(arrow, h.bob.player(), true);

        combat().onDamage(event);

        verify(event).setCancelled(false);
    }

    @Test
    void theSameMatchOutsideTheFightIsCancelled() {
        harness();
        DuelMatch match = duel();
        assertEquals(DuelPhase.WARMUP, match.phase());
        EntityDamageByEntityEvent warmup = hit(h.alice.player(), h.bob.player(), false);
        combat().onDamage(warmup);
        verify(warmup).setCancelled(true);

        h.seconds(1);
        assertEquals(DuelPhase.BATTLE_COUNTDOWN, match.phase());
        EntityDamageByEntityEvent countdown = hit(h.bob.player(), h.alice.player(), false);
        combat().onDamage(countdown);
        verify(countdown).setCancelled(true);
    }

    @Test
    void aDuelistAndAnOutsiderCantHurtEachOtherEitherWay() {
        harness();
        duel();
        h.toFight();
        EntityDamageByEntityEvent in = hit(outsider, h.alice.player(), false);
        EntityDamageByEntityEvent out = hit(h.alice.player(), outsider, false);

        combat().onDamage(in);
        combat().onDamage(out);

        verify(in).setCancelled(true);
        verify(out).setCancelled(true);
    }

    @Test
    void hitsWithoutADuelistAreLeftAlone() {
        harness();
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = hit(outsider, other, true);

        combat().onDamage(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void anAdminFrozenDuelistIsNeverUncancelled() {
        harness();
        duel();
        h.toFight();
        frozen.add(h.bob.id());
        EntityDamageByEntityEvent event = hit(h.alice.player(), h.bob.player(), true);

        combat().onDamage(event);

        verify(event, never()).setCancelled(false);
    }

    @Test
    void theRegistryLetsTheFightingPairThroughTheEnchantmentSafezone() {
        harness();
        duel();
        assertFalse(h.registry.allowsCombat(h.alice.id(), h.bob.id()), "warmup");
        h.toFight();
        assertTrue(h.registry.allowsCombat(h.alice.id(), h.bob.id()));
        assertFalse(h.registry.allowsCombat(h.alice.id(), outsider.getUniqueId()));
    }

    @Test
    void worldGuardsPvpBlockIsLiftedForTheFightingPairOnly() {
        // DuelWorldGuardPvpListener cancels DisallowedPVPEvent exactly when this answers true (WorldGuard's Bukkit
        // jar isn't on the test classpath).
        harness();
        duel();
        assertFalse(h.service.liftsWorldGuardPvpBlock(h.alice.id(), h.bob.id()), "warmup");

        h.toFight();

        assertTrue(h.service.liftsWorldGuardPvpBlock(h.alice.id(), h.bob.id()));
        assertFalse(h.service.liftsWorldGuardPvpBlock(outsider.getUniqueId(), h.bob.id()));
    }

    @Test
    void theTelemetryCountsAttemptsHitsAndDamage() {
        harness();
        DuelMatch match = duel();
        h.toFight();
        DuelCombatListener listener = combat();

        listener.onDamageRecorded(hit(h.alice.player(), h.bob.player(), false));
        listener.onDamageRecorded(hit(h.alice.player(), h.bob.player(), true));

        DuelMatch.Counters alice = match.counters.get(net.knightsandkings.knk.core.duel.DuelSide.CHALLENGER);
        assertEquals(2, alice.attackAttempts);
        assertEquals(1, alice.hits);
        assertEquals(4.0, alice.damageDealt);
        assertEquals(4.0, match.counters.get(net.knightsandkings.knk.core.duel.DuelSide.OPPONENT).damageTaken);
    }

    // ===== battleground access =====

    @Test
    void theAllocatedPairMayEnterAndIsConfinedInTheCountdownAndTheFight() {
        harness();
        DuelMatch match = duel();
        String region = "arena_2,a-battleground";
        assertFalse(h.access.exemptFromEntry(h.alice.id(), region), "not before the countdown");

        h.seconds(1);
        assertEquals(DuelPhase.BATTLE_COUNTDOWN, match.phase());
        assertTrue(h.access.exemptFromEntry(h.alice.id(), region));
        assertFalse(h.access.exemptFromEntry(outsider.getUniqueId(), region));
        assertEquals(DuelBattlegroundAccess.CONFINED, h.access.confinement(h.alice.id(), region).orElseThrow());
        h.seconds(1);
        assertTrue(h.access.confinement(h.bob.id(), region).isPresent(), "the fight too");

        h.kill(h.bob);
        h.respawn(h.bob);
        assertTrue(h.access.confinement(h.alice.id(), region).isEmpty(), "ENDING: free to go");
        h.seconds(1);
        assertFalse(h.access.hasGrant(h.alice.id()), "revoked after the exit teleport");
        assertFalse(h.access.hasGrant(h.bob.id()));
    }

    @Test
    void theExitPassLetsOneExitThroughWhileConfined() {
        DuelBattlegroundAccess access = new DuelBattlegroundAccess();
        UUID id = UUID.randomUUID();
        access.grant(id, "bg", player -> true);
        assertTrue(access.confinement(id, "BG").isPresent());

        access.passExit(id);

        assertTrue(access.confinement(id, "bg").isEmpty());
        access.revoke(id);
        assertFalse(access.exemptFromEntry(id, "bg"));
    }

    // ===== freeze, commands, guards, death =====

    @Test
    void theCountdownFreezesTheDuelistOnTheSpawnBlockButLetsThemLookAround() {
        harness();
        duel();
        h.seconds(1);
        DuelInventoryGuardListener guard = new DuelInventoryGuardListener(h.service, p -> false);
        Location to = new Location(h.world, 12, 64, 10, 90f, 5f);
        PlayerMoveEvent move = mock(PlayerMoveEvent.class);
        when(move.getPlayer()).thenReturn(h.alice.player());
        when(move.getFrom()).thenReturn(h.spawnA);
        when(move.getTo()).thenReturn(to);

        guard.onMove(move);

        verify(move).setTo(new Location(h.world, 10, 64, 10, 90f, 5f));
    }

    @Test
    void onlyTheAllowListRunsOnTheBattleground() {
        harness();
        duel();
        DuelCommandFilterListener filter = new DuelCommandFilterListener(h.service, () -> List.of("/duel", "/msg"), null,
            p -> false);
        PlayerCommandPreprocessEvent warmupSpawn = command("/spawn");
        filter.onCommand(warmupSpawn);
        verify(warmupSpawn, never()).setCancelled(true);

        h.seconds(1);
        PlayerCommandPreprocessEvent spawn = command("/spawn");
        PlayerCommandPreprocessEvent msg = command("/msg Bob gg");
        filter.onCommand(spawn);
        filter.onCommand(msg);
        verify(spawn).setCancelled(true);
        verify(msg, never()).setCancelled(true);
    }

    private PlayerCommandPreprocessEvent command(String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(h.alice.player());
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    @Test
    void duelistsCantDropItemsFromTheLockInUntilTheirGearIsBack() {
        harness();
        duel();
        DuelInventoryGuardListener guard = new DuelInventoryGuardListener(h.service, p -> false);
        PlayerDropItemEvent drop = new PlayerDropItemEvent(h.alice.player(), mock(org.bukkit.entity.Item.class));

        guard.onDrop(drop);

        assertTrue(drop.isCancelled());
    }

    @Test
    void aDeathInKeepModeKeepsTheInventoryAndDropsNothing() {
        harness();
        duel();
        h.toFight();
        h.bob.dead = true;
        List<ItemStack> drops = new ArrayList<>(List.of(h.items.item("bob-sword")));
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(h.bob.player());
        when(event.getDrops()).thenReturn(drops);

        new DuelDeathRespawnListener(h.service, Runnable::run).onDeath(event);

        verify(event).setKeepInventory(true);
        verify(event).setKeepLevel(true);
        assertTrue(drops.isEmpty());
    }
}
