package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.NpcStatPipeline;
import net.knightsandkings.knk.core.npc.modifier.StatModifier;
import net.knightsandkings.knk.core.survival.scaling.ScaledStat;
import net.knightsandkings.knk.core.survival.spawn.SpawnZone;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import net.knightsandkings.knk.paper.npc.FakeMobAttributes;
import net.knightsandkings.knk.paper.npc.NpcPlatform;
import net.knightsandkings.knk.paper.siege.npc.MatchActorRegistry;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.WorldPoint;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ZoneSpec;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import net.knightsandkings.knk.paper.npc.NpcAttribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DESIGN §7/S42: the round modifiers, the spawn location and the wiring of a spawned mob (actor, platform, health). */
class SurvivalSpawnerTest {

    private final World world = mock(World.class);
    private final Plugin plugin = mock(Plugin.class);
    private final FakeMobAttributes attributes = new FakeMobAttributes();
    private final NpcStatPipeline pipeline = NpcStatPipeline.vanilla();
    private final NpcPlatform platform;
    private final MatchActorRegistry actors;
    private final SurvivalSpawner spawner;

    SurvivalSpawnerTest() {
        when(plugin.getName()).thenReturn("knk");
        when(plugin.namespace()).thenReturn("knk");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        platform = new NpcPlatform(plugin, pipeline, attributes, null, () -> 0L, 96);
        actors = new MatchActorRegistry(plugin);
        when(world.getName()).thenReturn("w");
        spawner = new SurvivalSpawner(platform, actors, name -> "w".equals(name) ? world : null, new java.util.Random(1),
            SurvivalSettings.defaults(), null, type -> type == EntityType.ZOMBIE ? Zombie.class : null);
    }

    @Test
    void roundModifiersScaleDamagesAndSpeedAndDivideCooldownsByAttackSpeed() {
        Map<ScaledStat, Double> m = Map.of(ScaledStat.MELEE_DAMAGE, 1.5, ScaledStat.RANGED_DAMAGE, 1.2,
            ScaledStat.WALKING_SPEED, 1.1, ScaledStat.ATTACK_SPEED, 2.0, ScaledStat.HEALTH, 3.0);
        List<StatModifier> mods = SurvivalSpawner.roundModifiers(m);
        Map<NpcStat, Double> byStat = new HashMap<>();
        mods.forEach(mod -> byStat.put(mod.stat(), mod.value()));
        assertEquals(1.5, byStat.get(NpcStat.MELEE_DAMAGE));
        assertEquals(1.2, byStat.get(NpcStat.PROJECTILE_DAMAGE));
        assertEquals(1.1, byStat.get(NpcStat.MOVEMENT_SPEED));
        assertEquals(0.5, byStat.get(NpcStat.MELEE_COOLDOWN));
        assertEquals(0.5, byStat.get(NpcStat.RANGED_COOLDOWN));
        assertEquals(0.5, byStat.get(NpcStat.RANGED_DRAW));
        assertTrue(mods.stream().allMatch(mod -> mod.source().equals(SurvivalSpawner.ROUND_SOURCE)));
        // Round 1: identity everywhere.
        assertTrue(SurvivalSpawner.roundModifiers(Map.of()).stream().allMatch(StatModifier::isIdentity));
    }

    @Test
    void unknownOrNonMobTypesAreNotSpawnable() {
        assertTrue(spawner.mobClassOf("zombie").isPresent());
        assertTrue(spawner.mobClassOf("NOT_A_MOB").isEmpty());
        assertTrue(spawner.mobClassOf("ARROW").isEmpty(), "no entity class registered for it in this test");
    }

    private ZoneSpec zone(List<WorldPoint> spawns, OptionalDouble radius) {
        return new ZoneSpec(SpawnZone.initial("10", 1, new net.knightsandkings.knk.core.survival.SurvivalPoint(100, 64, 100)),
            "Outside", "w", radius, 1, OptionalInt.empty(), true, true, false, spawns, List.of());
    }

