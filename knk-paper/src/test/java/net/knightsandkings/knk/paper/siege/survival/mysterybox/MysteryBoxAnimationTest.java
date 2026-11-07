package net.knightsandkings.knk.paper.siege.survival.mysterybox;

import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxReelPlan;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The reel entities and stepping (DESIGN §8a). The World mock is a field (CLAUDE.md). */
public class MysteryBoxAnimationTest {

    private final World world = mock(World.class);
    private final NamespacedKey tag = new NamespacedKey("knk", "survival_box");
    private final ManualScheduler scheduler = new ManualScheduler();
    private final ItemDisplay item = entity(ItemDisplay.class);
    private final TextDisplay label = entity(TextDisplay.class);
    private final Interaction hitbox = entity(Interaction.class);
    private final List<Integer> shown = new ArrayList<>();
    private final List<Boolean> finished = new ArrayList<>();
    private final List<String> effectLog = new ArrayList<>();
    private final MysteryBoxAnimation.Effects effects = new MysteryBoxAnimation.Effects() {
        @Override
        public void step(World w, Location at, float pitch) {
            effectLog.add("step " + pitch);
        }

        @Override
        public void finale(World w, Location at) {
            effectLog.add("finale");
        }
    };

    public static <T extends Entity> T entity(Class<T> type) {
        T e = mock(type);
        when(e.getUniqueId()).thenReturn(UUID.randomUUID());
        when(e.isValid()).thenReturn(true);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(e.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(), eq(PersistentDataType.STRING))).thenReturn(true);
        return e;
    }

    @SuppressWarnings("unchecked")
    MysteryBoxAnimationTest() {
        when(world.spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class))).thenAnswer(inv -> {
            ((Consumer<ItemDisplay>) inv.getArgument(2)).accept(item);
            return item;
        });
        when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenAnswer(inv -> {
            ((Consumer<TextDisplay>) inv.getArgument(2)).accept(label);
            return label;
        });
        when(world.spawn(any(Location.class), eq(Interaction.class), any(Consumer.class))).thenAnswer(inv -> {
            ((Consumer<Interaction>) inv.getArgument(2)).accept(hitbox);
            return hitbox;
        });
    }

    private MysteryBoxAnimation animation(MysteryBoxReelPlan plan) {
        return new MysteryBoxAnimation(scheduler, effects, tag, new Location(world, 5, 64, 5), plan, id -> {
            shown.add(id);
            return mock(ItemStack.class);
        }, id -> Component.text("entry " + id), () -> finished.add(true));
    }

    @Test
    void stepsThroughTheReelWithTheEaseOutAndFinishesOnTheResult() {
        MysteryBoxAnimation animation = animation(MysteryBoxReelPlan.of(List.of(1, 2, 3, 9), 9, 6));
        animation.start();
        assertEquals(List.of(1), shown, "the first face shows at once");
        assertTrue(animation.owns(hitbox));
        assertTrue(animation.owns(item));
        assertFalse(animation.owns(mock(Zombie.class)));
        verify(hitbox).setResponsive(true);
        verify(item).setPersistent(false);
        assertEquals(1, scheduler.pending.size());
        assertEquals(3, scheduler.runAll());
        assertEquals(List.of(1, 2, 3, 9), shown);
        assertEquals(List.of(true), finished);
        assertTrue(animation.finished());
        assertEquals(3, animation.step());
        assertEquals(6L, (long) scheduler.delays.get(scheduler.delays.size() - 1), "slowest step last");
        assertEquals(4, effectLog.size());
        assertEquals("finale", effectLog.get(3));
        assertTrue(effectLog.get(0).startsWith("step"));
        verify(label, atLeastOnce()).text(any(Component.class));
    }

    @Test
    void removeStopsTheReelAndRemovesEveryEntity() {
        MysteryBoxAnimation animation = animation(MysteryBoxReelPlan.of(List.of(1, 2, 3), 3, 4));
        animation.start();
        animation.remove();
        assertEquals(0, scheduler.runAll());
        assertEquals(List.of(1), shown);
        assertTrue(finished.isEmpty());
        verify(item).remove();
        verify(label).remove();
        verify(hitbox).remove();
        animation.remove();
        verify(item, org.mockito.Mockito.times(1)).remove();
        assertFalse(animation.owns(hitbox));
    }

    @Test
    void aSingleEntryReelFinishesAtOnce() {
        MysteryBoxAnimation animation = animation(MysteryBoxReelPlan.of(List.of(), 7, 4));
        animation.start();
        assertEquals(List.of(true), finished);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    void sweepRemovesTaggedDisplayAndInteractionEntitiesOnly() {
        Zombie zombie = entity(Zombie.class);
        assertEquals(3, MysteryBoxAnimation.sweep(List.of(item, label, hitbox, zombie), tag));
        verify(zombie, never()).remove();
        verify(item).remove();
    }
}
