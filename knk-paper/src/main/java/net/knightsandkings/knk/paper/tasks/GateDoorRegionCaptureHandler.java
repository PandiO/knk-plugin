package net.knightsandkings.knk.paper.tasks;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Captures (item 6.3) and re-edits (item 6.4) a GateDoor's region-based geometry: an admin draws
 * a WorldEdit selection (polygon or cuboid) and this class serializes it into the vertex JSON
 * shape documented in {@code WORLDGUARD_REGION_FEASIBILITY.md} §9.1, persisted directly against
 * {@code GateDoor.ClosedRegionData}/{@code OpenedRegionData} via
 * {@link GateDoorsApi#updateRegionData}.
 *
 * <p>Deliberately <strong>not</strong> an {@link IWorldTaskHandler} - unlike {@link
 * WgRegionIdTaskHandler} (whose save/cancel selection-capture mechanics this class's are modeled
 * on), region capture isn't a FormConfig field task routed through the generic WorldTask system;
 * it's a direct admin command ({@code /knk gate door capture|redefine ...}, see {@code
 * GateCommand}) that writes straight to the door via the API - matching item 6.3's own
 * description ("a KnK command persists the resulting vertex list against a GateDoor via the
 * API"), deliberately stopping short of WorldGuard's persistent-region/flag layer (the Option 2
 * decision, WORLDGUARD_REGION_FEASIBILITY.md §6).
 *
 * <p>Round-trip re-edit (6.4) reuses the exact same save/cancel loop as a fresh capture (6.3) -
 * the only difference is {@link #startRedefine} pre-loads the door's existing stored region into
 * the session's {@link RegionSelector} before handing control to the player, mirroring
 * WorldGuard's own {@code /rg redefine} UX (WORLDGUARD_REGION_FEASIBILITY.md §9.2).
 */
public class GateDoorRegionCaptureHandler {
    private static final Logger LOGGER = Logger.getLogger(GateDoorRegionCaptureHandler.class.getName());

    private final Plugin plugin;
    private final GateDoorsApi gateDoorsApi;

    private final Map<Player, CaptureContext> activeCaptures = new HashMap<>();

    private record CaptureContext(int gateDoorId, String gateDoorName, boolean isOpenedRegion) {
    }

    public GateDoorRegionCaptureHandler(Plugin plugin, GateDoorsApi gateDoorsApi) {
        this.plugin = plugin;
        this.gateDoorsApi = gateDoorsApi;
    }

    /**
     * Item 6.3: begin a fresh capture. The player draws a new selection with {@code //sel poly}
     * or {@code //sel cuboid} and types 'save' when done, or 'cancel' to abort.
     */
    public void startCapture(Player player, CachedGateDoor door, boolean isOpenedRegion) {
        activeCaptures.put(player, new CaptureContext(door.getId(), door.getName(), isOpenedRegion));

        LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        session.dispatchCUISelection(BukkitAdapter.adapt(player));

        String slot = isOpenedRegion ? "opened" : "closed";
        player.sendMessage(ChatColor.GOLD + "[Gate Region] Capturing the " + slot + " region for door '" + door.getName() + "'.");
        player.sendMessage(ChatColor.YELLOW + "Draw a selection with '//sel poly' or '//sel cuboid', then type 'save'.");
        player.sendMessage(ChatColor.GRAY + "Or type 'cancel' to abort.");
    }

    /**
     * Item 6.4: re-hydrate the door's existing stored region into the player's active WorldEdit
     * selection so they can redraw/adjust it, instead of starting from scratch.
     */
    public void startRedefine(Player player, CachedGateDoor door, boolean isOpenedRegion) {
        String existingRegionData = isOpenedRegion ? door.getOpenedRegionData() : door.getClosedRegionData();
        if (existingRegionData == null || existingRegionData.isBlank()) {
            player.sendMessage(ChatColor.RED + "[Gate Region] Door '" + door.getName() + "' has no "
                + (isOpenedRegion ? "opened" : "closed") + " region yet - use 'capture' instead.");
            return;
        }

        World world = player.getWorld();
        RegionSelector selector;
        try {
            selector = GateRegionDataFormat.parseAsRegionSelector(BukkitAdapter.adapt(world), existingRegionData);
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "[Gate Region] Failed to parse the door's stored region data: " + e.getMessage());
            LOGGER.warning("Failed to parse region data for door " + door.getId() + ": " + e.getMessage());
            return;
        }

        LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        session.setRegionSelector(BukkitAdapter.adapt(world), selector);
        session.dispatchCUISelection(BukkitAdapter.adapt(player));

        activeCaptures.put(player, new CaptureContext(door.getId(), door.getName(), isOpenedRegion));

        String slot = isOpenedRegion ? "opened" : "closed";
        player.sendMessage(ChatColor.GOLD + "[Gate Region] Redefining the " + slot + " region for door '" + door.getName() + "'.");
        player.sendMessage(ChatColor.YELLOW + "The existing shape is now your active selection - adjust it with normal WorldEdit tools, then type 'save'.");
        player.sendMessage(ChatColor.GRAY + "Or type 'cancel' to abort (the stored region is unchanged until you save).");
    }

    /**
     * Handle chat input from a player during an active capture/redefine.
     * Processes 'save' and 'cancel'.
     *
     * @return true if the message was handled and should be cancelled (not shown in chat)
     */
    public boolean onPlayerChat(Player player, String message) {
        CaptureContext context = activeCaptures.get(player);
        if (context == null) return false;

        String cmd = message.trim().toLowerCase();
        if (cmd.equals("save")) {
            handleSave(player, context);
            return true;
        } else if (cmd.equals("cancel")) {
            cancel(player);
            return true;
        }

        return false;
    }

    public boolean isHandling(Player player) {
        return activeCaptures.containsKey(player);
    }

    public void cancel(Player player) {
        CaptureContext context = activeCaptures.remove(player);
        if (context != null) {
            WorldEdit.getInstance().getSessionManager().remove(BukkitAdapter.adapt(player));
            player.sendMessage(ChatColor.RED + "[Gate Region] Cancelled.");
        }
    }

    private void handleSave(Player player, CaptureContext context) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            World world = player.getWorld();
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));

            Region selection;
            try {
                selection = session.getSelection(BukkitAdapter.adapt(world));
            } catch (IncompleteRegionException e) {
                player.sendMessage(ChatColor.RED + "[Gate Region] No WorldEdit selection found! Please select a region first.");
                return;
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "[Gate Region] Error reading selection: " + e.getMessage());
                LOGGER.warning("Error reading WorldEdit selection for player " + player.getName() + ": " + e.getMessage());
                return;
            }

            String regionDataJson;
            try {
                regionDataJson = GateRegionDataFormat.serialize(selection, world.getName());
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "[Gate Region] Error serializing selection: " + e.getMessage());
                LOGGER.warning("Error serializing WorldEdit selection for player " + player.getName() + ": " + e.getMessage());
                return;
            }

            player.sendMessage(ChatColor.GRAY + "[Gate Region] Saving...");

            gateDoorsApi.updateRegionData(context.gateDoorId(), context.isOpenedRegion(), regionDataJson)
                .thenAccept(v -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    activeCaptures.remove(player);
                    WorldEdit.getInstance().getSessionManager().remove(BukkitAdapter.adapt(player));
                    player.sendMessage(ChatColor.GREEN + "[Gate Region] ✓ Saved the "
                        + (context.isOpenedRegion() ? "opened" : "closed") + " region for door '"
                        + context.gateDoorName() + "'.");
                    LOGGER.info("Saved " + (context.isOpenedRegion() ? "opened" : "closed")
                        + " region data for gate door " + context.gateDoorId() + " (player " + player.getName() + ")");
                }))
                .exceptionally(ex -> {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "[Gate Region] Failed to save: " + ex.getMessage()));
                    LOGGER.warning("Failed to save region data for gate door " + context.gateDoorId() + ": " + ex.getMessage());
                    return null;
                });
        });
    }

}