    @Test
    void spawnLocationUsesASpawnPointOrTheAreaAroundTheAnchor() {
        when(world.isChunkLoaded(any(Integer.class), any(Integer.class))).thenReturn(false);
        ZoneSpec points = zone(List.of(new WorldPoint("w", 10, 64, 10, 90f)), OptionalDouble.empty());
        Location at = spawner.spawnLocation(points).orElseThrow();
        assertTrue(Math.abs(at.getX() - 10) <= 1.5 && Math.abs(at.getZ() - 10) <= 1.5);
        assertEquals(64, at.getY());
        ZoneSpec area = zone(List.of(), OptionalDouble.of(5));
        Location around = spawner.spawnLocation(area).orElseThrow();
        assertTrue(Math.hypot(around.getX() - 100, around.getZ() - 100) <= 5.01);
        assertTrue(spawner.spawnLocation(new ZoneSpec(SpawnZone.initial("11", 1, new net.knightsandkings.knk.core.survival.SurvivalPoint(0, 0, 0)),
            "x", "unloaded", OptionalDouble.of(2), 1, OptionalInt.empty(), true, true, false, List.of(), List.of())).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSpawnedMobIsAnActorAdoptedByThePlatformWithScaledHealth() {
        when(world.isChunkLoaded(any(Integer.class), any(Integer.class))).thenReturn(false);
        Zombie zombie = mock(Zombie.class);
        UUID id = UUID.randomUUID();
        when(zombie.getUniqueId()).thenReturn(id);
        when(zombie.isValid()).thenReturn(true);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        when(zombie.getHealth()).thenReturn(20.0);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> values = new HashMap<>();
        doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(2))).when(pdc)
            .set(any(NamespacedKey.class), org.mockito.ArgumentMatchers.<PersistentDataType<String, String>>any(), org.mockito.ArgumentMatchers.anyString());
        when(zombie.getPersistentDataContainer()).thenReturn(pdc);
        attributes.base.put(NpcAttribute.MAX_HEALTH, 20.0);
        attributes.base.put(NpcAttribute.MOVEMENT_SPEED, 0.23);
        attributes.base.put(NpcAttribute.FOLLOW_RANGE, 35.0);
        when(world.spawn(any(Location.class), eq(Zombie.class), eq(CreatureSpawnEvent.SpawnReason.CUSTOM), any(Consumer.class)))
            .thenAnswer(inv -> {
                ((Consumer<Zombie>) inv.getArgument(3)).accept(zombie);
                return zombie;
            });

        Map<ScaledStat, Double> round3 = Map.of(ScaledStat.HEALTH, 1.2, ScaledStat.WALKING_SPEED, 1.1);
        Optional<Mob> spawned = spawner.spawn(new PlannedEnemy(7, 3, "ZOMBIE", false),
            zone(List.of(new WorldPoint("w", 10, 64, 10, 0f)), OptionalDouble.empty()), 1, 2, 0.5, round3);

        assertTrue(spawned.isPresent());
        verify(zombie).setPersistent(false);
        verify(zombie).setRemoveWhenFarAway(false);
        verify(zombie).setShouldBurnInDay(false);
        assertEquals(Set.of("1:2"), Set.copyOf(values.values().stream().map(Object::toString).toList()), "tagged for the leftover sweep");
        assertEquals(1, actors.actors(1).size());
        assertEquals(0.5, actors.actorOf((Entity) zombie).orElseThrow().captureWeight());
        assertTrue(platform.isAdopted(id));
        assertEquals(24.0, attributes.maxHealth.get(id), 1e-9, "20 × 1.2");
        double speedDelta = attributes.modifiers.get(id + ":" + NpcAttribute.MOVEMENT_SPEED + ":knk:npc_speed");
        assertEquals(0.23 * 1.1 - 0.23, speedDelta, 1e-9, "the speed modifier adds the scaled delta");
        assertEquals(0.23 * 1.1, pipeline.resolveSpeed(id, "zombie").value(), 1e-9);
    }
}
