package net.knightsandkings.knk.paper.regions.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.RegionView;

/** {@link DomainAccessService.RegionLookup} on WorldGuard: the regions and their KnK access flags. */
public final class WorldGuardRegionAccessLookup implements DomainAccessService.RegionLookup {

    private final RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();

    @Override
    public List<RegionView> at(Location location, Player player) {
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        return views(query.getApplicableRegions(BukkitAdapter.adapt(location)).getRegions(), localPlayer);
    }

    @Override
    public boolean hasWorldGuardBypass(Player player, Location location) {
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        return WorldGuard.getInstance().getPlatform().getSessionManager()
            .hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld()));
    }

    /** The views of WorldGuard regions for one player (also used by {@link DomainAccessHandler}). */
    static List<RegionView> views(Collection<ProtectedRegion> regions, LocalPlayer player) {
        List<RegionView> views = new ArrayList<>(regions.size());
        for (ProtectedRegion region : regions) {
            views.add(new View(region, player));
        }
        return views;
    }

    private record View(ProtectedRegion region, LocalPlayer player) implements RegionView {
        @Override
        public String regionId() {
            return region.getId();
        }

        @Override
        public String displayName() {
            return DomainAccessFlags.name() != null ? region.getFlag(DomainAccessFlags.name()) : null;
        }

        @Override
        public AccessState entry() {
            return state(DomainAccessFlags.entry());
        }

        @Override
        public AccessState exit() {
            return state(DomainAccessFlags.exit());
        }

        @Override
        public boolean isMember() {
            // Owners and members, including those of parent regions (WorldGuard's own rule).
            return player != null && region.isMember(player);
        }

        private AccessState state(StateFlag flag) {
            if (flag == null) {
                return AccessState.UNSET;
            }
            StateFlag.State value = region.getFlag(flag);
            if (value == null) {
                return AccessState.UNSET;
            }
            return value == StateFlag.State.DENY ? AccessState.DENY : AccessState.ALLOW;
        }
    }
}
