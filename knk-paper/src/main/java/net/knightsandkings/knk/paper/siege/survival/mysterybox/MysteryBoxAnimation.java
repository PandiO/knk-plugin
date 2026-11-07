package net.knightsandkings.knk.paper.siege.survival.mysterybox;

import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxReelPlan;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The in-world carousel of a Mystery Box roll (DESIGN §8a, modeled on the lootbox reel §3.9): an {@link ItemDisplay}
 * above the box block cycles through the reel's faces (display name on a {@link TextDisplay}, glint from the item), a
 * ticking sound and particles per step, slowing down to the result with a distinct sound and burst. Display entities
 * cannot be picked up by players, hoppers or mobs; the claim right-click lands on an {@link Interaction} hitbox over the
 * shown item. Every entity carries the {@code <plugin>:survival_box} tag so a leftover from a crash is swept.
 */
public final class MysteryBoxAnimation {

    public static final String TAG = "survival_box";

    /** The tick scheduler (Bukkit in production, a manual one in tests). */
    public interface TickScheduler {
        interface Cancellable {
            void cancel();
        }

        Cancellable later(long ticks, Runnable task);

        static TickScheduler bukkit(Plugin plugin) {
            return (ticks, task) -> {
                BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
                return scheduled::cancel;
            };
        }
    }

    /** The sounds and particles of a step and of the finale (Bukkit in production; silent in tests). */
    public interface Effects {
        void step(World world, Location at, float pitch);

        void finale(World world, Location at);

        static Effects bukkit() {
            return new Effects() {
                @Override
                public void step(World world, Location at, float pitch) {
                    world.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, Math.min(2.0f, pitch));
                    world.spawnParticle(Particle.ENCHANT, at, 12, 0.3, 0.4, 0.3, 0.4);
                }

                @Override
                public void finale(World world, Location at) {
                    world.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.4, 0.5, 0.4, 0.25);
                    world.spawnParticle(Particle.END_ROD, at, 20, 0.3, 0.5, 0.3, 0.05);
                }
            };
        }

        static Effects none() {
            return new Effects() {
                @Override
                public void step(World world, Location at, float pitch) {
                }

                @Override
                public void finale(World world, Location at) {
                }
            };
        }
    }

    private final TickScheduler scheduler;
    private final Effects effects;
    private final NamespacedKey tag;
    private final Location base;
    private final MysteryBoxReelPlan plan;
    private final IntFunction<ItemStack> faceOf;
    private final IntFunction<Component> nameOf;
    private final Runnable onFinished;
    private ItemDisplay item;
    private TextDisplay label;
    private Interaction hitbox;
    private TickScheduler.Cancellable task;
    private int step = -1;
    private boolean finished;
    private boolean removed;

    /**
     * @param base       the box block
     * @param faceOf     the item to show for a reel entry id (never null; a barrier for an unknown entry)
     * @param nameOf     the name to show for an entry id
     * @param onFinished called once on the main thread when the reel stopped on the result
     */
    public MysteryBoxAnimation(TickScheduler scheduler, Effects effects, NamespacedKey tag, Location base, MysteryBoxReelPlan plan,
                               IntFunction<ItemStack> faceOf, IntFunction<Component> nameOf, Runnable onFinished) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.tag = Objects.requireNonNull(tag, "tag");
        this.base = base.clone();
        this.plan = Objects.requireNonNull(plan, "plan");
        this.faceOf = faceOf;
        this.nameOf = nameOf;
        this.onFinished = onFinished;
    }

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, TAG);
    }

    /** Spawns the entities and schedules the first step. */
    public void start() {
        World world = Objects.requireNonNull(base.getWorld(), "world");
        Location at = base.clone().add(0.5, 1.35, 0.5);
        item = world.spawn(at, ItemDisplay.class, d -> {
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.getPersistentDataContainer().set(tag, PersistentDataType.STRING, TAG);
        });
        label = world.spawn(at.clone().add(0, 0.55, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.STRING, TAG);
        });
        hitbox = world.spawn(base.clone().add(0.5, 0.95, 0.5), Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(0.9f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(tag, PersistentDataType.STRING, TAG);
        });
        show(0);
        schedule();
    }

    private void schedule() {
        if (removed) return;
        if (plan.isLast(step)) {
            finish();
            return;
        }
        int delay = Math.max(1, plan.delayTicks().get(Math.min(step + 1, plan.steps() - 1)));
        task = scheduler.later(delay, () -> {
            if (removed) return;
            show(step + 1);
            schedule();
        });
    }

    private void show(int next) {
        step = next;
        int entryId = plan.entryAt(step);
        ItemStack face = faceOf.apply(entryId);
        if (item != null && item.isValid()) item.setItemStack(face == null ? new ItemStack(org.bukkit.Material.BARRIER) : face);
        if (label != null && label.isValid()) {
            Component name = nameOf.apply(entryId);
            label.text(name == null ? Component.text("?", NamedTextColor.GRAY) : name);
        }
        World world = base.getWorld();
        if (world == null) return;
        Location at = base.clone().add(0.5, 1.4, 0.5);
        if (plan.isLast(step)) {
            effects.finale(world, at);
        } else {
            effects.step(world, at, 0.8f + 1.2f * (step + 1) / Math.max(1, plan.steps()));
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        if (onFinished != null) onFinished.run();
    }

    public boolean finished() {
        return finished;
    }

    public int step() {
        return step;
    }

    /** Whether this entity is the claim hitbox (or any of the shown entities). */
    public boolean owns(Entity entity) {
        if (entity == null) return false;
        UUID id = entity.getUniqueId();
        return (hitbox != null && hitbox.getUniqueId().equals(id)) || (item != null && item.getUniqueId().equals(id))
            || (label != null && label.getUniqueId().equals(id));
    }

    public Optional<Location> location() {
        return Optional.of(base.clone().add(0.5, 1.35, 0.5));
    }

    /** Removes the entities and the pending step (idempotent). */
    public void remove() {
        removed = true;
        if (task != null) task.cancel();
        for (Entity e : new Entity[] {item, label, hitbox}) {
            if (e != null && e.isValid()) e.remove();
        }
        item = null;
        label = null;
        hitbox = null;
    }

    /** Removes every tagged display/interaction entity among {@code entities} (startup/chunk-load sweep). */
    public static int sweep(Collection<? extends Entity> entities, NamespacedKey tag) {
        int removed = 0;
        for (Entity e : entities) {
            if (!(e instanceof Display) && !(e instanceof Interaction)) continue;
            if (e.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }
}
