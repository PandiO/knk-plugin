package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.paper.enchantment.display.EffectBossBars;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.ToLongBiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#202: the cooldown store shows a cooldown bar for the player who used an ability (SUPPORT
 * abilities, Freeze, Cleaving's charged strike) and removes the bars on {@code /ce cooldown clear}.
 */
class CooldownBarCooldownManagerTest {

    private final List<Runnable> mainThread = new ArrayList<>();
    private final Executor queued = mainThread::add;
    private final UUID player = UUID.randomUUID();
    private EffectBossBars bars;
    private InMemoryCooldownManager store;
    private CooldownBarCooldownManager cooldowns;

    @BeforeEach
    void setUp() {
        bars = mock(EffectBossBars.class);
        store = new InMemoryCooldownManager();
        cooldowns = new CooldownBarCooldownManager(store, bars,
                CooldownBarCooldownManager.defaultLabels(EnchantmentRegistry.getInstance()), queued);
    }

    private void runMainThread() {
        List<Runnable> tasks = new ArrayList<>(mainThread);
        mainThread.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void theAbilitiesWithABar_AreTheSupportOnes_FreezeAndCleaving() {
        Map<String, String> labels = cooldowns.labels();
        assertEquals("Flash Chaos cooldown", labels.get("flash_chaos"));
        assertEquals("Chaos cooldown", labels.get("chaos"));
        assertEquals("Health Boost cooldown", labels.get("health_boost"));
        assertEquals("Armor Repair cooldown", labels.get("armor_repair"));
        assertEquals("Resistance cooldown", labels.get("resistance"));
        assertEquals("Invisibility cooldown", labels.get("invisibility"));
        assertEquals("Freeze cooldown", labels.get("freeze"));
        assertEquals("Cleaving strike cooldown", labels.get("cleaving"));
        assertFalse(labels.containsKey("poison"));
        assertFalse(labels.containsKey("strength"));
        assertEquals(8, labels.size());
    }

    @Test
    void applyingACooldownStoresItAndShowsTheBarOnTheMainThread() {
        cooldowns.applyCooldown(player, "flash_chaos", 90_000L).join();

        assertTrue(store.getRemainingCooldown(player, "flash_chaos").join() > 89_000L);
        verify(bars, never()).cooldown(eq(player), anyString(), anyString(), anyLong());
        runMainThread();
        verify(bars).cooldown(player, "flash_chaos", "Flash Chaos cooldown", 90_000L);
    }

    @Test
    void anOnHitEnchantmentWithoutABarOnlyStoresItsCooldown() {
        cooldowns.applyCooldown(player, "poison", 8_000L).join();
        runMainThread();

        assertTrue(cooldowns.getRemainingCooldown(player, "poison").join() > 0L);
        verify(bars, never()).cooldown(eq(player), anyString(), anyString(), anyLong());
    }

    @Test
    void aZeroCooldownShowsNoBar() {
        cooldowns.applyCooldown(player, "chaos", 0L).join();
        runMainThread();
        verify(bars, never()).cooldown(eq(player), anyString(), anyString(), anyLong());
    }

    @Test
    void clearingCooldownsClearsTheStoreAndTheBars() {
        cooldowns.applyCooldown(player, "freeze", 20_000L).join();
        cooldowns.applyCooldown(player, "cleaving", 60_000L).join();

        cooldowns.clearCooldowns(player).join();
        runMainThread();

        assertEquals(0L, store.getRemainingCooldown(player, "freeze").join());
        assertEquals(0L, store.getRemainingCooldown(player, "cleaving").join());
        verify(bars).clearCooldowns(player);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theBarsReadTheRealRemainingCooldownFromTheStore() {
        ArgumentCaptor<ToLongBiFunction<UUID, String>> source = ArgumentCaptor.forClass(ToLongBiFunction.class);
        verify(bars).cooldownsFrom(source.capture());

        assertEquals(0L, source.getValue().applyAsLong(player, "chaos"));
        store.applyCooldown(player, "chaos", 90_000L).join();
        assertTrue(source.getValue().applyAsLong(player, "chaos") > 89_000L);
    }

    @Test
    void aCooldownStillRunningOnJoinShowsItsBarAgain() {
        store.applyCooldown(player, "invisibility", 45_000L).join();
        store.applyCooldown(player, "poison", 8_000L).join();
        Player joined = mock(Player.class);
        when(joined.getUniqueId()).thenReturn(player);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(joined);

        cooldowns.onJoin(join);

        ArgumentCaptor<Long> remaining = ArgumentCaptor.forClass(Long.class);
        verify(bars).cooldown(eq(player), eq("invisibility"), eq("Invisibility cooldown"), remaining.capture());
        assertTrue(remaining.getValue() > 44_000L && remaining.getValue() <= 45_000L);
        verify(bars, never()).cooldown(eq(player), eq("poison"), anyString(), anyLong());
    }
}
