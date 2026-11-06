package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.modifier.StatModifier;
import net.knightsandkings.knk.core.survival.scaling.ScaledStat;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import net.knightsandkings.knk.paper.npc.NpcPlatform;
import net.knightsandkings.knk.paper.siege.npc.MatchActorRegistry;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.WorldPoint;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ZoneSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import net.knightsandkings.knk.paper.npc.NpcAttribute;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Spawns one planned enemy as a vanilla monster (DESIGN S42) at a spawnzone (S22, §6): a random {@code Spawn} point
 * of the zone, or a random point within the zone's area radius around its anchor, plus a small spread. The mob is
 * non-persistent (never saved with the chunk, so a crash leaves nothing behind), never despawns by distance, doesn't
 * burn in daylight, is registered as a match actor (link S3: capture presence, gate damage) and adopted by the NPC
 * platform with this round's stat modifiers (DESIGN §7: health through the max-health attribute, the other four
 * through the pipeline as {@code survival:round}; round 1 = vanilla). Main thread.
 */
public final class SurvivalSpawner {

    public static final String ROUND_SOURCE = "survival:round";

    /** World lookup seam (tests). */
    public interface Worlds {
        World byName(String name);

        static Worlds bukkit() {
            return Bukkit::getWorld;
        }
    }

    private final NpcPlatform platform;
    private final MatchActorRegistry actors;
    private final Worlds worlds;
    private final RandomGenerator random;
    private final SurvivalSettings settings;
    private final Logger logger;
    private final Function<EntityType, Class<? extends Entity>> entityClasses;

    public SurvivalSpawner(NpcPlatform platform, MatchActorRegistry actors, Worlds worlds, RandomGenerator random,
                           SurvivalSettings settings, Logger logger) {
        this(platform, actors, worlds, random, settings, logger, EntityType::getEntityClass);
    }

