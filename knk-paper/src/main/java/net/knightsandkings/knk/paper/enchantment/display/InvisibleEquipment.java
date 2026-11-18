package net.knightsandkings.knk.paper.enchantment.display;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hides the invisibility ability's invoker completely: no armour and no held items either, while they stay in the
 * tab list (PandiO/knk-workspace#211).
 * <p>
 * <b>Why not {@code hidePlayer}:</b> owner/staff mode ({@code ModeService}) hides a player with
 * {@code Player#hidePlayer}, and {@code hideEntity} on a player does the same. Both also send the "remove player
 * info" packet, so the player leaves the viewer's tab list, and Paper's {@code listPlayer} only lists a player the
 * viewer can see, so the entry can't be put back. A hidden player also can't be hit by the viewer, which would make
 * the ability far stronger than vanilla invisibility in a siege.
 * <p>
 * <b>How:</b> the ability keeps vanilla Invisibility (the client hides the body and name tag, the player stays a real,
 * hittable entity in the tab list) and this class covers the rest with per-viewer equipment packets
 * ({@code Player#sendEquipmentChange}, plain Paper API, no packet library): every player tracking the invoker who
 * would not see them through a team gets empty armour and hands. The real equipment isn't touched. The server sends
 * the real equipment again when it changes or when a viewer starts tracking the invoker, so the mask is sent again on
 * the next tick after {@link EntityEquipmentChangedEvent} and {@link PlayerTrackEntityEvent}, and {@link #tick} sends
 * it to every tracker as a safety net (a held item can show for about one tick after a hotbar switch).
 * <p>
 * <b>Who still sees them:</b> exactly the viewers vanilla shows a see-through body to: spectators, and a viewer on the
 * invoker's team of the viewer's own board when that team "can see friendly invisibles". The siege boards turn that
 * on only for the viewer's own siege team, so siege teammates keep seeing the invoker (body, tag and equipment) and
 * the other team doesn't; the shared rank board has it off ({@link InvisibleNameTag}).
 * <p>
 * <b>When it ends:</b> when Invisibility is removed or cleared (the ability's time runs out, milk, death, a command),
 * on death, and when the plugin disables ({@link #revealAll}); viewers get the real equipment back. On quit the
 * ability's Invisibility is removed too, so after a relog everyone sees the player again. Main thread.
 */
public final class InvisibleEquipment implements Listener {

    private static final Logger LOGGER = Logger.getLogger(InvisibleEquipment.class.getName());

    /** The slots a player shows to others. */
    static final List<EquipmentSlot> SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);

    /** Invoker → the viewers currently sent empty equipment. */
    private final Map<UUID, Set<UUID>> masked = new ConcurrentHashMap<>();
    private final Function<UUID, Player> players;
    private final Consumer<Runnable> nextTick;
    private final Supplier<ItemStack> empty;
    private final Predicate<PotionEffect> isInvisibility;
    private final Consumer<Player> endInvisibility;

    public InvisibleEquipment(Plugin plugin) {
        this(Bukkit::getPlayer, task -> Bukkit.getScheduler().runTask(plugin, task), ItemStack::empty,
                effect -> PotionEffectType.INVISIBILITY.equals(effect.getType()),
                player -> player.removePotionEffect(PotionEffectType.INVISIBILITY));
    }

    /**
     * @param isInvisibility  whether a potion effect is Invisibility, and {@code endInvisibility} removes it (the
     *                        registry constant needs a running server, so tests pass their own)
     */
    InvisibleEquipment(Function<UUID, Player> players, Consumer<Runnable> nextTick, Supplier<ItemStack> empty,
                       Predicate<PotionEffect> isInvisibility, Consumer<Player> endInvisibility) {
        this.players = players;
        this.nextTick = nextTick;
        this.empty = empty;
        this.isInvisibility = isInvisibility;
        this.endInvisibility = endInvisibility;
    }

    /** The invoker just turned invisible through the ability: hide their equipment from everyone who can't see them. */
    public void hide(Player invoker) {
        if (invoker == null) {
            return;
        }
        masked.computeIfAbsent(invoker.getUniqueId(), id -> ConcurrentHashMap.newKeySet());
        refresh(invoker);
    }

    /** Whether the invoker's equipment is being hidden right now. */
    public boolean isHidden(UUID invoker) {
        return invoker != null && masked.containsKey(invoker);
    }

    /** Sends every tracker what it should see now: empty equipment, or the real one to a viewer that sees through. */
    void refresh(Player invoker) {
        Set<UUID> viewers = masked.get(invoker.getUniqueId());
        if (viewers == null) {
            return;
        }
        try {
            Set<UUID> tracking = new HashSet<>();
            for (Player viewer : invoker.getTrackedBy()) {
                if (viewer == null || viewer.getUniqueId().equals(invoker.getUniqueId())) {
                    continue;
                }
                UUID viewerId = viewer.getUniqueId();
                tracking.add(viewerId);
                if (seesThrough(viewer, invoker)) {
                    if (viewers.remove(viewerId)) {
                        viewer.sendEquipmentChange(invoker, realEquipment(invoker));
                    }
                } else {
                    viewer.sendEquipmentChange(invoker, emptyEquipment());
                    viewers.add(viewerId);
                }
            }
            // A viewer out of range gets the real equipment with the next spawn; the track event masks it again.
            viewers.retainAll(tracking);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not hide the equipment of invisible " + invoker.getName(), e);
        }
    }

    /** Ends the hiding: every viewer that got empty equipment gets the real equipment back. */
    public void reveal(Player invoker) {
        if (invoker == null) {
            return;
        }
        Set<UUID> viewers = masked.remove(invoker.getUniqueId());
        if (viewers == null || viewers.isEmpty()) {
            return;
        }
        try {
            Map<EquipmentSlot, ItemStack> real = realEquipment(invoker);
            for (UUID viewerId : viewers) {
                Player viewer = players.apply(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendEquipmentChange(invoker, real);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not show the equipment of " + invoker.getName() + " again", e);
        }
    }

    /**
     * Plugin disable: everyone sees every hidden invoker again, and the ability's Invisibility ends, so a restart
     * doesn't leave anyone invisible without the mask.
     */
    public void revealAll() {
        for (UUID id : Set.copyOf(masked.keySet())) {
            Player invoker = players.apply(id);
            if (invoker == null) {
                masked.remove(id);
                continue;
            }
            reveal(invoker);
            removeInvisibility(invoker);
        }
    }

    /** Every few ticks: drop invokers that lost Invisibility without an event, and send the mask again. */
    public void tick() {
        for (UUID id : Set.copyOf(masked.keySet())) {
            Player invoker = players.apply(id);
            if (invoker == null || !invoker.isOnline()) {
                masked.remove(id);
            } else if (invoker.isDead() || !isInvisible(invoker)) {
                reveal(invoker);
            } else {
                refresh(invoker);
            }
        }
    }

    /** Milk, the ability's time running out, death or a command: the invisibility is over. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player invoker) || !isHidden(invoker.getUniqueId())) {
            return;
        }
        PotionEffect old = event.getOldEffect();
        if (old == null || !isInvisibility.test(old)) {
            return;
        }
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action == EntityPotionEffectEvent.Action.REMOVED || action == EntityPotionEffectEvent.Action.CLEARED) {
            reveal(invoker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        reveal(event.getEntity());
    }

    /** Relog: the invoker comes back visible to everyone, so the ability's Invisibility ends here. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (masked.remove(id) != null) {
            removeInvisibility(player);
        }
        for (Set<UUID> viewers : masked.values()) {
            viewers.remove(id);
        }
    }

    /** A viewer came into range: the server sends the real equipment with the spawn, so mask it again next tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Player invoker && isHidden(invoker.getUniqueId())) {
            UUID id = invoker.getUniqueId();
            nextTick.accept(() -> refreshIfHidden(id));
        }
    }

    /** The invoker changed armour or switched items: the server broadcasts the real item, so mask it again next tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (event.getEntity() instanceof Player invoker && isHidden(invoker.getUniqueId())) {
            UUID id = invoker.getUniqueId();
            nextTick.accept(() -> refreshIfHidden(id));
        }
    }

    private void refreshIfHidden(UUID id) {
        Player invoker = players.apply(id);
        if (invoker != null && invoker.isOnline() && isHidden(id)) {
            refresh(invoker);
        }
    }

    /**
     * The client's own rule for a see-through invisible player: a spectator, or a viewer on the invoker's team of the
     * viewer's board when that team can see friendly invisibles.
     */
    static boolean seesThrough(Player viewer, Player invoker) {
        if (viewer.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        Scoreboard board = viewer.getScoreboard();
        Team team = board == null ? null : board.getEntryTeam(invoker.getName());
        return team != null && team.canSeeFriendlyInvisibles() && team.hasEntry(viewer.getName());
    }

    private Map<EquipmentSlot, ItemStack> emptyEquipment() {
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : SLOTS) {
            items.put(slot, empty.get());
        }
        return items;
    }

    private Map<EquipmentSlot, ItemStack> realEquipment(Player invoker) {
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        EntityEquipment equipment = invoker.getEquipment();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack item = equipment == null ? null : equipment.getItem(slot);
            items.put(slot, item != null ? item : empty.get());
        }
        return items;
    }

    private boolean isInvisible(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect != null && isInvisibility.test(effect)) {
                return true;
            }
        }
        return false;
    }

    private void removeInvisibility(Player player) {
        try {
            endInvisibility.accept(player);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not end the invisibility of " + player.getName(), e);
        }
    }
}
