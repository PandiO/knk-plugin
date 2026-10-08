package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateCommandKeywords;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateToggle;
import net.knightsandkings.knk.paper.commands.GateCommandSupport.Request;
import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static net.knightsandkings.knk.paper.commands.GateCommandSupport.DOOR_ROOT;
import static net.knightsandkings.knk.paper.commands.GateCommandSupport.doorLabel;
import static net.knightsandkings.knk.paper.commands.GateCommandSupport.filterByPrefix;

/**
 * The door layer of the gate commands (KNG-77): {@code /knk gatedoor <sub> <door>}, alias
 * {@code /gatedoor}. Every subcommand acts on exactly one GateDoor. A door is named by its id, its
 * name, {@code <structure> <door>} (decision 5.0-D), {@code here} (KNG-78), or - for open, close,
 * toggle, info and repair - nothing at all, which uses the door the player looks at (KNG-79); see
 * {@link GateCommandSupport}.
 *
 * <p>Permissions: open/close/toggle need {@code knk.gatedoor.<open|close>.<doorId>} or
 * {@code knk.gatedoor.<open|close>.*}, or the same right on the door's whole gate
 * ({@code knk.gate.<open|close>.<structureId>} / {@code .*}). The rest (repair, tp, health, active,
 * invincible, capture, redefine) need {@code knk.gatedoor.admin} or {@code knk.gate.admin}.
 * info and list are open to everyone.
 */
public class GateDoorCommand implements CommandExecutor {
    /** The wildcard nodes this command checks from the cache; warmed before it runs. */
    public static final List<String> CHECKED_NODES = List.of(GateCommandSupport.GATEDOOR_ADMIN, GateCommandSupport.GATE_ADMIN,
        "knk.gatedoor.open.*", "knk.gatedoor.close.*", "knk.gate.open.*", "knk.gate.close.*");

    static final List<String> SUBCOMMANDS = List.of("open", "close", "toggle", "info", "list", "repair", "tp",
        "health", "active", "invincible", "capture", "redefine", "help");

    private final GateCommandSupport support;
    private final GateManager gateManager;
    private final GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler;

