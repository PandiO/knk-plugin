package net.knightsandkings.knk.paper.crossplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.core.crossplay.BlockResyncWatch;
import net.knightsandkings.knk.core.crossplay.BlockResyncWatch.Pos;
import net.knightsandkings.knk.core.crossplay.BlockResyncWatch.Resend;

/**
 * Ghost blocks on Bedrock clients (alpha test run 1, PandiO/knk-workspace#161): when a Bedrock (Floodgate) player's
 * break is refused - by WorldGuard, the global-region rules, the hub, a gate, a loading player's lock, any plugin -
 * the real block (and the other half of a door, tall plant or bed) is sent to that player again while they keep
 * mining and shortly after ({@link BlockResyncWatch}). Java clients roll their own prediction back and never need
 * it. Every refusal is caught in one place, at {@code MONITOR}, so a new protection needs nothing extra:
 * <ul>
 *   <li>a cancelled {@link BlockBreakEvent} (the break was finished and refused);</li>
 *   <li>a cancelled {@link BlockDamageEvent}, or a left-click on a block whose use was denied (refused at the
 *       start: the server never mines, the client does).</li>
 * </ul>
 * Arm swings keep the watch alive; {@link #tick} runs every server tick. Main thread only.
 */
public final class BedrockBlockResync implements Listener {

    private final BlockResyncWatch watch;
    private final Predicate<UUID> applies;
    private final Function<UUID, Player> onlinePlayer;
    private long tick;

    /**
     * @param watch        the bookkeeping
     * @param applies      whether a player's refused breaks are resent (Bedrock players, or everyone when configured)
     * @param onlinePlayer the online player by id, or null
     */
    public BedrockBlockResync(BlockResyncWatch watch, Predicate<UUID> applies, Function<UUID, Player> onlinePlayer) {
        this.watch = Objects.requireNonNull(watch, "watch");
        this.applies = Objects.requireNonNull(applies, "applies");
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            refused(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(BlockDamageEvent event) {
        if (event.isCancelled()) {
            refused(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null
            && event.useInteractedBlock() == Event.Result.DENY) {
            refused(event.getPlayer(), event.getClickedBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            watch.swung(event.getPlayer().getUniqueId(), tick);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        watch.forget(event.getPlayer().getUniqueId());
    }

    private void refused(Player player, Block block) {
        if (player == null || block == null || !applies.test(player.getUniqueId())) {
            return;
        }
        for (Block b : withPartners(block)) {
            watch.refused(player.getUniqueId(), new Pos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()), tick);
        }
    }

    /**
     * The block and the blocks that go with it: the other half of a door or tall plant, the other end of a bed (Geyser
     * shows a broken door's lower half wrongly, GeyserMC/Geyser#5746).
     */
    static List<Block> withPartners(Block block) {
        List<Block> out = new ArrayList<>(2);
        out.add(block);
        BlockData data = block.getBlockData();
        if (data instanceof Bisected bisected) {
            out.add(block.getRelative(bisected.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN));
        } else if (data instanceof Bed bed) {
            out.add(block.getRelative(bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace()));
        }
        return out;
    }

    /** Every server tick: the due resends. */
    public void tick() {
        tick++;
        for (Resend resend : watch.due(tick)) {
            Player player = onlinePlayer.apply(resend.playerId());
            if (player == null) {
                watch.forget(resend.playerId());
                continue;
            }
            World world = player.getWorld();
            if (world == null || !world.getName().equals(resend.pos().world())
                || !world.isChunkLoaded(resend.pos().x() >> 4, resend.pos().z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(resend.pos().x(), resend.pos().y(), resend.pos().z());
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }
}
