package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaSpectatorArea;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Who stands in an arena's spectator area, or in the arena itself (arena duels DESIGN §9.3): a WorldGuard region
 * test on the player's position against the area's (or arena's) {@code wgRegionId}, in its world. Main thread.
 */
public final class DuelSpectators {

    private final Supplier<Collection<? extends Player>> online;
    private final Function<Location, Set<String>> regionsAt;
    private final Supplier<List<KnkArena>> arenas;

    /**
     * @param online    the online players
     * @param regionsAt the WorldGuard region ids at a spot (lower or mixed case)
     * @param arenas    the cached arenas
     */
    public DuelSpectators(Supplier<Collection<? extends Player>> online, Function<Location, Set<String>> regionsAt,
                          Supplier<List<KnkArena>> arenas) {
        this.online = Objects.requireNonNull(online, "online");
        this.regionsAt = Objects.requireNonNull(regionsAt, "regionsAt");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
    }

    public List<KnkArena> arenas() {
        List<KnkArena> list = arenas.get();
        return list == null ? List.of() : list;
    }

    /** Whether {@code player} stands in one of {@code arena}'s spectator areas. */
    public boolean isSpectating(Player player, KnkArena arena) {
        if (player == null || arena == null || arena.spectatorAreas().isEmpty()) return false;
        Location at = player.getLocation();
        if (at == null || at.getWorld() == null) return false;
        Set<String> here = lower(regionsAt.apply(at));
        for (KnkArenaSpectatorArea area : arena.spectatorAreas()) {
            if (inWorld(at, area.worldName()) && area.wgRegionId() != null
                    && here.contains(area.wgRegionId().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code player} stands inside {@code arena}'s own region (challenging is offered there, §9.3). */
    public boolean isInside(Player player, KnkArena arena) {
        if (player == null || arena == null || arena.wgRegionId() == null) return false;
        Location at = player.getLocation();
        if (at == null || at.getWorld() == null || !inWorld(at, arena.worldName())) return false;
        return lower(regionsAt.apply(at)).contains(arena.wgRegionId().toLowerCase(Locale.ROOT));
    }

    /** The arena whose spectator area {@code player} stands in, if any. */
    public Optional<KnkArena> spectatedBy(Player player) {
        for (KnkArena arena : arenas()) {
            if (isSpectating(player, arena)) return Optional.of(arena);
        }
        return Optional.empty();
    }

    /** The arena {@code player} stands in (its region or a spectator area), if any. */
    public Optional<KnkArena> arenaAt(Player player) {
        for (KnkArena arena : arenas()) {
            if (isInside(player, arena) || isSpectating(player, arena)) return Optional.of(arena);
        }
        return Optional.empty();
    }

    /** The online players in {@code arena}'s spectator areas. */
    public List<Player> spectatorsOf(KnkArena arena) {
        List<Player> out = new ArrayList<>();
        if (arena == null || arena.spectatorAreas().isEmpty()) return out;
        for (Player player : online.get()) {
            if (isSpectating(player, arena)) out.add(player);
        }
        return out;
    }

    public Collection<? extends Player> online() {
        return online.get();
    }

    private static boolean inWorld(Location at, String worldName) {
        return worldName == null || worldName.isBlank() || at.getWorld().getName().equalsIgnoreCase(worldName);
    }

    private static Set<String> lower(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String id : ids) out.add(id.toLowerCase(Locale.ROOT));
        return out;
    }
}
