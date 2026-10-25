package net.knightsandkings.knk.paper.duel;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.BukkitPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.Handler;
import net.knightsandkings.knk.core.duel.ArenaAreaAccessRules;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * WorldGuard session handler for the arena's areas (arena duels DESIGN §8.2, rev. 6), registered next to
 * {@code DomainAccessHandler} the same way. WorldGuard calls {@link #onCrossBoundary} for every way a player moves
 * (walk, glide, swim, ride, embark, pearl, chorus fruit, teleport) with the regions entered and left; refusing puts the
 * player back. The rule itself is {@link ArenaAreaAccess#evaluate}, over the union of each area's regions.
 */
public final class ArenaAreaAccessHandler extends Handler {

    public static final class Factory extends Handler.Factory<ArenaAreaAccessHandler> {
        private final ArenaAreaAccess access;
        private final BiPredicate<Player, org.bukkit.Location> bypass;
        private final BiConsumer<Player, ArenaAreaAccessRules.Refusal> refused;

        /**
         * @param bypass  staff ({@code knk.region.bypass}, WorldGuard's region bypass) and the plugin's own corrective moves
         * @param refused tells the player (the domain-access refusal guard's chat/action-bar pacing)
         */
        public Factory(ArenaAreaAccess access, BiPredicate<Player, org.bukkit.Location> bypass,
                       BiConsumer<Player, ArenaAreaAccessRules.Refusal> refused) {
            this.access = access;
            this.bypass = bypass;
            this.refused = refused;
        }

        @Override
        public ArenaAreaAccessHandler create(Session session) {
            return new ArenaAreaAccessHandler(session, this);
        }

        /**
         * One crossing, without WorldGuard's types (the handler and the tests): true lets the move happen.
         *
         * @param origin      the region ids where the move starts
         * @param destination the region ids where it goes
         * @param cancellable WorldGuard can undo the move (respawns can't; they are not refused)
         */
        public boolean decide(Player player, Set<String> origin, Set<String> destination, String world,
                              org.bukkit.Location target, boolean cancellable) {
            Optional<ArenaAreaAccessRules.Refusal> refusal = access.evaluate(player.getUniqueId(), origin, destination, world);
            if (refusal.isEmpty() || !cancellable || bypass.test(player, target)) {
                return true;
            }
            refused.accept(player, refusal.get());
            return false;
        }
    }

    private final Factory factory;

    private ArenaAreaAccessHandler(Session session, Factory factory) {
        super(session);
        this.factory = factory;
    }

    @Override
    public boolean onCrossBoundary(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
                                   Set<ProtectedRegion> entered, Set<ProtectedRegion> exited, MoveType moveType) {
        if ((entered.isEmpty() && exited.isEmpty()) || !(player instanceof BukkitPlayer bukkitPlayer)) {
            return true;
        }
        Set<String> destination = new HashSet<>();
        for (ProtectedRegion region : toSet.getRegions()) destination.add(region.getId());
        Set<String> origin = new HashSet<>(destination);
        for (ProtectedRegion region : entered) origin.remove(region.getId());
        for (ProtectedRegion region : exited) origin.add(region.getId());
        Player bukkit = bukkitPlayer.getPlayer();
        org.bukkit.Location target = BukkitAdapter.adapt(to);
        String world = target.getWorld() != null ? target.getWorld().getName() : null;
        return factory.decide(bukkit, origin, destination, world, target, moveType.isCancellable());
    }

}
