package net.knightsandkings.knk.paper.regions.access;

import java.util.Set;

import org.bukkit.entity.Player;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.BukkitPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.Handler;

/**
 * WorldGuard session handler that enforces domain AllowEntry/AllowExit (KNG-56).
 *
 * <p>WorldGuard calls {@link #onCrossBoundary} for every way a player can move - walking, gliding,
 * swimming, riding, mounting a vehicle (embark), teleporting - with exactly the regions entered and
 * left since the player's last allowed position. Refusing makes WorldGuard put the player back at
 * that position (and stop or send back a vehicle). Its own bookkeeping only advances on an allowed
 * move, so holding W into a border is refused on every step.
 *
 * <p>Respawning is not cancellable in WorldGuard; {@code DomainAccessListener} corrects the respawn
 * location instead.
 */
public final class DomainAccessHandler extends Handler {

    public static final class Factory extends Handler.Factory<DomainAccessHandler> {
        private final DomainAccessService service;

        public Factory(DomainAccessService service) {
            this.service = service;
        }

        @Override
        public DomainAccessHandler create(Session session) {
            return new DomainAccessHandler(session, service);
        }
    }

    private final DomainAccessService service;

    private DomainAccessHandler(Session session, DomainAccessService service) {
        super(session);
        this.service = service;
    }

    @Override
    public boolean onCrossBoundary(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
                                   Set<ProtectedRegion> entered, Set<ProtectedRegion> exited, MoveType moveType) {
        if ((entered.isEmpty() && exited.isEmpty()) || !(player instanceof BukkitPlayer bukkitPlayer)) {
            return true;
        }
        Player bukkit = bukkitPlayer.getPlayer();
        return service.onCrossing(bukkit, BukkitAdapter.adapt(to),
                WorldGuardRegionAccessLookup.views(entered, player),
                WorldGuardRegionAccessLookup.views(exited, player),
                moveType.isCancellable())
            .isEmpty();
    }
}