    public GateDoorCommand(GateCommandSupport support, GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler) {
        this.support = support;
        this.gateManager = support.gateManager;
        this.gateDoorRegionCaptureHandler = gateDoorRegionCaptureHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> executeOpen(sender, subArgs);
            case "close" -> executeClose(sender, subArgs);
            case "toggle" -> executeToggle(sender, subArgs);
            case "info" -> executeInfo(sender, subArgs);
            case "list" -> executeList(sender, subArgs);
            case "repair" -> executeRepair(sender, subArgs);
            case "tp" -> executeTeleport(sender, subArgs);
            case "health" -> executeHealth(sender, subArgs);
            case "active" -> executeToggleActive(sender, subArgs);
            case "invincible" -> executeToggleInvincible(sender, subArgs);
            case "capture" -> executeRegion(sender, subArgs, false);
            case "redefine" -> executeRegion(sender, subArgs, true);
            case "help", "?" -> {
                sendHelp(sender);
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown gate door subcommand: " + args[0]);
                sendHelp(sender);
                yield true;
            }
        };
    }

    void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Door Commands (one door) ━━━");
        sender.sendMessage(ChatColor.GRAY + "<door> = door id or name, <structure> <door>, or 'here'. "
            + "Left out on open/close/toggle/info/repair: the door you look at.");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor open|close|toggle [door]");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor info [door]");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor list [structure]");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor repair [door]");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor tp <door>");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor health <door> <amount>");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor active|invincible <door>");
        sender.sendMessage(ChatColor.GRAY + "/gatedoor capture|redefine <door> [closed|opened]");
        sender.sendMessage(ChatColor.GRAY + "Whole gate (all its doors): /gate help. Long form: /knk gatedoor ...");
    }

    // === open / close / toggle ===

    public boolean executeOpen(CommandSender sender, String[] args) {
        Request request = Request.of(DOOR_ROOT, "open", true, "Usage: /gatedoor open <door name|id> | <structure> <door> | here");
        CachedGateDoor door = support.door(sender, args, request);
        if (door != null) {
            support.withNodes(sender, GateCommandSupport.doorControlNodes(door), () -> open(sender, door));
        }
        return true;
    }

    public boolean executeClose(CommandSender sender, String[] args) {
        Request request = Request.of(DOOR_ROOT, "close", true, "Usage: /gatedoor close <door name|id> | <structure> <door> | here");
        CachedGateDoor door = support.door(sender, args, request);
        if (door != null) {
            support.withNodes(sender, GateCommandSupport.doorControlNodes(door), () -> close(sender, door));
        }
        return true;
    }

    /** Flips the door's target state ({@link GateToggle#doorOpens}): open/opening closes, closed/closing opens. */
    public boolean executeToggle(CommandSender sender, String[] args) {
        Request request = Request.of(DOOR_ROOT, "toggle", true, "Usage: /gatedoor toggle <door name|id> | <structure> <door> | here");
        CachedGateDoor door = support.door(sender, args, request);
        if (door != null) {
            support.withNodes(sender, GateCommandSupport.doorControlNodes(door), () -> {
                if (GateToggle.doorOpens(door.getCurrentState())) {
                    open(sender, door);
                } else {
                    close(sender, door);
                }
            });
        }
        return true;
    }

    private void open(CommandSender sender, CachedGateDoor door) {
        if (!support.mayControlDoor(sender, "open", door)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to open " + doorLabel(door) + ".");
            return;
        }
        if (!door.isEffectivelyActive()) {
            sender.sendMessage(ChatColor.RED + capitalise(doorLabel(door)) + " is not active.");
            return;
        }
        if (door.isEffectivelyDestroyed()) {
            sender.sendMessage(ChatColor.RED + capitalise(doorLabel(door)) + " is destroyed and cannot be opened.");
            return;
        }
        if (gateManager.openGate(door.getId())) {
            gateManager.setAnimationCompletionCallback(door.getId(), state ->
                sender.sendMessage(ChatColor.GREEN + capitalise(doorLabel(door)) + " is now " + state + "."));
            sender.sendMessage(ChatColor.GREEN + "Opening " + doorLabel(door) + "...");
        } else {
            sender.sendMessage(ChatColor.YELLOW + capitalise(doorLabel(door)) + " is already open or opening.");
        }
    }

    private void close(CommandSender sender, CachedGateDoor door) {
        if (!support.mayControlDoor(sender, "close", door)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to close " + doorLabel(door) + ".");
            return;
        }
        if (!door.isEffectivelyActive()) {
            sender.sendMessage(ChatColor.RED + capitalise(doorLabel(door)) + " is not active.");
            return;
        }
        if (gateManager.closeGate(door.getId())) {
            gateManager.setAnimationCompletionCallback(door.getId(), state ->
                sender.sendMessage(ChatColor.GREEN + capitalise(doorLabel(door)) + " is now " + state + "."));
            sender.sendMessage(ChatColor.GREEN + "Closing " + doorLabel(door) + "...");
        } else {
            sender.sendMessage(ChatColor.YELLOW + capitalise(doorLabel(door)) + " is already closed or closing.");
        }
    }

    // === info / list ===

    public boolean executeInfo(CommandSender sender, String[] args) {
        Request request = Request.of(DOOR_ROOT, "info", true, "Usage: /gatedoor info <door name|id> | <structure> <door> | here");
        CachedGateDoor gate = support.door(sender, args, request);
        if (gate == null) {
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Door Info: " + GateCommandSupport.structureName(gate) + " / " + gate.getName() + " ━━━");
        sender.sendMessage(ChatColor.GRAY + "Door ID: " + ChatColor.WHITE + gate.getId()
            + ChatColor.GRAY + "  Gate structure ID: " + ChatColor.WHITE + gate.getGateStructureId());
        sender.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + gate.getGateType());
        String stateLine = GateCommandSupport.formatState(gate.getCurrentState());
        if (gate.isJammed()) {
            stateLine += " " + ChatColor.RED + "(JAMMED)";
        }
        sender.sendMessage(ChatColor.GRAY + "State: " + stateLine);
        sender.sendMessage(ChatColor.GRAY + "Active: " + ChatColor.WHITE + (gate.isEffectivelyActive() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Destroyed: " + ChatColor.WHITE + (gate.isEffectivelyDestroyed() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Health: " + ChatColor.WHITE
            + String.format("%.0f/%.0f", gate.getHealthCurrent(), gate.getHealthMax()));
        sender.sendMessage(ChatColor.GRAY + "Invincible: " + ChatColor.WHITE + (gate.isEffectivelyInvincible() ? "✓" : "✗"));
        sender.sendMessage(ChatColor.GRAY + "Blocks: " + ChatColor.WHITE + gate.getBlocks().size());
        sender.sendMessage(ChatColor.GRAY + "Motion Type: " + ChatColor.WHITE + gate.getMotionType());
        sender.sendMessage(ChatColor.GRAY + "Face Direction: " + ChatColor.WHITE + gate.getFaceDirection());
        return true;
    }

    /** {@code /gatedoor list [structure]}: every loaded door, or one gate's doors. */
    public boolean executeList(CommandSender sender, String[] args) {
        Location senderLoc = sender instanceof Player player ? player.getLocation() : null;
        List<CachedGateDoor> doors;
        if (args.length > 0) {
            Request request = Request.of(DOOR_ROOT, "list", false, "Usage: /gatedoor list [structure]");
            CachedGateStructure structure = support.structure(sender, args, request);
            if (structure == null) {
                return true;
            }
            doors = support.doorsOf(structure);
        } else {
            doors = new ArrayList<>(gateManager.getAllGates().values());
            doors.sort(Comparator.comparingInt(CachedGateDoor::getId));
        }

        if (doors.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No gate doors are loaded. Use /gate reload after confirming API connectivity.");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Doors ━━━");
        for (CachedGateDoor gate : doors) {
            String statusColor = gate.getCurrentState() == AnimationState.OPEN ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
            String distanceStr = sameWorld(senderLoc, gate)
                ? String.format(" (%.0fm)", gate.getAnchorPoint().distance(senderLoc.toVector())) : "";
            String jammedSuffix = gate.isJammed() ? " " + ChatColor.RED + "(JAMMED)" : "";
            sender.sendMessage(ChatColor.AQUA + "#" + gate.getId() + " " + GateCommandSupport.structureName(gate) + " / " + gate.getName()
                + ChatColor.GRAY + " [" + gate.getGateType() + "]"
                + statusColor + " " + gate.getCurrentState() + jammedSuffix + distanceStr);
        }
        return true;
    }

    static boolean sameWorld(Location location, CachedGateDoor door) {
        if (location == null || door.getAnchorPoint() == null) {
            return false;
        }
        String doorWorld = door.getWorldName();
        return doorWorld == null || doorWorld.isBlank() || location.getWorld() == null
            || doorWorld.equals(location.getWorld().getName());
    }

    // === admin ===

    public boolean executeRepair(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Request request = Request.of(DOOR_ROOT, "repair", true, "Usage: /gatedoor repair <door name|id> | <structure> <door> | here");
        CachedGateDoor gate = support.door(sender, args, request);
        if (gate == null) {
            return true;
        }
        support.repair(gate);
        sender.sendMessage(ChatColor.GREEN + "Repaired " + doorLabel(gate) + ". Health: "
            + gate.getHealthCurrent() + "/" + gate.getHealthMax());
        CachedGateStructure structure = gateManager.getStructure(gate.getGateStructureId());
        if (structure != null && Boolean.TRUE.equals(structure.getIsDestroyedOverride())) {
            sender.sendMessage(ChatColor.YELLOW + "Note: the gate's override destroyed=true still applies; clear it with /gate override "
                + structure.getId() + " destroyed clear.");
        }
        return true;
    }

    public boolean executeTeleport(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can teleport.");
            return true;
        }
        Request request = Request.of(DOOR_ROOT, "tp", false, "Usage: /gatedoor tp <door name|id> | <structure> <door> | here");
        CachedGateDoor gate = support.door(sender, args, request);
        if (gate == null) {
            return true;
        }
        support.teleportTo(player, gate);
        sender.sendMessage(ChatColor.GREEN + "Teleported to " + doorLabel(gate) + ".");
        return true;
    }

    public boolean executeHealth(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /gatedoor health <door name|id> | <structure> <door> | here <amount>");
            return true;
        }
        String amountArg = args[args.length - 1];
        double amount;
        try {
            amount = Double.parseDouble(amountArg);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid health value: " + amountArg);
            return true;
        }
        Request request = Request.of(DOOR_ROOT, "health", false, "Usage: /gatedoor health <door> <amount>").withTrailing(amountArg);
        CachedGateDoor gate = support.door(sender, Arrays.copyOf(args, args.length - 1), request);
        if (gate == null) {
            return true;
        }
        gate.setHealthCurrent(Math.max(0, Math.min(amount, gate.getHealthMax())));
        support.persistHealthChange(gate);
        sender.sendMessage(ChatColor.GREEN + "Set health of " + doorLabel(gate) + " to " + gate.getHealthCurrent());
        return true;
    }

    public boolean executeToggleActive(CommandSender sender, String[] args) {
        CachedGateDoor gate = adminDoor(sender, args, "active");
        if (gate != null) {
            gate.setIsActive(!gate.isActive());
            support.persistOperationalSettings(gate);
            gateManager.fireStateChanged(gate.getId()); // R4
            sender.sendMessage(ChatColor.GREEN + capitalise(doorLabel(gate)) + " active: " + gate.isActive());
        }
        return true;
    }

    public boolean executeToggleInvincible(CommandSender sender, String[] args) {
        CachedGateDoor gate = adminDoor(sender, args, "invincible");
        if (gate != null) {
            gate.setIsInvincible(!gate.isInvincible());
            support.persistOperationalSettings(gate);
            sender.sendMessage(ChatColor.GREEN + capitalise(doorLabel(gate)) + " invincible: " + gate.isInvincible());
        }
        return true;
    }

    private CachedGateDoor adminDoor(CommandSender sender, String[] args, String sub) {
        if (!requireAdmin(sender)) {
            return null;
        }
        return support.door(sender, args,
            Request.of(DOOR_ROOT, sub, false, "Usage: /gatedoor " + sub + " <door name|id> | <structure> <door> | here"));
    }

    /**
     * {@code /gatedoor capture|redefine <door> [closed|opened]} (items 6.3/6.4): draws or re-edits
     * a WorldEdit-based region for one of a door's two region slots.
     */
    private boolean executeRegion(CommandSender sender, String[] args, boolean isRedefine) {
        if (!requireAdmin(sender)) {
            return true;
        }
        String sub = isRedefine ? "redefine" : "capture";
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can " + sub + " a gate door region.");
            return true;
        }
        String usage = "Usage: /gatedoor " + sub + " <door name|id> | <structure> <door> | here [closed|opened]";

        boolean isOpenedRegion = false;
        String[] doorArgs = args;
        String slot = null;
        if (args.length > 0) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (last.equals("closed") || last.equals("opened")) {
                isOpenedRegion = last.equals("opened");
                slot = last;
                doorArgs = Arrays.copyOf(args, args.length - 1);
            }
        }
        Request request = Request.of(DOOR_ROOT, sub, false, usage);
        if (slot != null) {
            request = request.withTrailing(slot);
        }
        CachedGateDoor gate = support.door(sender, doorArgs, request);
        if (gate == null) {
            return true;
        }
        if (gateDoorRegionCaptureHandler == null) {
            sender.sendMessage(ChatColor.RED + "Region capture is not available.");
            return true;
        }
        if (isRedefine) {
            gateDoorRegionCaptureHandler.startRedefine(player, gate, isOpenedRegion);
        } else {
            gateDoorRegionCaptureHandler.startCapture(player, gate, isOpenedRegion);
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (support.isDoorAdmin(sender)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
        return false;
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // === tab completion ===

    /** Completion for the arguments after {@code gatedoor}; no lookups beyond the gate cache. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        String current = args[args.length - 1];
        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, current);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub) || "help".equals(sub)) {
            return List.of();
        }
        if ("list".equals(sub)) {
            return args.length == 2 ? filterByPrefix(structureSelectors(), current) : List.of();
        }
        if (args.length == 2) {
            return filterByPrefix(doorSelectors(), current);
        }
        if (("capture".equals(sub) || "redefine".equals(sub))) {
            return filterByPrefix(List.of("closed", "opened"), current);
        }
        if ("health".equals(sub) && args.length == 3) {
            return filterByPrefix(List.of("0", "100", "250", "500"), current);
        }
        return List.of();
    }

    private List<String> doorSelectors() {
        List<String> selectors = new ArrayList<>();
        selectors.add(GateCommandKeywords.HERE);
        gateManager.getAllGates().values().stream()
            .sorted(Comparator.comparingInt(CachedGateDoor::getId))
            .forEach(door -> {
                selectors.add(String.valueOf(door.getId()));
                if (!door.getName().contains(" ")) {
                    selectors.add(door.getName());
                }
            });
        return selectors;
    }

    List<String> structureSelectors() {
        List<String> selectors = new ArrayList<>();
        gateManager.getAllStructures().values().stream()
            .sorted(Comparator.comparingInt(CachedGateStructure::getId))
            .forEach(structure -> {
                selectors.add(String.valueOf(structure.getId()));
                if (!structure.getName().contains(" ")) {
                    selectors.add(structure.getName());
                }
            });
        return selectors;
    }
}
