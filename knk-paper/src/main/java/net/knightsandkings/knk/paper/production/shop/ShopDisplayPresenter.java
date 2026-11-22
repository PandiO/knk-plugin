package net.knightsandkings.knk.paper.production.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The entities of the display stalls (P12, PandiO/knk-workspace#250, shops DESIGN § 7), the lootbox technique (lootboxes
 * DESIGN D3): per spot an {@link ItemDisplay} on top of the block (fixed, it doesn't turn, faces the spot's facing), an
 * {@link Interaction} hitbox for the clicks and an optional {@link TextDisplay} price label. All three are
 * <b>non-persistent</b> and carry PDC {@code knightsandkings:knk_shop_display = <spot id>}: they go with their chunk or a
 * crash and are spawned again from the module's state, so a stale copy can't survive. No block is touched; display
 * entities can't be knocked down, turned or taken. A sold-out item is shown dimmed. Main thread only.
 */
public final class ShopDisplayPresenter {

    public static final NamespacedKey SPOT_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_shop_display"));

    /** Dimmed light for a greyed-out (sold-out) item. */
    static final Display.Brightness GREYED = new Display.Brightness(3, 3);

    /** One spot's entities and what they show. */
    public record Rendered(DisplaySpot spot, String itemKey, ItemDisplay model, Interaction hitbox, TextDisplay label) {

        public boolean isValid() {
            return model.isValid() && hitbox.isValid() && (label == null || label.isValid());
        }

        void remove() {
            model.remove();
            hitbox.remove();
            if (label != null) {
                label.remove();
            }
        }

        boolean owns(Entity entity) {
            UUID id = entity.getUniqueId();
            return id.equals(model.getUniqueId()) || id.equals(hitbox.getUniqueId()) || (label != null && id.equals(label.getUniqueId()));
        }
    }

    private final Map<Integer, Rendered> rendered = new HashMap<>();

    /** The spot id on one of a stall's entities, or empty for any other entity. */
    public static Optional<Integer> spotOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        Integer id = entity.getPersistentDataContainer().get(SPOT_KEY, PersistentDataType.INTEGER);
        return Optional.ofNullable(id);
    }

    public static boolean isStallEntity(Entity entity) {
        return spotOf(entity).isPresent();
    }

    /**
     * Shows {@code item} on {@code spot} when its chunk is loaded: updates the entities in place when they are there for the
     * same block, else spawns them. {@code itemKey} names what is shown (the offer item), so a click finds it.
     *
     * @return true when the spot is shown
     */
    public boolean show(DisplaySpot spot, String itemKey, ItemStack item, boolean greyed, List<String> labelLines, double scale) {
        World world = Bukkit.getWorld(spot.world());
        if (world == null || !world.isChunkLoaded(spot.chunkX(), spot.chunkZ())) {
            hide(spot.id());
            return false;
        }
        Rendered existing = rendered.get(spot.id());
        if (existing != null && existing.isValid() && existing.spot().blockKey().equals(spot.blockKey())
                && existing.spot().facing() == spot.facing()
                && (existing.label() != null) == (labelLines != null && !labelLines.isEmpty())) {
            paint(existing.model(), item, greyed, scale);
            if (existing.label() != null) {
                existing.label().text(LegacyComponentSerializer.legacyAmpersand().deserialize(String.join("\n", labelLines)));
            }
            rendered.put(spot.id(), new Rendered(spot, itemKey, existing.model(), existing.hitbox(), existing.label()));
            return true;
        }
        hide(spot.id());
        float size = (float) Math.max(0.4, scale);
        Location top = new Location(world, spot.x() + 0.5, spot.y() + 1.0, spot.z() + 0.5, spot.facing().yaw(), 0f);
        ItemDisplay model = world.spawn(top.clone().add(0, size / 2.0, 0), ItemDisplay.class, entity -> {
            tag(entity, spot.id());
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // Billboard FIXED is a display's default: it faces the spawn location's yaw, the spot's facing.
            paint(entity, item, greyed, scale);
        });
        Interaction hitbox = world.spawn(top, Interaction.class, entity -> {
            tag(entity, spot.id());
            entity.setInteractionWidth(size);
            entity.setInteractionHeight(size);
            entity.setResponsive(true);
        });
        TextDisplay label = null;
        if (labelLines != null && !labelLines.isEmpty()) {
            label = world.spawn(top.clone().add(0, size + 0.15, 0), TextDisplay.class, entity -> {
                tag(entity, spot.id());
                billboard(entity, Display.Billboard.CENTER);
                entity.text(LegacyComponentSerializer.legacyAmpersand().deserialize(String.join("\n", labelLines)));
            });
        }
        rendered.put(spot.id(), new Rendered(spot, itemKey, model, hitbox, label));
        return true;
    }

    private static void paint(ItemDisplay entity, ItemStack item, boolean greyed, double scale) {
        entity.setItemStack(item);
        entity.setBrightness(greyed ? GREYED : null);
        float s = (float) scale;
        entity.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
    }

    /** Removes a spot's entities (nothing to show, the spot removed, its chunk unloading). */
    public void hide(int spotId) {
        Rendered existing = rendered.remove(spotId);
        if (existing != null) {
            existing.remove();
        }
    }

    public void hideAll() {
        rendered.values().forEach(Rendered::remove);
        rendered.clear();
    }

    public Optional<Rendered> rendered(int spotId) {
        return Optional.ofNullable(rendered.get(spotId));
    }

    public boolean isShown(int spotId) {
        Rendered existing = rendered.get(spotId);
        return existing != null && existing.isValid();
    }

    /** The spots whose entities went away (their chunk unloaded, or something removed them); forgotten here. */
    public List<Integer> prune() {
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, Rendered> entry : rendered.entrySet()) {
            if (!entry.getValue().isValid()) {
                gone.add(entry.getKey());
            }
        }
        for (Integer id : gone) {
            rendered.remove(id).remove();
        }
        return gone;
    }

    /**
     * Removes every stall entity among {@code entities} that isn't one of the shown spots' own entities (a copy left by a
     * crash or a duplicate; non-persistent, so none should survive a restart). Returns how many were removed.
     */
    public int purgeOrphans(Collection<Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            Optional<Integer> spot = spotOf(entity);
            if (spot.isEmpty()) {
                continue;
            }
            Rendered owner = rendered.get(spot.get());
            if (owner == null || !owner.owns(entity)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public int size() {
        return rendered.size();
    }

    /** Turns the label towards every viewer; a server without billboards (a test double) keeps it fixed. */
    static void billboard(Display entity, Display.Billboard billboard) {
        try {
            entity.setBillboard(billboard);
        } catch (RuntimeException e) { // MockBukkit's "not implemented"; Paper always has it
            // fixed label, still readable from the front
        }
    }

    private static void tag(Entity entity, int spotId) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(SPOT_KEY, PersistentDataType.INTEGER, spotId);
    }
}