    SurvivalSpawner(NpcPlatform platform, MatchActorRegistry actors, Worlds worlds, RandomGenerator random,
                    SurvivalSettings settings, Logger logger, Function<EntityType, Class<? extends Entity>> entityClasses) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.random = Objects.requireNonNull(random, "random");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = logger;
        this.entityClasses = entityClasses;
    }

    /** The entity type of a planned enemy's monster type id ({@code "ZOMBIE"}), empty for an unknown or non-mob type. */
    public Optional<Class<? extends Mob>> mobClassOf(String monsterTypeId) {
        EntityType type;
        try {
            type = EntityType.valueOf(monsterTypeId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Class<? extends Entity> cls = entityClasses.apply(type);
        if (cls == null || !Mob.class.isAssignableFrom(cls)) return Optional.empty();
        @SuppressWarnings("unchecked")
        Class<? extends Mob> mobClass = (Class<? extends Mob>) cls;
        return Optional.of(mobClass);
    }

    /** A spawn location in the zone: a random spawn point, or a random point in the area; empty when the world isn't loaded. */
    public Optional<Location> spawnLocation(ZoneSpec zone) {
        World world = worlds.byName(zone.world());
        if (world == null) return Optional.empty();
        double x;
        double y;
        double z;
        float yaw = 0f;
        if (!zone.spawnPoints().isEmpty()) {
            WorldPoint p = zone.spawnPoints().get(random.nextInt(zone.spawnPoints().size()));
            x = p.x();
            y = p.y();
            z = p.z();
            yaw = p.yaw();
        } else {
            double radius = zone.areaRadius().orElse(0);
            double angle = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * radius;
            x = zone.zone().anchor().x() + Math.cos(angle) * r;
            y = zone.zone().anchor().y();
            z = zone.zone().anchor().z() + Math.sin(angle) * r;
        }
        double spread = settings.spawnSpreadRadius();
        if (spread > 0 && !zone.spawnPoints().isEmpty()) {
            x += (random.nextDouble() * 2 - 1) * spread;
            z += (random.nextDouble() * 2 - 1) * spread;
        }
        Location at = new Location(world, x, y, z, yaw, 0f);
        return Optional.of(snapToGround(at));
    }

    /** Puts the location on the highest solid block at or below it within a few blocks (a point authored mid-air or in the floor). */
    static Location snapToGround(Location at) {
        World world = at.getWorld();
        if (world == null || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return at;
        int y = at.getBlockY();
        // Up to 3 blocks up if the point is inside a block, then down to the floor.
        int steps = 0;
        while (steps < 3 && world.getBlockAt(at.getBlockX(), y, at.getBlockZ()).isSolid()) {
            y++;
            steps++;
        }
        steps = 0;
        while (steps < 6 && y > world.getMinHeight() && !world.getBlockAt(at.getBlockX(), y - 1, at.getBlockZ()).isSolid()) {
            y--;
            steps++;
        }
        return new Location(world, at.getBlockX() + 0.5, y, at.getBlockZ() + 0.5, at.getYaw(), 0f);
    }

    /**
     * Spawns the planned enemy and wires it up.
     *
     * @param multipliers the round's stat multipliers ({@code WaveScaling.statMultipliers}); 1.0 everywhere in round 1
     * @return the mob, or empty when the type is unknown, the world isn't loaded or the spawn failed
     */
    public Optional<Mob> spawn(PlannedEnemy enemy, ZoneSpec zone, int lobbyId, int npcTeamId, double captureWeight,
                               Map<ScaledStat, Double> multipliers) {
        Optional<Class<? extends Mob>> cls = mobClassOf(enemy.monsterType());
        if (cls.isEmpty()) {
            if (logger != null) logger.warning("[Survival] Monster type " + enemy.monsterType() + " is not a spawnable mob; enemy " + enemy.id() + " skipped");
            return Optional.empty();
        }
        Optional<Location> at = spawnLocation(zone);
        if (at.isEmpty()) {
            if (logger != null) logger.warning("[Survival] World " + zone.world() + " of spawnzone " + zone.name() + " is not loaded");
            return Optional.empty();
        }
        Mob mob;
        try {
            mob = at.get().getWorld().spawn(at.get(), cls.get(), SpawnReason.CUSTOM, this::prepare);
        } catch (RuntimeException e) {
            if (logger != null) logger.warning("[Survival] Spawning " + enemy.monsterType() + " failed: " + e.getMessage());
            return Optional.empty();
        }
        if (mob == null || !mob.isValid()) return Optional.empty();
        actors.register(mob, lobbyId, npcTeamId, captureWeight);
        platform.pipeline().modifiers().forActor(mob.getUniqueId()).putAll(roundModifiers(multipliers));
        platform.adopt(mob, NpcPlatform.profileIdOf(mob));
        applyHealth(mob, multipliers.getOrDefault(ScaledStat.HEALTH, 1.0));
        return Optional.of(mob);
    }

    private void prepare(Mob mob) {
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        if (mob instanceof Zombie zombie) zombie.setShouldBurnInDay(false);
        if (mob instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
    }

    /** Max health = vanilla base × the round's health multiplier, healed to full (DESIGN §7; round 1 = vanilla). */
    void applyHealth(Mob mob, double healthMultiplier) {
        double base = platform.attributes().base(mob, NpcAttribute.MAX_HEALTH, mob.getHealth());
        double max = Math.max(1.0, base * Math.max(0.01, healthMultiplier));
        platform.attributes().setMaxHealth(mob, max);
    }

    /** The {@code survival:round} modifiers for the pipeline (S2 notes): damages and speed multiply, cooldowns divide by attack speed. */
    public static List<StatModifier> roundModifiers(Map<ScaledStat, Double> multipliers) {
        List<StatModifier> out = new ArrayList<>();
        double melee = multipliers.getOrDefault(ScaledStat.MELEE_DAMAGE, 1.0);
        double ranged = multipliers.getOrDefault(ScaledStat.RANGED_DAMAGE, 1.0);
        double speed = multipliers.getOrDefault(ScaledStat.WALKING_SPEED, 1.0);
        double attackSpeed = multipliers.getOrDefault(ScaledStat.ATTACK_SPEED, 1.0);
        out.add(StatModifier.multiply(ROUND_SOURCE, NpcStat.MELEE_DAMAGE, positive(melee)));
        out.add(StatModifier.multiply(ROUND_SOURCE, NpcStat.PROJECTILE_DAMAGE, positive(ranged)));
        out.add(StatModifier.multiply(ROUND_SOURCE, NpcStat.MOVEMENT_SPEED, positive(speed)));
        double cooldownFactor = 1.0 / positive(attackSpeed);
        out.add(StatModifier.multiply(ROUND_SOURCE, NpcStat.MELEE_COOLDOWN, cooldownFactor));
        out.add(StatModifier.multiply(ROUND_SOURCE, NpcStat.RANGED_COOLDOWN, cooldownFactor));
        out.add(StatModifier.multiply(ROUND_SOURCE, NpcStat.RANGED_DRAW, cooldownFactor));
        return out;
    }

    private static double positive(double value) {
        return value > 0 && Double.isFinite(value) ? value : 1.0;
    }
}
