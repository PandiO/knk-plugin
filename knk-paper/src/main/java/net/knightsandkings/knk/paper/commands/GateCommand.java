package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
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
 */
public class GateCommand implements CommandExecutor {
    private final GateManager gateManager;
    private final GateStructuresApi gateStructuresApi;
    private final UserManager userManager;
    private final UsersCommandApi usersCommandApi;
    private final DistrictGateLoader districtGateLoader;

    public GateCommand(GateManager gateManager, GateStructuresApi gateStructuresApi,
                        UserManager userManager, UsersCommandApi usersCommandApi,
                        DistrictGateLoader districtGateLoader) {
        this.gateManager = gateManager;
        this.gateStructuresApi = gateStructuresApi;
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
        sender.sendMessage(ChatColor.GRAY + "/knk gate open <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate close <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate info <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate list");
        sender.sendMessage(ChatColor.GRAY + "/knk gate passthrough <default|instant|teleport>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin health <name|id> <amount>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin repair <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin tp <name|id>");
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
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin health <name|id> <amount>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin repair <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin tp <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin active <name|id>");
        sender.sendMessage(ChatColor.GRAY + "/knk gate admin invincible <name|id>");
    }

    /**
     * Handle /gate open <name>
     */
    public boolean executeOpen(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate open <name|id>");
            return true;
        }

