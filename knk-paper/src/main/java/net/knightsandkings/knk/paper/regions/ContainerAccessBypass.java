package net.knightsandkings.knk.paper.regions;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

/**
 * Who may open storage where a region's rules refuse it (PandiO/knk-workspace#157). Towns and other domains refuse
 * chests and other storage to anyone who isn't a member of the region unless the region allows it (WorldGuard's
 * {@code chest-access}, falling back to {@code build}) - developer decision 2026-10-25: that is intended. These pass
 * anyway:
 * <ul>
 *   <li>a player in staff mode or owner mode, whatever their rank;</li>
 *   <li>a holder of {@value #NODE} (seeded for Moderator and Admin, not for the general Staff group).</li>
 * </ul>
 * Storage is a block whose state holds an inventory (chests, barrels, shulker boxes, hoppers, dispensers, droppers,
 * furnaces, brewing stands, ...) or a storage minecart or chest boat. Bukkit-only, so it can be unit-tested;
 * {@code ContainerAccessWorldGuardListener} applies it to WorldGuard's use events.
 */
public final class ContainerAccessBypass {

    public static final String NODE = "knk.region.bypass.containers";

    private final Predicate<Player> inStaffOrOwnerMode;
    private final Predicate<Player> holdsNode;

    /**
     * @param inStaffOrOwnerMode whether the player's active mode is staff or owner mode
     * @param holdsNode          whether the player holds {@value #NODE} (Bukkit or in-house grant; cached answer)
     */
    public ContainerAccessBypass(Predicate<Player> inStaffOrOwnerMode, Predicate<Player> holdsNode) {
        this.inStaffOrOwnerMode = Objects.requireNonNull(inStaffOrOwnerMode, "inStaffOrOwnerMode");
        this.holdsNode = Objects.requireNonNull(holdsNode, "holdsNode");
    }

    /** Whether the player may use these blocks although the region refuses it: all storage, and the player bypasses. */
    public boolean allowsBlocks(Player player, Collection<Block> blocks) {
        if (player == null || blocks == null || blocks.isEmpty() || !blocks.stream().allMatch(ContainerAccessBypass::isStorage)) {
            return false;
        }
        return bypasses(player);
    }

    /** {@link #allowsBlocks} for an entity: a storage minecart or chest boat. */
    public boolean allowsEntity(Player player, Entity entity) {
        return player != null && isStorage(entity) && bypasses(player);
    }

    /** Staff mode or owner mode first (no permission lookup), then the node. */
    public boolean bypasses(Player player) {
        return inStaffOrOwnerMode.test(player) || holdsNode.test(player);
    }

    static boolean isStorage(Block block) {
        if (block == null) {
            return false;
        }
        BlockState state = block.getState(false);
        return state instanceof InventoryHolder;
    }

    static boolean isStorage(Entity entity) {
        return entity instanceof InventoryHolder && (entity instanceof Minecart || entity instanceof Boat);
    }
}
