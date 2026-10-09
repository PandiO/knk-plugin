package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateCommandKeywords;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.DoorCandidate;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.StructureCandidate;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.gates.GateDoorOpenStateMapper;
import net.knightsandkings.knk.paper.gates.GateTargeting;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link GateCommand} ({@code /knk gate}, {@code /gate}: gate structures) and
 * {@link GateDoorCommand} ({@code /knk gatedoor}, {@code /gatedoor}: single doors) share (KNG-77):
 * selector resolution, the implicit targets ({@code here}, KNG-78; look-at, KNG-79), permission
 * checks, labels and persistence.
 *
 * <p>Target resolution for a structure or door argument:
 * <ol>
 *   <li>An explicit id or name (a door also takes {@code <structure> <door>}).</li>
 *   <li>{@code here} (players only): the doors whose region is within {@code gates.here.radius}
 *       blocks. One candidate is used; several get a clickable, distance-sorted choice in chat
 *       whose lines run the same command with the explicit id (so permissions are checked again
 *       on click); none is an error naming the radius. For a structure, several doors of one gate
 *       are one candidate.</li>
 *   <li>Nothing (players only, and only for the commands marked {@link Request#inferable}: open,
 *       close, toggle, info, repair): the door the player looks at, then the {@code here} logic,
 *       then the usage message.</li>
 * </ol>
 * Every reply names the resolved door and gate, so a wrong guess shows straight away.
 */
public class GateCommandSupport {
    /** Root literals under {@code /knk}; also the standalone {@code /gate} and {@code /gatedoor}. */
    public static final String STRUCTURE_ROOT = "gate";
    public static final String DOOR_ROOT = "gatedoor";

    /** Subcommands taking a structure / a door, for "use the other command" hints. */
    static final java.util.Set<String> STRUCTURE_TARGET_SUBS = java.util.Set.of("open", "close", "toggle", "info", "repair", "tp", "override");
    static final java.util.Set<String> DOOR_TARGET_SUBS = java.util.Set.of("open", "close", "toggle", "info", "repair", "tp",
        "health", "active", "invincible", "capture", "redefine");

    public static final String GATE_ADMIN = "knk.gate.admin";
    public static final String GATEDOOR_ADMIN = "knk.gatedoor.admin";

    final GateManager gateManager;
    final GateStructuresApi gateStructuresApi;
    final GateDoorsApi gateDoorsApi;
    final GateTargeting targeting;
    CommandPermissions permissions = CommandPermissions.bukkitOnly();

    public GateCommandSupport(GateManager gateManager, GateStructuresApi gateStructuresApi, GateDoorsApi gateDoorsApi,
                              GateTargeting targeting) {
        this.gateManager = gateManager;
        this.gateStructuresApi = gateStructuresApi;
        this.gateDoorsApi = gateDoorsApi;
        this.targeting = targeting;
    }

    public GateTargeting targeting() {
        return targeting;
    }

    // === Requests and implicit targets ===

    /**
     * One command invocation's shape, for messages and for the clickable choices.
     *
     * @param root      {@link #STRUCTURE_ROOT} or {@link #DOOR_ROOT}
     * @param sub       the subcommand ({@code repair})
     * @param trailing  arguments after the target, re-appended to a clicked choice
     * @param inferable whether an omitted target may be inferred (look-at, then here)
     * @param usage     the usage line
     */
    public record Request(String root, String sub, List<String> trailing, boolean inferable, String usage) {
        public Request {
            trailing = List.copyOf(trailing);
        }

        static Request of(String root, String sub, boolean inferable, String usage) {
            return new Request(root, sub, List.of(), inferable, usage);
        }

        Request withTrailing(String... args) {
            return new Request(root, sub, Arrays.asList(args), inferable, usage);
        }

        /** The explicit-id command a clicked choice runs. */
        String commandFor(int id) {
            StringBuilder command = new StringBuilder("/knk ").append(root).append(' ').append(sub).append(' ').append(id);
            trailing.forEach(arg -> command.append(' ').append(arg));
            return command.toString();
        }
    }

    /**
     * The door {@code selector} names, or null after telling the sender why there is none (not
     * found, no door nearby, or a choice was offered).
     */
    public CachedGateDoor door(CommandSender sender, String[] selector, Request request) {
        if (selector.length == 0) {
            if (!request.inferable() || !(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.YELLOW + request.usage());
                return null;
            }
            Optional<CachedGateDoor> seen = lookedAtDoor(player);
            if (seen.isPresent()) {
                sender.sendMessage(ChatColor.GRAY + "Using the door you're looking at: " + doorLabel(seen.get()) + ".");
                return seen.get();
            }
            CachedGateDoor near = doorHere(player, request, true);
            if (near == null) {
                return null;
            }
            return near;
        }
        if (selector.length == 1 && GateCommandKeywords.isHere(selector[0])) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use 'here'.");
                return null;
            }
            return doorHere(player, request, false);
        }
        CachedGateDoor door = resolveDoor(selector);
        if (door == null) {
            String joined = String.join(" ", selector);
            sender.sendMessage(ChatColor.RED + "Gate door '" + joined + "' not found.");
            CachedGateStructure structure = resolveStructure(joined);
            if (structure != null && STRUCTURE_TARGET_SUBS.contains(request.sub())) {
                sender.sendMessage(ChatColor.GRAY + "'" + joined + "' is a gate structure; for the whole gate use /gate "
                    + request.sub() + " " + joined + ", or name a door: /gatedoor " + request.sub() + " " + structure.getId() + " <door>.");
            } else if (structure != null) {
                sender.sendMessage(ChatColor.GRAY + "'" + joined + "' is a gate structure; name one of its doors: /gatedoor "
                    + request.sub() + " " + structure.getId() + " <door>.");
            }
        }
        return door;
    }

    /** The gate structure {@code selector} names, or null after telling the sender why there is none. */
    public CachedGateStructure structure(CommandSender sender, String[] selector, Request request) {
        if (selector.length == 0) {
            if (!request.inferable() || !(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.YELLOW + request.usage());
                return null;
            }
            Optional<CachedGateDoor> seen = lookedAtDoor(player);
            if (seen.isPresent()) {
                CachedGateStructure structure = gateManager.getStructure(seen.get().getGateStructureId());
                if (structure != null) {
                    sender.sendMessage(ChatColor.GRAY + "Using the gate you're looking at: " + structureLabel(structure) + ".");
                    return structure;
                }
            }
            return structureHere(player, request, true);
        }
        if (selector.length == 1 && GateCommandKeywords.isHere(selector[0])) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use 'here'.");
                return null;
            }
            return structureHere(player, request, false);
        }
        String joined = String.join(" ", selector);
        CachedGateStructure structure = resolveStructure(joined);
        if (structure == null) {
            sender.sendMessage(ChatColor.RED + "Gate structure '" + joined + "' not found.");
            CachedGateDoor door = resolveDoor(selector);
            if (door != null && STRUCTURE_TARGET_SUBS.contains(request.sub())) {
                String forDoor = DOOR_TARGET_SUBS.contains(request.sub())
                    ? "; for just that door use /gatedoor " + request.sub() + " " + door.getId() + ", for" : "; for";
                sender.sendMessage(ChatColor.GRAY + "'" + joined + "' is a gate door (" + doorLabel(door) + ")"
                    + forDoor + " its whole gate /gate " + request.sub() + " " + door.getGateStructureId() + ".");
            }
            return null;
        }
        noteDoorIdNowMeansStructure(sender, selector, structure, request);
        return structure;
    }

    /**
     * Before KNG-77, {@code /knk gate open 7} meant door 7. When a numeric selector is also the id
     * of a door of <em>another</em> gate, say that /gate now takes a structure id.
     */
    private void noteDoorIdNowMeansStructure(CommandSender sender, String[] selector, CachedGateStructure structure, Request request) {
        if (selector.length != 1 || !selector[0].chars().allMatch(Character::isDigit)) {
            return;
        }
        CachedGateDoor door = gateManager.getGate(structure.getId());
        if (door != null && door.getGateStructureId() != structure.getId() && DOOR_TARGET_SUBS.contains(request.sub())) {
            sender.sendMessage(ChatColor.GRAY + "Note: /gate takes a gate structure id (since KNG-77). For "
                + doorLabel(door) + " use /gatedoor " + request.sub() + " " + door.getId() + ".");
        }
    }

    private Optional<CachedGateDoor> lookedAtDoor(Player player) {
        return targeting.lookedAt(player).map(hit -> gateManager.getGate(hit.doorId()));
    }

    private CachedGateDoor doorHere(Player player, Request request, boolean inferred) {
        List<DoorCandidate> candidates = targeting.doorsNear(player);
        if (candidates.isEmpty()) {
            noneNearby(player, request, inferred, "gate door");
            return null;
        }
        if (candidates.size() == 1 || targeting.settings().hereNearest()) {
            CachedGateDoor door = gateManager.getGate(candidates.get(0).doorId());
            if (door != null) {
                player.sendMessage(ChatColor.GRAY + "Using the nearest door: " + doorLabel(door)
                    + String.format(Locale.ROOT, " (%.1f m).", candidates.get(0).distance()));
            } else {
                player.sendMessage(ChatColor.RED + "The nearest gate door (#" + candidates.get(0).doorId()
                    + ") is no longer loaded; try again.");
            }
            return door;
        }
        List<Component> lines = new ArrayList<>();
        for (DoorCandidate candidate : candidates) {
            CachedGateDoor door = gateManager.getGate(candidate.doorId());
            if (door == null) {
                continue;
            }
            lines.add(choice(String.format(Locale.ROOT, "#%d %s of gate '%s' - %.1f m", door.getId(), door.getName(),
                structureName(door), candidate.distance()), request.commandFor(door.getId())));
        }
        sendChoices(player, "Several gate doors are within " + radius() + " blocks. Click the one you mean:", lines);
        return null;
    }

    private CachedGateStructure structureHere(Player player, Request request, boolean inferred) {
        List<StructureCandidate> candidates = targeting.structuresNear(player);
        if (candidates.isEmpty()) {
            noneNearby(player, request, inferred, "gate");
            return null;
        }
        if (candidates.size() == 1 || targeting.settings().hereNearest()) {
            CachedGateStructure structure = gateManager.getStructure(candidates.get(0).structureId());
            if (structure != null) {
                player.sendMessage(ChatColor.GRAY + "Using the nearest gate: " + structureLabel(structure)
                    + String.format(Locale.ROOT, " (%.1f m).", candidates.get(0).distance()));
            } else {
                player.sendMessage(ChatColor.RED + "The nearest gate door belongs to gate structure #"
                    + candidates.get(0).structureId() + ", which isn't loaded.");
            }
            return structure;
        }
        List<Component> lines = new ArrayList<>();
        for (StructureCandidate candidate : candidates) {
            CachedGateStructure structure = gateManager.getStructure(candidate.structureId());
            String name = structure == null ? "?" : structure.getName();
            int doors = candidate.doorIds().size();
            lines.add(choice(String.format(Locale.ROOT, "#%d %s (%d door%s in range) - %.1f m", candidate.structureId(), name,
                doors, doors == 1 ? "" : "s", candidate.distance()), request.commandFor(candidate.structureId())));
        }
        sendChoices(player, "Several gates are within " + radius() + " blocks. Click the one you mean:", lines);
        return null;
    }

    private void noneNearby(Player player, Request request, boolean inferred, String what) {
        if (inferred) {
            player.sendMessage(ChatColor.YELLOW + request.usage());
            player.sendMessage(ChatColor.GRAY + "(No " + what + " in sight or within " + radius()
                + " blocks to use instead.)");
        } else {
            player.sendMessage(ChatColor.RED + "No " + what + " within " + radius() + " blocks of you.");
        }
    }

    private String radius() {
        double radius = targeting.settings().hereRadius();
        return radius == Math.rint(radius) ? String.valueOf((long) radius) : String.valueOf(radius);
    }

    private static Component choice(String label, String command) {
        return Component.text(" ▸ ", NamedTextColor.GRAY)
            .append(Component.text(label, NamedTextColor.AQUA))
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text("Run " + command, NamedTextColor.GRAY)));
    }

    private static void sendChoices(Player player, String header, List<Component> lines) {
        if (lines.isEmpty()) {
            player.sendMessage(ChatColor.RED + "The gates near you are no longer loaded; try again.");
            return;
        }
        player.sendMessage(Component.text(header, NamedTextColor.GOLD));
        lines.forEach(player::sendMessage);
    }

    // === Explicit selectors ===

    /**
     * Resolves a door selector. Tries, in order: the joined args as a door id or name; then, with
     * 2+ args, args[0] as a structure id/name and the rest as a door id/name within it (decision
     * 5.0-D's {@code <gateStructure> <gateDoor>}).
     */
    public CachedGateDoor resolveDoor(String[] args) {
        if (args.length == 0) {
            return null;
        }
        String joined = String.join(" ", args);
        CachedGateDoor direct = findDoorByIdOrName(joined);
        if (direct != null) {
            return direct;
        }
        if (args.length >= 2) {
            CachedGateStructure structure = resolveStructure(args[0]);
            if (structure != null) {
                return findDoorInStructure(structure.getId(), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
        }
        return null;
    }

    public CachedGateStructure resolveStructure(String nameOrId) {
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

    private CachedGateDoor findDoorByIdOrName(String nameOrId) {
        try {
            return gateManager.getGate(Integer.parseInt(nameOrId));
        } catch (NumberFormatException ignored) {
            return gateManager.getGateByName(nameOrId);
        }
    }

    /** A structure's doors, by id. */
    public List<CachedGateDoor> doorsOf(CachedGateStructure structure) {
        List<CachedGateDoor> doors = new ArrayList<>(gateManager.getDoorsForStructure(structure.getId()));
        doors.sort(Comparator.comparingInt(CachedGateDoor::getId));
        return doors;
    }

    // === Permissions ===

    /** Fills the in-house permission cache for {@code nodes}, then runs {@code then} (on the main thread). */
    public void withNodes(CommandSender sender, Collection<String> nodes, Runnable then) {
        permissions.warm(sender, nodes, then);
    }

    public boolean has(CommandSender sender, String node) {
        return permissions.has(sender, node);
    }

    /** {@code knk.gate.<action>.<structureId>}, {@code knk.gate.<action>.*} (action: open / close) or {@code knk.gate.admin}. */
    public boolean mayControlStructure(CommandSender sender, String action, int structureId) {
        return has(sender, "knk.gate." + action + "." + structureId) || has(sender, "knk.gate." + action + ".*")
            || isStructureAdmin(sender);
    }

    /**
     * {@code knk.gatedoor.<action>.<doorId>} or {@code knk.gatedoor.<action>.*}, door admin, or the
     * right to do the same to the door's whole gate ({@link #mayControlStructure}). Admin nodes are
     * checked here, not only through plugin.yml children, because in-house grants don't see those.
     */
    public boolean mayControlDoor(CommandSender sender, String action, CachedGateDoor door) {
        return has(sender, "knk.gatedoor." + action + "." + door.getId()) || has(sender, "knk.gatedoor." + action + ".*")
            || isDoorAdmin(sender) || mayControlStructure(sender, action, door.getGateStructureId());
    }

    /** The per-id nodes {@link #mayControlDoor} may check, to warm before checking. */
    public static List<String> doorControlNodes(CachedGateDoor door) {
        return List.of("knk.gatedoor.open." + door.getId(), "knk.gatedoor.close." + door.getId(),
            "knk.gate.open." + door.getGateStructureId(), "knk.gate.close." + door.getGateStructureId());
    }

    public static List<String> structureControlNodes(CachedGateStructure structure) {
        return List.of("knk.gate.open." + structure.getId(), "knk.gate.close." + structure.getId());
    }

    /** Door-level admin: {@code knk.gatedoor.admin}, or {@code knk.gate.admin} (which covers every door). */
    public boolean isDoorAdmin(CommandSender sender) {
        return has(sender, GATEDOOR_ADMIN) || has(sender, GATE_ADMIN);
    }

    public boolean isStructureAdmin(CommandSender sender) {
        return has(sender, GATE_ADMIN);
    }

    // === Labels ===

    public static String doorLabel(CachedGateDoor door) {
        return "door '" + door.getName() + "' (#" + door.getId() + ") of gate '" + structureName(door) + "'";
    }

    public static String structureLabel(CachedGateStructure structure) {
        return "gate '" + structure.getName() + "' (#" + structure.getId() + ")";
    }

    static String structureName(CachedGateDoor door) {
        String name = door.getStructureName();
        return name == null || name.isBlank() ? "#" + door.getGateStructureId() : name;
    }

    public static String formatState(AnimationState state) {
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

    // === Shared actions ===

    /** Full health, not destroyed, persisted; navigation re-checks routes through it (R4). */
    public void repair(CachedGateDoor door) {
        door.setHealthCurrent(door.getHealthMax());
        door.setIsDestroyed(false);
        persistHealthChange(door);
        persistState(door);
        gateManager.fireStateChanged(door.getId());
    }

    /**
     * Teleports the player onto the door's anchor, in the door's own world when it is loaded.
     *
     * @return false (and nothing happens) when the door has no anchor point
     */
    public boolean teleportTo(Player player, CachedGateDoor door) {
        if (door.getAnchorPoint() == null) {
            player.sendMessage(ChatColor.RED + capitalise(doorLabel(door)) + " has no anchor point to teleport to.");
            return false;
        }
        World world = null;
        String worldName = door.getWorldName();
        if (worldName != null && !worldName.isBlank() && player.getServer() != null) {
            world = player.getServer().getWorld(worldName);
        }
        if (world == null) {
            world = player.getWorld();
        }
        Vector anchor = door.getAnchorPoint();
        player.teleport(new Location(world, anchor.getX() + 0.5, anchor.getY() + 1, anchor.getZ() + 0.5));
        return true;
    }

    static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public void persistOperationalSettings(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            return;
        }
        gateDoorsApi.updateOperationalSettings(gate.getId(), gate.isActive(), gate.isInvincible())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    public void persistHealthChange(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            return;
        }
        gateDoorsApi.updateHealth(gate.getId(), gate.getHealthCurrent())
            .exceptionally(error -> {
                gateManager.reloadGates();
                return null;
            });
    }

    public void persistState(CachedGateDoor gate) {
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

    /** Lower-cased prefix filter for tab completion. */
    static List<String> filterByPrefix(Collection<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(Objects::nonNull)
            .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
            .distinct()
            .toList();
    }
}
