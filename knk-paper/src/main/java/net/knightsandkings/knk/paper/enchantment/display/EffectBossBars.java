package net.knightsandkings.knk.paper.enchantment.display;

import net.knightsandkings.knk.core.effects.TimedEffectBoard;
import net.knightsandkings.knk.core.effects.TimedEffectBoard.View;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * One boss bar per timed debuff a custom enchantment put on a player (PandiO/knk-workspace#170): {@code Poison · 4s},
 * {@code Frozen · 2s}, counting down, plus a static red {@code Frozen by <staff> for: <reason>} bar while staff hold
 * the player with {@code /freeze}. The bars are this class's own Adventure bars, so they sit next to the navigation bar
 * ({@code NavigationHud}) and any other plugin's bars without touching them.
 * <p>
 * A bar follows the real effect: a potion bar reads the player's potion effect every tick of {@link #tick} (milk or a
 * stronger copy from elsewhere moves or ends it), the freeze bar reads {@code FrozenPlayerTracker}. Timed bars go on
 * death; every bar goes on quit (a staff freeze comes back on the next join through {@code AdminFreezeManager}).
 * Also forwards {@link #hideNameTags} to the {@link NameTagBlackout}. Main thread.
 */
public final class EffectBossBars implements EffectDisplay, Listener {

    /** The key and label of the staff {@code /freeze} bar. */
    public static final String STAFF_FREEZE = "staff_freeze";
    public static final String FROZEN_LABEL = "Frozen";
    /** Who froze the player when it isn't known (console, vanished staff, a restart, the web app). */
    public static final String UNKNOWN_FREEZER = "staff";
    /** Longest reason shown on the staff freeze bar before it is cut with an ellipsis. */
    static final int MAX_REASON_LENGTH = 60;

    private final TimedEffectBoard board = new TimedEffectBoard();
    private final Map<UUID, Map<String, BossBar>> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Function<Player, PotionEffect>>> potions = new ConcurrentHashMap<>();
    private final Map<String, ToLongFunction<UUID>> remainingSources = new ConcurrentHashMap<>();
    private final NameTagBlackout blackout;
    private volatile InvisibleNameTag invisibleNameTag;
    private final Function<UUID, Player> players;
    private final LongSupplier clock;

    public EffectBossBars(NameTagBlackout blackout) {
        this(blackout, Bukkit::getPlayer, System::currentTimeMillis);
    }

    EffectBossBars(NameTagBlackout blackout, Function<UUID, Player> players, LongSupplier clock) {
        this.blackout = blackout;
        this.players = players;
        this.clock = clock;
    }

    /**
     * Where the bar under {@code key} reads the effect's real remaining time (ms, 0 = over) when it isn't a potion,
     * e.g. {@code freeze} from {@code FrozenPlayerTracker::remainingMillis}.
     */
    public void remainingFrom(String key, ToLongFunction<UUID> source) {
        if (key != null && source != null) {
            remainingSources.put(key, source);
        }
    }

    @Override
    public void debuff(LivingEntity victim, String key, String label, PotionEffectType potion, int durationTicks) {
        debuff(victim, key, label, potion == null ? null : p -> p.getPotionEffect(potion), durationTicks);
    }

    /** @param potion reads the potion effect behind the bar from the player, null when there is none */
    void debuff(LivingEntity victim, String key, String label, Function<Player, PotionEffect> potion, int durationTicks) {
        if (!(victim instanceof Player player) || key == null || durationTicks <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = clock.getAsLong();
        long durationMs = durationTicks * 50L;
        if (potion != null) {
            // A stronger or longer copy already running keeps its own (longer) time on the bar.
            PotionEffect running = potion.apply(player);
            if (running != null && !running.isInfinite() && running.getDuration() > durationTicks) {
                durationMs = running.getDuration() * 50L;
            }
            potions.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(key, potion);
        } else {
            Map<String, Function<Player, PotionEffect>> own = potions.get(id);
            if (own != null) {
                own.remove(key);
            }
        }
        board.startTimed(id, key, label, durationMs, now);
        render(player, now);
    }

    /** #169: the blackout ends with the blindness (milk ends it early). */
    @Override
    public void hideNameTags(LivingEntity victim, int durationTicks) {
        if (blackout != null && victim instanceof Player player) {
            blackout.hide(player, durationTicks, p -> p.hasPotionEffect(PotionEffectType.BLINDNESS));
        }
    }

    /** Where {@link #invisible} goes (#169 round 2); none until set. */
    public void setInvisibleNameTag(InvisibleNameTag invisibleNameTag) {
        this.invisibleNameTag = invisibleNameTag;
    }

    @Override
    public void invisible(LivingEntity invoker, int durationTicks) {
        InvisibleNameTag target = invisibleNameTag;
        if (target != null && invoker instanceof Player player && durationTicks > 0) {
            target.invisible(player);
        }
    }

    /** Staff {@code /freeze} started ({@code true}) or ended, freezer and reason unknown. */
    public void staffFrozen(UUID playerId, boolean frozen) {
        staffFrozen(playerId, frozen, staffFreezeLabel(null, null));
    }

    /**
     * Staff {@code /freeze} started ({@code true}) or ended: the static red bar with {@code label} (see
     * {@link #staffFreezeLabel}).
     */
    public void staffFrozen(UUID playerId, boolean frozen, String label) {
        if (playerId == null) {
            return;
        }
        if (frozen) {
            board.startStatic(playerId, STAFF_FREEZE, label == null || label.isBlank() ? FROZEN_LABEL : label);
        } else {
            board.end(playerId, STAFF_FREEZE);
        }
        Player player = players.apply(playerId);
        if (player != null) {
            render(player, clock.getAsLong());
        }
    }

    /** Every few ticks: follow the real effects and count the bars down. */
    public void tick() {
        long now = clock.getAsLong();
        for (UUID id : Set.copyOf(board.players())) {
            Player player = players.apply(id);
            if (player == null) {
                forget(id);
                continue;
            }
            follow(player, now);
            render(player, now);
        }
        // Bars of players without entries any more (all ended in follow) are hidden by render above; stragglers here.
        for (UUID id : Set.copyOf(bars.keySet())) {
            if (!board.players().contains(id)) {
                Player player = players.apply(id);
                if (player != null) {
                    render(player, now);
                } else {
                    forget(id);
                }
            }
        }
    }

    public boolean has(UUID playerId, String key) {
        return board.has(playerId, key);
    }

    /** Plugin disable: hide every bar. */
    public void hideAll() {
        for (UUID id : Set.copyOf(bars.keySet())) {
            Player player = players.apply(id);
            Map<String, BossBar> own = bars.remove(id);
            if (player != null && own != null) {
                own.values().forEach(player::hideBossBar);
            }
        }
        for (UUID id : Set.copyOf(board.players())) {
            board.clear(id);
        }
        potions.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        board.endTimed(player.getUniqueId());
        potions.remove(player.getUniqueId());
        render(player, clock.getAsLong());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Map<String, BossBar> own = bars.remove(player.getUniqueId());
        if (own != null) {
            own.values().forEach(player::hideBossBar);
        }
        forget(player.getUniqueId());
    }

    private void follow(Player player, long now) {
        UUID id = player.getUniqueId();
        Map<String, Function<Player, PotionEffect>> own = potions.getOrDefault(id, Map.of());
        for (String key : board.keys(id)) {
            Function<Player, PotionEffect> potion = own.get(key);
            if (potion != null) {
                PotionEffect running = potion.apply(player);
                if (running == null) {
                    board.end(id, key);
                    own.remove(key);
                } else if (!running.isInfinite()) {
                    board.sync(id, key, running.getDuration() * 50L, now);
                }
                continue;
            }
            ToLongFunction<UUID> source = remainingSources.get(key);
            if (source != null) {
                board.sync(id, key, source.applyAsLong(id), now);
            }
        }
    }

    /** Shows, updates and hides the player's bars to match the board. */
    private void render(Player player, long now) {
        UUID id = player.getUniqueId();
        List<View> views = board.views(id, now);
        Map<String, BossBar> own = bars.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Set<String> live = new HashSet<>();
        for (View view : views) {
            live.add(view.key());
            Component text = Component.text(view.text(), textColor(view.key()));
            BossBar bar = own.get(view.key());
            if (bar == null) {
                bar = BossBar.bossBar(text, view.progress(), barColor(view.key()), BossBar.Overlay.PROGRESS);
                own.put(view.key(), bar);
                player.showBossBar(bar);
            } else {
                bar.name(text);
                bar.progress(view.progress());
            }
        }
        for (Map.Entry<String, BossBar> e : new HashMap<>(own).entrySet()) {
            if (!live.contains(e.getKey())) {
                own.remove(e.getKey());
                player.hideBossBar(e.getValue());
            }
        }
        if (own.isEmpty()) {
            bars.remove(id, own);
        }
        Map<String, Function<Player, PotionEffect>> ownPotions = potions.get(id);
        if (ownPotions != null) {
            ownPotions.keySet().retainAll(live);
            if (ownPotions.isEmpty()) {
                potions.remove(id, ownPotions);
            }
        }
    }

    private void forget(UUID id) {
        board.clear(id);
        bars.remove(id);
        potions.remove(id);
    }

    /**
     * The staff freeze bar text (PandiO/knk-workspace#170 round 2): {@code Frozen by <frozenBy> for: <reason>}, with
     * {@value #UNKNOWN_FREEZER} for an unknown freezer and without {@code " for: ..."} when there is no reason. A
     * reason longer than {@value #MAX_REASON_LENGTH} characters is cut with an ellipsis.
     */
    public static String staffFreezeLabel(String frozenBy, String reason) {
        String by = frozenBy == null || frozenBy.isBlank() ? UNKNOWN_FREEZER : frozenBy.trim();
        String text = FROZEN_LABEL + " by " + by;
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            return text;
        }
        if (why.length() > MAX_REASON_LENGTH) {
            why = why.substring(0, MAX_REASON_LENGTH - 1).stripTrailing() + "…";
        }
        return text + " for: " + why;
    }

    static BossBar.Color barColor(String key) {
        return switch (key) {
            case STAFF_FREEZE -> BossBar.Color.RED;
            case "freeze" -> BossBar.Color.BLUE;
            case "poison" -> BossBar.Color.GREEN;
            case "blindness", "nausea" -> BossBar.Color.PURPLE;
            case "wither", "slowness" -> BossBar.Color.WHITE;
            default -> BossBar.Color.RED;
        };
    }

    static NamedTextColor textColor(String key) {
        return switch (key) {
            case STAFF_FREEZE -> NamedTextColor.RED;
            case "freeze" -> NamedTextColor.AQUA;
            case "poison" -> NamedTextColor.GREEN;
            case "blindness", "nausea" -> NamedTextColor.LIGHT_PURPLE;
            case "wither", "slowness" -> NamedTextColor.GRAY;
            default -> NamedTextColor.RED;
        };
    }
}
