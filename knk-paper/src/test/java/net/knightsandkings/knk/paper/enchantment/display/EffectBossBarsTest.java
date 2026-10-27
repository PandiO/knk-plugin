package net.knightsandkings.knk.paper.enchantment.display;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#170: one boss bar per timed custom-enchantment debuff, counting down and following the real
 * effect; a static "Frozen" bar for a staff freeze; gone on end, death and quit.
 */
class EffectBossBarsTest {

    private final AtomicLong now = new AtomicLong(0L);
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<BossBar> shown = new ArrayList<>();
    // PotionEffectType is registry-backed (no server in tests): the bars read the potion through these instead.
    private final Map<String, PotionEffect> potions = new HashMap<>();
    private final Function<Player, PotionEffect> poisonType = p -> potions.get("poison");
    private final Function<Player, PotionEffect> nauseaType = p -> potions.get("nausea");
    private final long[] freezeRemaining = {0L};
    private EffectBossBars bars;
    private Player player;

    @BeforeEach
    void setUp() {
        bars = new EffectBossBars(null, online::get, now::get);
        bars.remainingFrom("freeze", id -> freezeRemaining[0]);
        player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        doAnswer(inv -> shown.add(inv.getArgument(0))).when(player).showBossBar(any(BossBar.class));
        doAnswer(inv -> shown.remove(inv.<BossBar>getArgument(0))).when(player).hideBossBar(any(BossBar.class));
        online.put(id, player);
    }

    @Test
    void oneBarPerDebuff_CountingDown() {
        potions.put("poison", effect(80));
        bars.debuff(player, "poison", "Poison", poisonType, 80);
        freezeRemaining[0] = 2_000L;
        bars.debuff(player, "freeze", "Frozen", (Function<Player, PotionEffect>) null, 40);

        assertEquals(List.of("Poison · 4s", "Frozen · 2s"), texts());

        now.set(1_000L);
        potions.put("poison", effect(60));
        freezeRemaining[0] = 1_000L;
        bars.tick();
        assertEquals(List.of("Poison · 3s", "Frozen · 1s"), texts());
        assertEquals(0.75f, shown.get(0).progress(), 0.0001f);
    }

    @Test
    void theSameDebuffAgainRestartsItsBarInsteadOfAddingOne() {
        potions.put("poison", effect(80));
        bars.debuff(player, "poison", "Poison", poisonType, 80);
        now.set(2_000L);
        potions.put("poison", effect(80));
        bars.debuff(player, "poison", "Poison", poisonType, 80);

        assertEquals(1, shown.size());
        assertEquals("Poison · 4s", texts().get(0));
        assertEquals(1.0f, shown.get(0).progress(), 0.0001f);
    }

    @Test
    void aBarEndsWithItsEffect_MilkOrAnEarlyUnfreeze() {
        potions.put("poison", effect(80));
        bars.debuff(player, "poison", "Poison", poisonType, 80);
        freezeRemaining[0] = 2_000L;
        bars.debuff(player, "freeze", "Frozen", (Function<Player, PotionEffect>) null, 40);

        potions.clear();          // milk
        freezeRemaining[0] = 0L;  // unfrozen
        now.set(500L);
        bars.tick();

        assertTrue(shown.isEmpty());
        assertFalse(bars.has(player.getUniqueId(), "poison"));
    }

    @Test
    void aBarRunsOutOnTime() {
        bars.debuff(player, "nausea", "Nausea", (Function<Player, PotionEffect>) null, 20);
        now.set(1_000L);
        bars.tick();
        assertTrue(shown.isEmpty());
    }

    @Test
    void staffFreezeIsAStaticFrozenBar_ThatDeathKeepsAndQuitClears() {
        bars.staffFrozen(player.getUniqueId(), true);
        potions.put("poison", effect(80));
        bars.debuff(player, "poison", "Poison", poisonType, 80);
        assertEquals(List.of("Frozen", "Poison · 4s"), texts());
        assertEquals(1.0f, shown.get(0).progress(), 0.0001f);

        now.set(3_600_000L);
        potions.put("poison", effect(80)); // still there, a later copy
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        bars.onDeath(death);
        assertEquals(List.of("Frozen"), texts());

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        bars.onQuit(quit);
        assertTrue(shown.isEmpty());
        assertFalse(bars.has(player.getUniqueId(), EffectBossBars.STAFF_FREEZE));
    }

    @Test
    void unfreezingByStaffHidesTheFrozenBar() {
        bars.staffFrozen(player.getUniqueId(), true);
        bars.staffFrozen(player.getUniqueId(), false);
        assertTrue(shown.isEmpty());
    }

    @Test
    void aLongerRunningCopyOfThePotionKeepsItsTimeOnTheBar() {
        potions.put("nausea", effect(250));
        bars.debuff(player, "nausea", "Nausea", nauseaType, 60);
        assertEquals("Nausea · 13s", texts().get(0));
    }

    @Test
    void creaturesGetNoBar() {
        Zombie zombie = mock(Zombie.class);
        bars.debuff(zombie, "poison", "Poison", poisonType, 80);
        verifyNoInteractions(zombie);
    }

    @Test
    void hideAllOnDisable() {
        bars.debuff(player, "poison", "Poison", (Function<Player, PotionEffect>) null, 80);
        bars.staffFrozen(player.getUniqueId(), true);
        bars.hideAll();
        assertTrue(shown.isEmpty());
    }

    private static PotionEffect effect(int ticks) {
        PotionEffect effect = mock(PotionEffect.class);
        when(effect.getDuration()).thenReturn(ticks);
        when(effect.isInfinite()).thenReturn(false);
        return effect;
    }

    private List<String> texts() {
        List<String> texts = new ArrayList<>();
        for (BossBar bar : shown) {
            Component name = bar.name();
            texts.add(((TextComponent) name).content());
        }
        return texts;
    }
}