        String gateName = String.join(" ", args);
        CachedGate gate = findGate(gateName);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' not found.");
            return true;
        }

        // Check permission
        if (!checkPermission(sender, "knk.gate.open." + gate.getId()) && 
            !checkPermission(sender, "knk.gate.open.*")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to open this gate.");
            return true;
        }

        // Check if gate is active
        if (!gate.isActive()) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' is not active.");
            return true;
        }

        // Check if gate is destroyed
        if (gate.isDestroyed()) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' is destroyed and cannot be opened.");
            return true;
        }

        // Try to open
        if (gateManager.openGate(gate.getId())) {
            gateManager.setAnimationCompletionCallback(gate.getId(), state ->
                sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' is now " + state + ".")
            );
            sender.sendMessage(ChatColor.GREEN + "Opening gate '" + gateName + "'...");
            return true;
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Gate '" + gateName + "' is already open or opening.");
            return true;
        }
    }

    /**
     * Handle /gate close <name>
     */
    public boolean executeClose(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate close <name|id>");
            return true;
        }

        String gateName = String.join(" ", args);
        CachedGate gate = findGate(gateName);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' not found.");
            return true;
        }

        // Check permission
        if (!checkPermission(sender, "knk.gate.close." + gate.getId()) && 
            !checkPermission(sender, "knk.gate.close.*")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to close this gate.");
            return true;
        }

        // Check if gate is active
        if (!gate.isActive()) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' is not active.");
            return true;
        }

        // Try to close
        if (gateManager.closeGate(gate.getId())) {
            gateManager.setAnimationCompletionCallback(gate.getId(), state ->
                sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' is now " + state + ".")
            );
            sender.sendMessage(ChatColor.GREEN + "Closing gate '" + gateName + "'...");
            return true;
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Gate '" + gateName + "' is already closed or closing.");
            return true;
        }
    }

    /**
     * Handle /gate info <name>
     */
    public boolean executeInfo(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate info <name|id>");
            return true;
        }

        String gateName = String.join(" ", args);
        CachedGate gate = findGate(gateName);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' not found.");
            return true;
        }

        // Display gate information
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Info: " + gate.getName() + " ━━━");
        sender.sendMessage(ChatColor.GRAY + "ID: " + ChatColor.WHITE + gate.getId());
        sender.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + gate.getGateType());
        String stateLine = formatState(gate.getCurrentState());
        if (gate.isJammed()) {
            stateLine += " " + ChatColor.RED + "(JAMMED)";
        }
        sender.sendMessage(ChatColor.GRAY + "State: " + stateLine);
        sender.sendMessage(ChatColor.GRAY + "Active: " + ChatColor.WHITE + (gate.isActive() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Destroyed: " + ChatColor.WHITE + (gate.isDestroyed() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Health: " + ChatColor.WHITE + 
                String.format("%.0f/%.0f", gate.getHealthCurrent(), gate.getHealthMax()));
        sender.sendMessage(ChatColor.GRAY + "Invincible: " + ChatColor.WHITE + (gate.isInvincible() ? "✓" : "✗"));
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

        List<CachedGate> gates = gateManager.getAllGates().values().stream()
            .sorted(Comparator.comparingInt(CachedGate::getId))
            .toList();

        if (gates.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No gates are loaded. Use /knk gate admin reload after confirming API connectivity.");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "━━━ Gates ━━━");
        for (CachedGate gate : gates) {
            String statusColor = gate.getCurrentState() == AnimationState.OPEN ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
            String distanceStr = senderLoc != null ?
                String.format(" (%.0fm)", gate.getAnchorPoint().distance(senderLoc.toVector())) : "";
            String jammedSuffix = gate.isJammed() ? " " + ChatColor.RED + "(JAMMED)" : "";

                sender.sendMessage(ChatColor.AQUA + "#" + gate.getId() + " " + gate.getName() +
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
     * Handle /gate admin health <name> <amount>
     */
    public boolean executeAdminHealth(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin health <name|id> <amount>");
            return true;
        }

        String gateName = String.join(" ", Arrays.copyOf(args, args.length - 1));
        CachedGate gate = findGate(gateName);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' not found.");
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
     * Handle /gate admin repair <name>
     */
    public boolean executeAdminRepair(CommandSender sender, String[] args) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin repair <name|id>");
            return true;
        }

        String gateName = String.join(" ", args);
        CachedGate gate = findGate(gateName);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' not found.");
            return true;
        }

        gate.setHealthCurrent(gate.getHealthMax());
        gate.setIsDestroyed(false);
        persistHealthChange(gate);
        persistState(gate);
        sender.sendMessage(ChatColor.GREEN + "Repaired gate '" + gateName + "'. Health: " +
                gate.getHealthCurrent() + "/" + gate.getHealthMax());

        return true;
    }

    /**
     * Handle /gate admin tp <name>
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
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin tp <name|id>");
            return true;
        }

        String gateName = String.join(" ", args);
        CachedGate gate = findGate(gateName);

        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + gateName + "' not found.");
            return true;
        }

        Player player = (Player) sender;
        Vector anchorPoint = gate.getAnchorPoint();
        Location teleportLoc = new Location(player.getWorld(), 
            anchorPoint.getX() + 0.5, 
            anchorPoint.getY() + 1, 
            anchorPoint.getZ() + 0.5);

        player.teleport(teleportLoc);
        sender.sendMessage(ChatColor.GREEN + "Teleported to gate '" + gateName + "'.");

        return true;
    }

    public boolean executeAdminToggleActive(CommandSender sender, String[] args) {
        CachedGate gate = findAdminGate(sender, args, "active");
        if (gate == null) {
            return true;
        }

        gate.setIsActive(!gate.isActive());
        persistOperationalSettings(gate);
        sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' active: " + gate.isActive());
        return true;
    }

    public boolean executeAdminToggleInvincible(CommandSender sender, String[] args) {
        CachedGate gate = findAdminGate(sender, args, "invincible");
        if (gate == null) {
            return true;
        }

        gate.setIsInvincible(!gate.isInvincible());
        persistOperationalSettings(gate);
        sender.sendMessage(ChatColor.GREEN + "Gate '" + gate.getName() + "' invincible: " + gate.isInvincible());
        return true;
    }

    /**
     * Check if sender has a permission.
     */
    private boolean checkPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    private CachedGate findAdminGate(CommandSender sender, String[] args, String settingName) {
        if (!sender.hasPermission("knk.gate.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return null;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk gate admin " + settingName + " <name|id>");
            return null;
        }

        String selector = String.join(" ", args);
        CachedGate gate = findGate(selector);
        if (gate == null) {
            sender.sendMessage(ChatColor.RED + "Gate '" + selector + "' not found.");
        }
        return gate;
    }

    private void persistOperationalSettings(CachedGate gate) {
        if (gateStructuresApi == null) {
            return;
        }

        gateStructuresApi.updateOperationalSettings(gate.getId(), gate.isActive(), gate.isInvincible())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    private void persistHealthChange(CachedGate gate) {
        if (gateStructuresApi == null) {
            return;
        }

        gateStructuresApi.updateGateHealth(gate.getId(), gate.getHealthCurrent())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    private void persistState(CachedGate gate) {
        if (gateStructuresApi == null) {
            return;
        }

        boolean isOpened = gate.getCurrentState() == AnimationState.OPEN;
        gateStructuresApi.updateGateState(gate.getId(), isOpened, gate.isDestroyed(), gate.isJammed())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    private CachedGate findGate(String nameOrId) {
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
