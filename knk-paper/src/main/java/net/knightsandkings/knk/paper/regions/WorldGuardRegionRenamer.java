package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.RegionRenamePlan;
import net.knightsandkings.knk.core.regions.RegionRenamePlan.Action;
import net.knightsandkings.knk.core.regions.RegionRenamePlan.Plan;

/**
 * Renames a world task's region to its final name in one world's {@link RegionManager}, replacing the domain's previous
 * region when the API names it (KNG-103; the decision is {@link RegionRenamePlan}).
 *
 * <p>WorldGuard can't rename a region, so the region is rebuilt under the new id. On a plain rename the new region copies
 * the old one's priority, parent, owners, members and flags (owners/members since KNG-43). When it <em>replaces</em> the
 * domain's existing {@code domain_<id>} it is that region redefined in place: the new shape, and the replaced region's
 * priority, parent, owners, members and flags (an admin's {@code pvp allow}, the synced {@code knk-allow-entry}), with the
 * drawn region's own flags and owners only filling gaps. Regions whose parent was a removed region are re-linked to the new
 * one. The managed-region finalizer then applies the category's parent, priority and seed flags as before.
 *
 * <p>All of it happens on the caller's (main) thread before WorldGuard saves; if any step throws, every region is put
 * back the way it was, so the world never ends up with neither region.
 */
public final class WorldGuardRegionRenamer {

    private static final Logger LOGGER = Logger.getLogger(WorldGuardRegionRenamer.class.getName());

    /** @param detail why it failed, or what was replaced/removed/kept; never null */
    public record Result(boolean success, Action action, String detail) {
    }

    private WorldGuardRegionRenamer() {
    }

    public static Result rename(RegionManager regionManager, String oldRegionId, String newRegionId, String replaceRegionId) {
        ProtectedRegion source = regionManager.getRegion(oldRegionId);
        ProtectedRegion target = regionManager.getRegion(newRegionId);
        ProtectedRegion replaced = replaceRegionId == null || replaceRegionId.isBlank() ? null
                : regionManager.getRegion(replaceRegionId.trim());
        Plan plan = RegionRenamePlan.decide(oldRegionId, newRegionId, replaceRegionId,
                source != null, target != null, replaced != null);

        switch (plan.action()) {
            case ALREADY_NAMED:
                return new Result(true, plan.action(), "already named " + newRegionId);
            case REFUSE:
                return new Result(false, plan.action(), plan.note());
            default:
                break;
        }

        ProtectedRegion removed = plan.action() == Action.REPLACE_TARGET ? target
                : plan.action() == Action.RENAME_AND_REMOVE_PREVIOUS ? replaced : null;
        ProtectedRegion newRegion = withNewId(source, newRegionId);
        if (newRegion == null) {
            return new Result(false, Action.REFUSE, "unsupported region type: " + source.getClass().getSimpleName());
        }

        // The settings the new region carries: the replaced domain region's when redefining it in place, else the drawn one's.
        ProtectedRegion settings = plan.action() == Action.REPLACE_TARGET ? removed : source;
        newRegion.setPriority(settings.getPriority());
        newRegion.setOwners(union(source.getOwners(), settings.getOwners()));
        newRegion.setMembers(union(source.getMembers(), settings.getMembers()));
        copyFlags(source, newRegion);
        if (settings != source) {
            copyFlags(settings, newRegion);
        }
        ProtectedRegion parent = settings.getParent() != null ? settings.getParent() : source.getParent();
        if (parent != null && parent != source && parent != removed) {
            try {
                newRegion.setParent(parent);
            } catch (ProtectedRegion.CircularInheritanceException e) {
                LOGGER.warning("Could not keep the parent of renamed region " + newRegionId + ": " + e.getMessage());
            }
        }

        // Regions whose parent is one of the removed regions (e.g. a District's Structures): WorldGuard unsets their parent
        // when it is removed, so they are pointed at the new region. Their original parent is kept for a rollback.
        Map<ProtectedRegion, ProtectedRegion> children = new LinkedHashMap<>();
        for (ProtectedRegion candidate : regionManager.getRegions().values()) {
            ProtectedRegion candidateParent = candidate.getParent();
            if (candidate != source && candidate != removed && candidateParent != null
                    && (candidateParent == source || candidateParent == removed)) {
                children.put(candidate, candidateParent);
            }
        }

        List<ProtectedRegion> takenOut = new ArrayList<>();
        boolean added = false;
        try {
            regionManager.removeRegion(source.getId());
            takenOut.add(source);
            if (removed != null) {
                regionManager.removeRegion(removed.getId());
                takenOut.add(removed);
            }
            regionManager.addRegion(newRegion);
            added = true;
            for (ProtectedRegion child : children.keySet()) {
                child.setParent(newRegion);
            }
        } catch (Exception e) {
            rollBack(regionManager, newRegion, added, takenOut, children);
            return new Result(false, Action.REFUSE, "rename failed and was rolled back: " + e.getMessage());
        }

        String detail = switch (plan.action()) {
            case REPLACE_TARGET -> "replaced the previous " + newRegionId + " in place";
            case RENAME_AND_REMOVE_PREVIOUS -> "removed the previous region " + removed.getId();
            default -> plan.note() != null ? plan.note() : "renamed";
        };
        if (!children.isEmpty()) {
            detail += " (" + children.size() + " child region(s) re-linked)";
        }
        return new Result(true, plan.action(), detail);
    }

    /** The same shape under a new id, or null for a type WorldGuard regions here are never drawn as (e.g. global). */
    private static ProtectedRegion withNewId(ProtectedRegion region, String newRegionId) {
        if (region instanceof ProtectedPolygonalRegion polygon) {
            return new ProtectedPolygonalRegion(newRegionId, polygon.getPoints(),
                    polygon.getMinimumPoint().getBlockY(), polygon.getMaximumPoint().getBlockY());
        }
        if (region instanceof ProtectedCuboidRegion cuboid) {
            return new ProtectedCuboidRegion(newRegionId, cuboid.getMinimumPoint(), cuboid.getMaximumPoint());
        }
        return null;
    }

    private static DefaultDomain union(DefaultDomain first, DefaultDomain second) {
        DefaultDomain result = new DefaultDomain();
        if (first != null) {
            result.addAll(first);
        }
        if (second != null && second != first) {
            result.addAll(second);
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyFlags(ProtectedRegion from, ProtectedRegion to) {
        for (Map.Entry<Flag<?>, Object> entry : from.getFlags().entrySet()) {
            try {
                to.setFlag((Flag) entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LOGGER.warning("Failed to copy flag " + entry.getKey().getName() + " to " + to.getId() + ": " + e.getMessage());
            }
        }
    }

    private static void rollBack(RegionManager regionManager, ProtectedRegion newRegion, boolean added,
                                 List<ProtectedRegion> takenOut, Map<ProtectedRegion, ProtectedRegion> children) {
        try {
            if (added) {
                regionManager.removeRegion(newRegion.getId());
            }
            for (ProtectedRegion region : takenOut) {
                regionManager.addRegion(region);
            }
            for (Map.Entry<ProtectedRegion, ProtectedRegion> child : children.entrySet()) {
                child.getKey().setParent(child.getValue());
            }
        } catch (Exception e) {
            LOGGER.severe("Rolling back the rename to " + newRegion.getId() + " failed; check /rg list: " + e.getMessage());
        }
    }
}
