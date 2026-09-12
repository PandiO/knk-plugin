package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.dto.GateStructureOverridesUpdateDto;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.gates.GateDoorOpenStateMapper;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Gate command implementation providing player and admin gate control.
 * Supports opening/closing, status, listing, and admin operations.
 *
 * <p>Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md)
 * split a "gate" into a GateStructure with one or more independently-animating GateDoors. Every
 * door-addressing command (open/close/info/admin health|repair|tp|active|invincible) accepts
 * either a bare door selector (id or name - unchanged from before item 5, still works for a
 * structure with only one door or a globally-unique door name) or decision 5.0-D's two-level
 * {@code <gateStructure> <gateDoor>} form - see {@link #resolveDoor}.
 */
public class GateCommand implements CommandExecutor {
    private final GateManager gateManager;
    private final GateStructuresApi gateStructuresApi;
    private final GateDoorsApi gateDoorsApi;
    private final UserManager userManager;
    private final UsersCommandApi usersCommandApi;
    private final DistrictGateLoader districtGateLoader;

    public GateCommand(GateManager gateManager, GateStructuresApi gateStructuresApi, GateDoorsApi gateDoorsApi,
                        UserManager userManager, UsersCommandApi usersCommandApi,
                        DistrictGateLoader districtGateLoader) {
        this.gateManager = gateManager;
        this.gateStructuresApi = gateStructuresApi;
        this.gateDoorsApi = gateDoorsApi;
        this.userManager = userManager;
        this.usersCommandApi = usersCommandApi;
        this.districtGateLoader = districtGateLoader;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        return switch (subcommand) {
            case "open" -> executeOpen(sender, subArgs);
            case "close" -> executeClose(sender, subArgs);
            case "info" -> executeInfo(sender, subArgs);
            case "list" -> executeList(sender, subArgs);
            case "passthrough" -> executePassThrough(sender, subArgs);
            case "admin" -> executeAdmin(sender, subArgs);
            case "help", "?" -> {
                sendHelp(sender);
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown gate subcommand: " + args[0]);
                sendHelp(sender);
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Commands ━━━");
        sender.sendMessage(ChatColor.GRAY + "/knk gate open <door name|id> | <structure> <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate close <door name|id> | <structure> <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate info <door name|id> | <structure> <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate list");
        sender.sendMessage(ChatColor.GRAY + "/knk gate passthrough <default|instant|teleport>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin health <door name|id> <amount>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin repair <door name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin tp <door name|id>");
    }

    /**
     * Handle /gate passthrough <default|instant|teleport>: sets the sender's own preferred gate
     * pass-through method, updating the in-memory cache immediately and persisting to the backend
     * asynchronously (matching the persistHealthChange/persistState reload-on-failure idiom).
     */
    public boolean executePassThrough(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can set a pass-through method.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate passthrough <default|instant|teleport>");
            return true;
        }

        GatePassThroughMethod method = switch (args[0].toLowerCase()) {
            case "default" -> GatePassThroughMethod.DEFAULT;
            case "instant" -> GatePassThroughMethod.INSTANT_OPEN;
            case "teleport" -> GatePassThroughMethod.TELEPORT;
            default -> null;
        };

        if (method == null) {
            sender.sendMessage(ChatColor.RED + "Unknown pass-through method '" + args[0] + "'. Use default, instant, or teleport.");
            return true;
        }

        PlayerUserData current = userManager.getCachedUser(player.getUniqueId());
        if (current == null || current.userId() == null) {
            sender.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
            return true;
        }

        userManager.updateCachedUser(player.getUniqueId(), current.withGatePassThroughMethodDefault(method));

        if (usersCommandApi != null) {
            usersCommandApi.setGatePassThroughMethodById(current.userId(), method)
                .exceptionally(error -> {
                    sender.sendMessage(ChatColor.RED + "Failed to save your pass-through method; it may reset next time you join.");
                    return null;
                });
        }

        sender.sendMessage(ChatColor.GREEN + "Gate pass-through method set to " + method + ".");
        return true;
    }

    private boolean executeAdmin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendAdminHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        return switch (action) {
            case "health" -> executeAdminHealth(sender, subArgs);
            case "repair" -> executeAdminRepair(sender, subArgs);
            case "tp" -> executeAdminTeleport(sender, subArgs);
            case "reload" -> executeAdminReload(sender, subArgs);
            case "active" -> executeAdminToggleActive(sender, subArgs);
            case "invincible" -> executeAdminToggleInvincible(sender, subArgs);
            case "override" -> executeAdminOverride(sender, subArgs);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown admin gate action: " + args[0]);
                sendAdminHelp(sender);
                yield true;
            }
        };
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Admin Commands ━━━");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin reload");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin reload district <id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin health <door> <amount>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin repair <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin tp <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin active <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin invincible <door>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin override <structure> <field> <value|clear>");
        sender.sendMessage(ChatColor.GRAY + "  fields: active, destroyed, invincible, canrespawn, openedstate");
    }

    /**
     * Handle /gate open <door> | <structure> <door>
     */
    public boolean executeOpen(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate open <door name|id> | <structure> <door>");
            return true;
        }

        CachedGateDoor gate = resolveDoor(args);
        String selector = String.join(" ", args);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + selector + "' not found.");
            return true;
        }

        // Check permission
        if (!checkPermission(sender, "knk.gate.open." + gate.getId()) &&
            !checkPermission(sender, "knk.gate.open.*")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to open this gate.");
            return true;
        }

        // Check if gate is active
        if (!gate.isEffectivelyActive()) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gate.getName() + "' is not active.");
            return true;
        }

        // Check if gate is destroyed
        if (gate.isEffectivelyDestroyed()) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gate.getName() + "' is destroyed and cannot be opened.");
            return true;
        }

        // Try to open
        if (gateManager.openGate(gate.getId())) {
            gateManager.setAnimationCompletionCallback(gate.getId(), state ->
                sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' is now " + state + ".")
            );
            sender.sendMessage(ChatColor.GREEN + "Opening gate '" + gate.getName() + "'...");
            return true;
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Gate '" + gate.getName() + "' is already open or opening.");
            return true;
        }
    }

    /**
     * Handle /gate close <door> | <structure> <door>
     */
    public boolean executeClose(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate close <door name|id> | <structure> <door>");
            return true;
        }

        CachedGateDoor gate = resolveDoor(args);
        String selector = String.join(" ", args);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + selector + "' not found.");
            return true;
        }

        // Check permission
        if (!checkPermission(sender, "knk.gate.close." + gate.getId()) &&
            !checkPermission(sender, "knk.gate.close.*")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to close this gate.");
            return true;
        }

        // Check if gate is active
        if (!gate.isEffectivelyActive()) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gate.getName() + "' is not active.");
            return true;
        }

        // Try to close
        if (gateManager.closeGate(gate.getId())) {
            gateManager.setAnimationCompletionCallback(gate.getId(), state ->
                sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' is now " + state + ".")
            );
            sender.sendMessage(ChatColor.GREEN + "Closing gate '" + gate.getName() + "'...");
            return true;
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Gate '" + gate.getName() + "' is already closed or closing.");
            return true;
        }
    }

    /**
     * Handle /gate info <door> | <structure> <door>
     */
    public boolean executeInfo(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate info <door name|id> | <structure> <door>");
            return true;
        }

        CachedGateDoor gate = resolveDoor(args);
        String selector = String.join(" ", args);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + selector + "' not found.");
            return true;
        }

        // Display gate information
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Info: " + gate.getStructureName() + " / " + gate.getName() + " ━━━");
        sender.sendMessage(ChatColor.GRAY + "ID: " + ChatColor.WHITE + gate.getId());
        sender.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + gate.getGateType());
        String stateLine = formatState(gate.getCurrentState());
        if (gate.isJammed()) {
            stateLine += " " + ChatColor.RED + "(JAMMED)";
        }
        sender.sendMessage(ChatColor.GRAY + "State: " + stateLine);
        sender.sendMessage(ChatColor.GRAY + "Active: " + ChatColor.WHITE + (gate.isEffectivelyActive() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Destroyed: " + ChatColor.WHITE + (gate.isEffectivelyDestroyed() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Health: " + ChatColor.WHITE +
                String.format("%.0f/%.0f", gate.getHealthCurrent(), gate.getHealthMax()));
        sender.sendMessage(ChatColor.GRAY + "Invincible: " + ChatColor.WHITE + (gate.isEffectivelyInvincible() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Blocks: " + ChatColor.WHITE + gate.getBlocks().size());
        sender.sendMessage(ChatColor.GRAY + "Motion Type: " + ChatColor.WHITE + gate.getMotionType());
        sender.sendMessage(ChatColor.GRAY + "Face Direction: " + ChatColor.WHITE + gate.getFaceDirection());

        return true;
    }

    /**
     * Handle /gate list
     */
    public boolean executeList(CommandSender sender, String[] args) {
        final Location senderLoc;
        if (sender instanceof Player) {
            senderLoc = ((Player) sender).getLocation();
        } else {
            senderLoc = null;
        }

        List<CachedGateDoor> gates = gateManager.getAllGates().values().stream()
            .sorted(Comparator.comparingInt(CachedGateDoor::getId))
            .toList();

        if (gates.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No gates are loaded. Use /knk gate admin reload after confirming API connectivity.");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "━━━ Gates ━━━");
        for (CachedGateDoor gate : gates) {
            String statusColor = gate.getCurrentState() == AnimationState.OPEN ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
            String distanceStr = senderLoc != null ?
                String.format(" (%.0fm)", gate.getAnchorPoint().distance(senderLoc.toVector())) : "";
            String jammedSuffix = gate.isJammed() ? " " + ChatColor.RED + "(JAMMED)" : "";

                sender.sendMessage(ChatColor.AQUA + "#" + gate.getId() + " " + gate.getStructureName() + " / " + gate.getName() +
                    ChatColor.GRAY + " [" + gate.getGateType() + "]" +
                    statusColor + " " + gate.getCurrentState() + jammedSuffix + distanceStr);
        }

        return true;
    }

    /**
     * Handle /gate admin reload [district <id>]. Bare "reload" does a full world reload (as
     * before); "reload district <id>" force-refreshes just one district's gates via
     * DistrictGateLoader, without needing a full world reload - a district's gates already load
     * automatically the first time a player enters it (see KnKPlugin's region-transition
     * wiring), this is just for forcing a refresh after editing an already-loaded district's
     * gates in the web app.
     */
    public boolean executeAdminReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length >= 2 && "district".equalsIgnoreCase(args[0])) {
            return executeAdminReloadDistrict(sender, args[1]);
        }

        sender.sendMessage(ChatColor.YELLOW + "Reloading gates from API...");
        gateManager.reloadGates().thenRun(() -> {
            int gateCount = gateManager.getAllGates().size();
            sender.sendMessage(ChatColor.GREEN + "Loaded " + gateCount + " gates from API.");
        }).exceptionally(ex -> {
            sender.sendMessage(ChatColor.RED + "Failed to reload gates: " + ex.getMessage());
            return null;
        });

        return true;
    }

    private boolean executeAdminReloadDistrict(CommandSender sender, String districtIdArg) {
        if (districtGateLoader == null) {
            sender.sendMessage(ChatColor.RED + "District gate loading isn't configured on this server.");
            return true;
        }

        int districtId;
        try {
            districtId = Integer.parseInt(districtIdArg);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid district id: " + districtIdArg);
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Reloading gates for district " + districtId + "...");
        districtGateLoader.forceReload(districtId).thenRun(() ->
            sender.sendMessage(ChatColor.GREEN + "Reloaded gates for district " + districtId + ".")
        ).exceptionally(ex -> {
            sender.sendMessage(ChatColor.RED + "Failed to reload district " + districtId + ": " + ex.getMessage());
            return null;
        });

        return true;
    }

    /**
     * Handle /gate admin health <door> <amount>
     */
    public boolean executeAdminHealth(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin health <door name|id> <amount>");
            return true;
        }

        String[] doorArgs = Arrays.copyOf(args, args.length - 1);
        CachedGateDoor gate = resolveDoor(doorArgs);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + String.join(" ", doorArgs) + "' not found.");
            return true;
        }

        try {
            double amount = Double.parseDouble(args[args.length - 1]);
            gate.setHealthCurrent(Math.max(0, Math.min(amount, gate.getHealthMax())));
            persistHealthChange(gate);
            sender.sendMessage(ChatColor.GREEN + "Set gate health to " + gate.getHealthCurrent());
            return true;
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid health value: " + args[args.length - 1]);
            return true;
        }
    }

    /**
     * Handle /gate admin repair <door>
     */
    public boolean executeAdminRepair(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin repair <door name|id>");
            return true;
        }

        CachedGateDoor gate = resolveDoor(args);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + String.join(" ", args) + "' not found.");
            return true;
        }

        gate.setHealthCurrent(gate.getHealthMax());
        gate.setIsDestroyed(false);
        persistHealthChange(gate);
        persistState(gate);
        sender.sendMessage(ChatColor.GREEN + "Repaired gate '" + gate.getName() + "'. Health: " +
                gate.getHealthCurrent() + "/" + gate.getHealthMax());

        return true;
    }

    /**
     * Handle /gate admin tp <door>
     */
    public boolean executeAdminTeleport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can teleport.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin tp <door name|id>");
            return true;
        }

        CachedGateDoor gate = resolveDoor(args);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + String.join(" ", args) + "' not found.");
            return true;
        }

        Player player = (Player) sender;
        Vector anchorPoint = gate.getAnchorPoint();
        Location teleportLoc = new Location(player.getWorld(),
            anchorPoint.getX() + 0.5,
            anchorPoint.getY() + 1,
            anchorPoint.getZ() + 0.5);

        player.teleport(teleportLoc);
        sender.sendMessage(ChatColor.GREEN + "Teleported to gate '" + gate.getName() + "'.");

        return true;
    }

    public boolean executeAdminToggleActive(CommandSender sender, String[] args) {
        CachedGateDoor gate = findAdminGate(sender, args, "active");
        if (gate == null) {
            return true;
        }

        gate.setIsActive(!gate.isActive());
        persistOperationalSettings(gate);
        sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' active: " + gate.isActive());
        return true;
    }

    public boolean executeAdminToggleInvincible(CommandSender sender, String[] args) {
        CachedGateDoor gate = findAdminGate(sender, args, "invincible");
        if (gate == null) {
            return true;
        }

        gate.setIsInvincible(!gate.isInvincible());
        persistOperationalSettings(gate);
        sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' invincible: " + gate.isInvincible());
        return true;
    }

    /**
     * Handle /gate admin override <structure> <field> <value|clear> - decision 5.0-B's
     * structure-level cascading override: sets/clears one of the nullable override columns on
     * GateStructure so every child door's effective value reflects it immediately (no per-door
     * write - see CachedGateDoor.isEffectivelyXxx()). Persists via the backend's
     * PATCH .../overrides endpoint, then mirrors the change onto the in-memory CachedGateStructure
     * so it takes effect immediately without waiting for a reload.
     *
     * <p>Covers the fields most relevant to the two use cases named in the backlog (an admin
     * force-state command, and the future Siege capture-destroys-all-doors event): active,
     * destroyed, invincible, canrespawn, openedstate. The remaining cascade-overridable fields
     * (pass-through/display settings) are supported by the underlying model and API but not yet
     * exposed here - add a case below if/when a concrete admin or Siege use needs one.
     */
    public boolean executeAdminOverride(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin override <structure> <field> <value|clear>");
            sender.sendMessage(ChatColor.YELLOW + "Fields: active, destroyed, invincible, canrespawn, openedstate");
            return true;
        }

        String field = args[args.length - 2].toLowerCase();
        String valueArg = args[args.length - 1];
        String[] structureArgs = Arrays.copyOf(args, args.length - 2);
        String structureSelector = String.join(" ", structureArgs);

        CachedGateStructure structure = resolveStructure(structureSelector);
        if (structure == null) {
            sender.sendMessage(ChatColor.RED + "Gate structure '" + structureSelector + "' not found.");
            return true;
        }

        boolean clear = "clear".equalsIgnoreCase(valueArg);
        GateStructureOverridesUpdateDto request = new GateStructureOverridesUpdateDto();

        switch (field) {
            case "active" -> {
                Boolean value = clear ? null : parseBoolean(valueArg);
                if (!clear && value == null) {
                    sender.sendMessage(ChatColor.RED + "Value for 'active' must be true, false, or clear.");
                    return true;
                }
                request.setClearIsActiveOverride(clear);
                request.setIsActiveOverride(value);
                structure.setIsActiveOverride(value);
            }
            case "destroyed" -> {
                Boolean value = clear ? null : parseBoolean(valueArg);
                if (!clear && value == null) {
                    sender.sendMessage(ChatColor.RED + "Value for 'destroyed' must be true, false, or clear.");
                    return true;
                }
                request.setClearIsDestroyedOverride(clear);
                request.setIsDestroyedOverride(value);
                structure.setIsDestroyedOverride(value);
            }
            case "invincible" -> {
                Boolean value = clear ? null : parseBoolean(valueArg);
                if (!clear && value == null) {
                    sender.sendMessage(ChatColor.RED + "Value for 'invincible' must be true, false, or clear.");
                    return true;
                }
                request.setClearIsInvincibleOverride(clear);
                request.setIsInvincibleOverride(value);
                structure.setIsInvincibleOverride(value);
            }
            case "canrespawn" -> {
                Boolean value = clear ? null : parseBoolean(valueArg);
                if (!clear && value == null) {
                    sender.sendMessage(ChatColor.RED + "Value for 'canrespawn' must be true, false, or clear.");
                    return true;
                }
                request.setClearCanRespawnOverride(clear);
                request.setCanRespawnOverride(value);
                structure.setCanRespawnOverride(value);
            }
            case "openedstate" -> {
                String value = clear ? null : valueArg.toUpperCase();
                if (!clear && !isValidOpenedState(value)) {
                    sender.sendMessage(ChatColor.RED + "Value for 'openedstate' must be CLOSED, OPEN, or clear.");
                    return true;
                }
                request.setClearOpenedStateOverride(clear);
                request.setOpenedStateOverride(value);
                structure.setOpenedStateOverride(value);
                // Give the override real, immediate effect by driving each door through the
                // normal state machine (which already resolves effective active/destroyed) -
                // rather than leaving AnimationState stale until the door's next own trigger.
                if ("OPEN".equals(value)) {
                    for (CachedGateDoor door : gateManager.getDoorsForStructure(structure.getId())) {
                        gateManager.openGate(door.getId());
                    }
                } else if ("CLOSED".equals(value)) {
                    for (CachedGateDoor door : gateManager.getDoorsForStructure(structure.getId())) {
                        gateManager.closeGate(door.getId());
                    }
                }
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown override field '" + field + "'. "
                    + "Fields: active, destroyed, invincible, canrespawn, openedstate");
                return true;
            }
        }

        if (gateStructuresApi != null) {
            gateStructuresApi.updateOverrides(structure.getId(), request).exceptionally(error -> {
                sender.sendMessage(ChatColor.RED + "Override applied locally, but failed to persist: " + error.getMessage());
                return null;
            });
        }

        sender.sendMessage(clear
            ? ChatColor.GREEN + "Cleared " + field + " override on '" + structure.getName() + "'."
            : ChatColor.GREEN + "Set " + field + " override on '" + structure.getName() + "' to " + valueArg + ".");
        return true;
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    private static boolean isValidOpenedState(String value) {
        return "CLOSED".equals(value) || "OPEN".equals(value);
    }

    /**
     * Check if sender has a permission.
     */
    private boolean checkPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    private CachedGateDoor findAdminGate(CommandSender sender, String[] args, String settingName) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return null;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin " + settingName + " <door name|id>");
            return null;
        }

        CachedGateDoor gate = resolveDoor(args);
        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate door '" + String.join(" ", args) + "' not found.");
        }
        return gate;
    }

    private void persistOperationalSettings(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            return;
        }

        gateDoorsApi.updateOperationalSettings(gate.getId(), gate.isActive(), gate.isInvincible())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    private void persistHealthChange(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            return;
        }

        gateDoorsApi.updateHealth(gate.getId(), gate.getHealthCurrent())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    private void persistState(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            return;
        }

        String openedState = GateDoorOpenStateMapper.toWireValue(gate.getCurrentState(), gate.isJammed());
        gateDoorsApi.updateState(gate.getId(), openedState, gate.isDestroyed())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    /**
     * Resolves a door selector to a CachedGateDoor. Tries, in order:
     * <ol>
     *   <li>The full joined args as a direct door id or name (unchanged from before item 5 -
     *       still works for a structure with only one door, or a globally-unique door name).</li>
     *   <li>If that fails and there are 2+ args: args[0] as a structure id/name, and the
     *       remaining args (joined) as a door id/name within that structure (decision 5.0-D's
     *       {@code <gateStructure> <gateDoor>} syntax).</li>
     * </ol>
     */
    private CachedGateDoor resolveDoor(String[] args) {
        if (args.length == 0) {
            return null;
        }

        String joined = String.join(" ", args);
        CachedGateDoor direct = findGateByIdOrName(joined);
        if (direct != null) {
            return direct;
        }

        if (args.length >= 2) {
            CachedGateStructure structure = resolveStructure(args[0]);
            if (structure != null) {
                String doorSelector = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                return findDoorInStructure(structure.getId(), doorSelector);
            }
        }

        return null;
    }

    private CachedGateStructure resolveStructure(String nameOrId) {
        try {
            return gateManager.getStructure(Integer.parseInt(nameOrId));
        } catch (NumberFormatException ignored) {
            return gateManager.getStructureByName(nameOrId);
        }
    }

    private CachedGateDoor findDoorInStructure(int structureId, String doorNameOrId) {
        Integer doorId = null;
        try {
            doorId = Integer.parseInt(doorNameOrId);
        } catch (NumberFormatException ignored) {
            // not an id, fall through to name matching
        }

        for (CachedGateDoor door : gateManager.getDoorsForStructure(structureId)) {
            if ((doorId != null && door.getId() == doorId) || door.getName().equalsIgnoreCase(doorNameOrId)) {
                return door;
            }
        }
        return null;
    }

    private CachedGateDoor findGateByIdOrName(String nameOrId) {
        try {
            return gateManager.getGate(Integer.parseInt(nameOrId));
        } catch (NumberFormatException ignored) {
            return gateManager.getGateByName(nameOrId);
        }
    }

    /**
     * Format animation state for display.
     */
    private String formatState(AnimationState state) {
        if (state == null) {
            return ChatColor.GRAY + "UNKNOWN";
        }
        return switch (state) {
            case OPEN -> ChatColor.GREEN + "OPEN";
            case OPENING -> ChatColor.YELLOW + "OPENING";
            case CLOSED -> ChatColor.RED + "CLOSED";
            case CLOSING -> ChatColor.YELLOW + "CLOSING";
            default -> ChatColor.GRAY + state.toString();
        };
    }
}
