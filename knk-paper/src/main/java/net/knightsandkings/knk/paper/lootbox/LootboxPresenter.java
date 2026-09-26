package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.KnkLootboxType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A box in the world (docs/specs/lootboxes/DESIGN.md §3.4, D3): an {@link ItemDisplay} (the type's model, slowly
 * turning), an {@link Interaction} hitbox (0.9 x 0.9) and an optional {@link TextDisplay} label. All three are
 * <b>non-persistent</b> and carry PDC {@code knightsandkings:knk_lootbox = <token>}: they vanish with their chunk or a
 * crash, and the plugin re-renders boxes from its active-box cache, so a stale copy can never exist. No block is
 * touched, so a box can't overwrite a build or be broken, pushed or blown up.
 * <p>
 * Ambience echoes v1 treasure: gold-block particles and a quiet level-up sound every 4 s to players within
 * {@code display.particles-radius}. Main thread only.
 */
public final class LootboxPresenter {

    public static final NamespacedKey TOKEN_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_lootbox"));

    private static final float HITBOX_SIZE = 0.9f;
    private static final int SPIN_TICKS = 20;
    private static final int AMBIENCE_EVERY_SPINS = 4; // 4 s

    private record Rendered(KnkLootboxSpawn spawn, ItemDisplay model, Interaction hitbox, TextDisplay label) {
        boolean isValid() {
            return model.isValid() && hitbox.isValid() && (label == null || label.isValid());
        }

        void remove() {
            model.remove();
            hitbox.remove();
            if (label != null) {
                label.remove();
            }
        }
    }

    private final Supplier<LootboxSettings> settings;
    private final Map<Integer, Rendered> rendered = new HashMap<>();
    private int spinStep;

    public LootboxPresenter(Supplier<LootboxSettings> settings) {
        this.settings = settings;
    }

    /** The box token on one of its entities, or empty for any other entity. */
    public static Optional<UUID> tokenOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        String raw = entity.getPersistentDataContainer().get(TOKEN_KEY, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.of(new UUID(0, 0)); // Tagged but unreadable: never an active token, so it gets purged.
        }
    }

    /**
     * Shows {@code spawn} if its world and chunk are loaded and it isn't shown yet (or its entities went away with an
     * unloaded chunk). {@code type} gives the model; null (a type disabled since) shows a chest.
     */
    public void render(KnkLootboxSpawn spawn, KnkLootboxType type) {
        Rendered existing = rendered.get(spawn.id());
        if (existing != null) {
            if (existing.isValid()) {
                return;
            }
            existing.remove();
            rendered.remove(spawn.id());
        }

        World world = Bukkit.getWorld(spawn.world());
        if (world == null || !world.isChunkLoaded(spawn.chunkX(), spawn.chunkZ())) {
            return;
        }

        LootboxSettings current = settings.get();
        String token = spawn.token().toString();
        Location base = new Location(world, spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5);

        ItemStack model = new ItemStack(displayMaterial(type));
        ItemDisplay display = world.spawn(base.clone().add(0, 0.5, 0), ItemDisplay.class, entity -> {
            tag(entity, token);
            entity.setItemStack(model);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setTransformation(transformation(0f));
        });
        Interaction hitbox = world.spawn(base, Interaction.class, entity -> {
            tag(entity, token);
            entity.setInteractionWidth(HITBOX_SIZE);
            entity.setInteractionHeight(HITBOX_SIZE);
            entity.setResponsive(true);
        });
        TextDisplay label = null;
        if (current.label()) {
            label = world.spawn(base.clone().add(0, 1.3, 0), TextDisplay.class, entity -> {
                tag(entity, token);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.text(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(current.coloredLabel(spawn.boxLabel(), spawn.boxStars())));
            });
        }
        rendered.put(spawn.id(), new Rendered(spawn, display, hitbox, label));
    }

    /** Removes a box's entities (claimed, expired, despawned). */
    public void remove(int spawnId) {
        Rendered existing = rendered.remove(spawnId);
        if (existing != null) {
            existing.remove();
        }
    }

    public void removeAll() {
        rendered.values().forEach(Rendered::remove);
        rendered.clear();
    }

    public boolean isRendered(int spawnId) {
        Rendered existing = rendered.get(spawnId);
        return existing != null && existing.isValid();
    }

    /**
     * Removes every entity among {@code entities} that carries a box token {@code isActive} rejects (belt and braces:
     * the entities are non-persistent, so none should survive a restart). Returns how many were removed.
     */
    public int purgeOrphans(Collection<Entity> entities, Predicate<UUID> isActive) {
        int removed = 0;
        for (Entity entity : entities) {
            Optional<UUID> token = tokenOf(entity);
            if (token.isPresent() && !isActive.test(token.get())) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Every second: turn the models a quarter; every 4 s: particles and sound near each box. */
    public void tick() {
        LootboxSettings current = settings.get();
        spinStep++;
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, Rendered> entry : rendered.entrySet()) {
            Rendered box = entry.getValue();
            if (!box.isValid()) {
                gone.add(entry.getKey()); // Its chunk unloaded; re-rendered on load.
                continue;
            }
            if (current.rotate()) {
                box.model().setInterpolationDelay(0);
                box.model().setInterpolationDuration(SPIN_TICKS);
                box.model().setTransformation(transformation((float) (spinStep * Math.PI / 2)));
            }
            if (spinStep % AMBIENCE_EVERY_SPINS == 0 && current.particlesRadius() > 0) {
                ambience(box.hitbox().getLocation().add(0, 0.5, 0), current.particlesRadius());
            }
        }
        gone.forEach(id -> rendered.remove(id).remove());
    }

    private static void ambience(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        double radiusSquared = (double) radius * radius;
        BlockData gold = Material.GOLD_BLOCK.createBlockData();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) > radiusSquared) {
                continue;
            }
            player.spawnParticle(Particle.BLOCK, center, 8, 0.3, 0.3, 0.3, 0, gold);
            player.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.1f, 0.5f + ThreadLocalRandom.current().nextFloat());
        }
    }

    private static Transformation transformation(float angle) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new Quaternionf().rotationY(angle),
                new Vector3f(0.6f, 0.6f, 0.6f),
                new Quaternionf());
    }

    private static void tag(Entity entity, String token) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(TOKEN_KEY, PersistentDataType.STRING, token);
    }

    private static Material displayMaterial(KnkLootboxType type) {
        Material material = type == null || type.displayMaterialKey() == null ? null : Material.matchMaterial(type.displayMaterialKey());
        return material != null && material.isItem() && !material.isAir() ? material : Material.CHEST;
    }
}
