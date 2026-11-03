package net.knightsandkings.knk.core.npc.modifier;

import net.knightsandkings.knk.core.npc.NpcStat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifierSetTest {
    private static final NpcStat SPEED = NpcStat.MOVEMENT_SPEED;
    private static final NpcStat DMG = NpcStat.MELEE_DAMAGE;

    @Test
    void putReplacesPerSourceAndStatAndBumpsRevisionOnlyOnChange() {
        ModifierSet set = new ModifierSet();
        assertEquals(0, set.revision());
        assertTrue(set.put(StatModifier.multiply("survival:round", SPEED, 1.1)));
        assertEquals(1, set.revision());
        assertFalse(set.put(StatModifier.multiply("survival:round", SPEED, 1.1)), "same modifier again is no change");
        assertEquals(1, set.revision());
        assertTrue(set.put(StatModifier.multiply("survival:round", SPEED, 1.2)));
        assertEquals(2, set.revision());
        assertEquals(1, set.forStat(SPEED).size());
        assertEquals(1.2, set.forStat(SPEED).get(0).value());
    }

    @Test
    void sourcesAreCaseInsensitiveAndIteratedByName() {
        ModifierSet set = new ModifierSet();
        set.put(StatModifier.multiply("Survival:Round", SPEED, 1.1));
        set.put(StatModifier.multiply("effect:slowness", SPEED, 0.85));
        set.put(StatModifier.add("ARMOR:bonus", DMG, 1.0));
        assertEquals(List.of("armor:bonus", "effect:slowness", "survival:round"), set.sources());
        assertEquals(List.of("effect:slowness", "survival:round"),
            set.forStat(SPEED).stream().map(StatModifier::source).toList());
        assertEquals(3, set.all().size());
    }

    @Test
    void removeBySourceDropsEveryStatOfThatSource() {
        ModifierSet set = new ModifierSet();
        set.putAll(List.of(StatModifier.multiply("survival:round", SPEED, 1.1),
            StatModifier.multiply("survival:round", DMG, 1.3),
            StatModifier.multiply("survival:per-player", DMG, 1.1)));
        long rev = set.revision();
        assertTrue(set.remove("survival:round"));
        assertEquals(rev + 1, set.revision());
        assertTrue(set.forStat(SPEED).isEmpty());
        assertEquals(1, set.forStat(DMG).size());
        assertFalse(set.remove("survival:round"), "removing again is no change");
        assertEquals(rev + 1, set.revision());
    }

    @Test
    void removeBySourceAndStatKeepsTheOtherStats() {
        ModifierSet set = new ModifierSet();
        set.put(StatModifier.multiply("s", SPEED, 1.1));
        set.put(StatModifier.multiply("s", DMG, 1.3));
        assertTrue(set.remove("s", SPEED));
        assertFalse(set.remove("s", SPEED));
        assertEquals(List.of("s"), set.sources());
        assertTrue(set.remove("s", DMG));
        assertTrue(set.isEmpty());
        assertTrue(set.sources().isEmpty());
    }

    @Test
    void clearIsAChangeOnlyWhenSomethingWasThere() {
        ModifierSet set = new ModifierSet();
        set.clear();
        assertEquals(0, set.revision());
        set.put(StatModifier.add("a", DMG, 1));
        set.clear();
        assertEquals(2, set.revision());
        assertTrue(set.isEmpty());
    }

    @Test
    void modifiersRejectNonFiniteValuesAndBlankSources() {
        assertThrows(IllegalArgumentException.class, () -> StatModifier.multiply("s", SPEED, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> StatModifier.add("s", SPEED, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> StatModifier.add("  ", SPEED, 1));
        assertTrue(StatModifier.add("s", SPEED, 0).isIdentity());
        assertTrue(StatModifier.multiply("s", SPEED, 1).isIdentity());
        assertFalse(StatModifier.multiply("s", SPEED, 0).isIdentity());
    }

    @Test
    void actorModifiersApplyToManyActorsAndRemoveASourceEverywhere() {
        ActorModifiers actors = new ActorModifiers();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        actors.putAll(List.of(a, b), List.of(StatModifier.multiply("survival:round", SPEED, 1.2)));
        actors.forActor(c).put(StatModifier.multiply("effect:slowness", SPEED, 0.5));
        assertEquals(3, actors.size());
        assertTrue(actors.find(a).isPresent());
        assertEquals(1.2, actors.forActor(b).forStat(SPEED).get(0).value());
        assertEquals(2, actors.removeSource("survival:round"));
        assertEquals(0, actors.removeSource("survival:round"));
        assertTrue(actors.forActor(a).isEmpty());
        assertEquals(1, actors.forActor(c).forStat(SPEED).size());
        actors.forget(c);
        assertTrue(actors.find(c).isEmpty());
        actors.clear();
        assertEquals(0, actors.size());
    }
}
