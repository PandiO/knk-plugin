package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
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
 * Who stands in an arena's spectator area, or in the arena itself (arena duels DESIGN §9.3, rev. 6): a WorldGuard
 * region test on the player's position against the union of the area's regions (a spectator area), or of the arena's
 * own region, its KNG-253 sub-regions and every area's regions (the arena), in its world. Main thread.
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
        String world = at.getWorld().getName();
        for (KnkArenaArea area : arena.spectatorAreas()) {
            for (String id : here) {
                if (area.hasRegion(world, id)) return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code player} stands inside {@code arena} (challenging is offered there, §6.1, §9.3): its main region, one
     * of its KNG-253 sub-regions or one of its areas' regions.
     */
    public boolean isInside(Player player, KnkArena arena) {
        if (player == null || arena == null) return false;
        Location at = player.getLocation();
        if (at == null || at.getWorld() == null) return false;
        return isInside(arena, at.getWorld().getName(), regionsAt.apply(at));
    }

    /** {@link #isInside(Player, KnkArena)} for given region ids at a spot in {@code world}. */
    public static boolean isInside(KnkArena arena, String world, Set<String> regionIdsHere) {
        if (arena == null || regionIdsHere == null || regionIdsHere.isEmpty()) return false;
        Set<String> here = lower(regionIdsHere);
        if (inWorld(world, arena.worldName())) {
            for (String own : arena.ownRegionIds()) {
                if (here.contains(own.toLowerCase(Locale.ROOT))) return true;
            }
        }
        for (KnkArenaArea area : arena.areas()) {
            for (String id : here) {
                if (area.hasRegion(world, id)) return true;
            }
        }
        return false;
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

    private static boolean inWorld(String world, String worldName) {
        return worldName == null || worldName.isBlank() || world == null || world.equalsIgnoreCase(worldName);
    }

    private static Set<String> lower(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String id : ids) out.add(id.toLowerCase(Locale.ROOT));
        return out;
    }
}
