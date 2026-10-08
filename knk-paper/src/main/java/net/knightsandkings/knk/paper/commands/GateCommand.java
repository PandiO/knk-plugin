package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.dto.GateStructureOverridesUpdateDto;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.gates.GateCommandKeywords;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateToggle;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.commands.GateCommandSupport.Request;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.gates.GateTargeting;
import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
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
import java.util.stream.Stream;

import static net.knightsandkings.knk.paper.commands.GateCommandSupport.STRUCTURE_ROOT;
import static net.knightsandkings.knk.paper.commands.GateCommandSupport.doorLabel;
import static net.knightsandkings.knk.paper.commands.GateCommandSupport.filterByPrefix;
import static net.knightsandkings.knk.paper.commands.GateCommandSupport.structureLabel;

/**
 * The structure layer of the gate commands (KNG-77): {@code /knk gate <sub> <structure>}, alias
 * {@code /gate}. A "gate" is a GateStructure with one or more independently-animating GateDoors
 * (item 5); every subcommand here acts on the structure and <em>all</em> of its doors. One door at
 * a time is {@link GateDoorCommand} ({@code /knk gatedoor}, {@code /gatedoor}) - sibling roots
 * rather than a nested {@code door} literal, so {@code /gate door} can't clash with a structure
 * named "door" and each root completes exactly one kind of entity.
 *
 * <p>A structure is named by its id, its name, {@code here} (KNG-78), or - for open, close,
 * toggle, info and repair - nothing, which uses the gate the player looks at (KNG-79); see
 * {@link GateCommandSupport}.
 *
 * <p>Permissions: open/close/toggle need {@code knk.gate.<open|close>.<structureId>} or
 * {@code knk.gate.<open|close>.*}; repair, tp, override and reload need {@code knk.gate.admin};
 * info, list and passthrough are open to everyone.
 *
 * <p>Deprecated, for one release: {@code /knk gate admin <action> ...} and
 * {@code /knk gate door capture|redefine ...} still work - they meant one door, so they run the
 * door-layer command (or the structure one for reload/override) and say which to use instead.
 */
public class GateCommand implements CommandExecutor {
    /** The wildcard nodes /knk gate and /knk gatedoor check from the cache; warmed before they run. */
    public static final List<String> CHECKED_NODES = Stream.concat(
        Stream.of(GateCommandSupport.GATE_ADMIN, "knk.gate.open.*", "knk.gate.close.*"),
        GateDoorCommand.CHECKED_NODES.stream()).distinct().toList();

    static final List<String> SUBCOMMANDS = List.of("open", "close", "toggle", "info", "list", "repair", "tp",
        "override", "reload", "passthrough", "help");
    static final List<String> OVERRIDE_FIELDS = List.of("active", "destroyed", "invincible", "canrespawn", "openedstate");

    private final GateManager gateManager;
    private final GateStructuresApi gateStructuresApi;
    private final UserManager userManager;
    private final UsersCommandApi usersCommandApi;
    private final DistrictGateLoader districtGateLoader;
    private final GateCommandSupport support;
    private final GateDoorCommand doorCommand;

    public GateCommand(GateManager gateManager, GateStructuresApi gateStructuresApi, GateDoorsApi gateDoorsApi,
                       UserManager userManager, UsersCommandApi usersCommandApi,
                       DistrictGateLoader districtGateLoader,
                       GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler) {
        this.gateManager = gateManager;
        this.gateStructuresApi = gateStructuresApi;
        this.userManager = userManager;
        this.usersCommandApi = usersCommandApi;
        this.districtGateLoader = districtGateLoader;
        this.support = new GateCommandSupport(gateManager, gateStructuresApi, gateDoorsApi, new GateTargeting(gateManager));
        this.doorCommand = new GateDoorCommand(support, gateDoorRegionCaptureHandler);
    }

    /** The door layer ({@code /knk gatedoor}), sharing this command's permissions and targeting. */
    public GateDoorCommand doorCommand() {
        return doorCommand;
    }

    /** {@code gates.here.*} / {@code gates.lookat.*} from config.yml (KNG-78/79). */
    public void setTargetingSettings(GateTargeting.Settings settings) {
        support.targeting().setSettings(settings);
    }

    /**
     * Checks knk.gate.* / knk.gatedoor.* through KnkPermissible as well as Bukkit (KNG-24; plain
     * sender.hasPermission refused in-house grants). Bukkit-only until set.
     */
    public void setPermissions(CommandPermissions permissions) {
        support.permissions = java.util.Objects.requireNonNull(permissions, "permissions must not be null");
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
            case "override" -> executeOverride(sender, subArgs);
            case "reload" -> executeReload(sender, subArgs);
            case "passthrough" -> executePassThrough(sender, subArgs);
            case "admin" -> executeDeprecatedAdmin(sender, subArgs);
            case "door" -> executeDeprecatedDoor(sender, subArgs);
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
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Commands (whole gate, all its doors) ━━━");
        sender.sendMessage(ChatColor.GRAY + "<structure> = gate structure id or name, or 'here'. "
            + "Left out on open/close/toggle/info/repair: the gate you look at.");
        sender.sendMessage(ChatColor.GRAY + "/gate open|close|toggle [structure]");
        sender.sendMessage(ChatColor.GRAY + "/gate info [structure]");
        sender.sendMessage(ChatColor.GRAY + "/gate list");
        sender.sendMessage(ChatColor.GRAY + "/gate repair [structure]");
        sender.sendMessage(ChatColor.GRAY + "/gate tp <structure>");
        sender.sendMessage(ChatColor.GRAY + "/gate override <structure> <field> <value|clear>");
        sender.sendMessage(ChatColor.GRAY + "  fields: active, destroyed, invincible, canrespawn, openedstate");
        sender.sendMessage(ChatColor.GRAY + "/gate reload [district <id>]");
        sender.sendMessage(ChatColor.GRAY + "/gate passthrough <default|instant|teleport>");
        sender.sendMessage(ChatColor.GRAY + "One door: /gatedoor help. Long form: /knk gate ...");
    }

    // === open / close / toggle ===

    public boolean executeOpen(CommandSender sender, String[] args) {
        CachedGateStructure structure = support.structure(sender, args,
            Request.of(STRUCTURE_ROOT, "open", true, "Usage: /gate open <structure name|id> | here"));
        if (structure != null) {
            support.withNodes(sender, GateCommandSupport.structureControlNodes(structure),
                () -> whenLoaded(sender, structure, s -> openAll(sender, s, "Opening")));
        }
        return true;
    }

    public boolean executeClose(CommandSender sender, String[] args) {
        CachedGateStructure structure = support.structure(sender, args,
            Request.of(STRUCTURE_ROOT, "close", true, "Usage: /gate close <structure name|id> | here"));
        if (structure != null) {
            support.withNodes(sender, GateCommandSupport.structureControlNodes(structure),
                () -> whenLoaded(sender, structure, s -> closeAll(sender, s, "Closing")));
        }
        return true;
    }

    /**
     * {@link GateToggle#structureOpens}: if any active door is open or opening, close them all;
     * otherwise open them all.
     */
    public boolean executeToggle(CommandSender sender, String[] args) {
        CachedGateStructure structure = support.structure(sender, args,
            Request.of(STRUCTURE_ROOT, "toggle", true, "Usage: /gate toggle <structure name|id> | here"));
        if (structure == null) {
            return true;
        }
        support.withNodes(sender, GateCommandSupport.structureControlNodes(structure), () -> whenLoaded(sender, structure, current -> {
            List<AnimationState> states = support.doorsOf(current).stream()
                .filter(CachedGateDoor::isEffectivelyActive)
                .map(CachedGateDoor::getCurrentState)
                .toList();
            if (GateToggle.structureOpens(states)) {
                openAll(sender, current, "Toggling: opening");
            } else {
                closeAll(sender, current, "Toggling: closing");
            }
        }));
        return true;
    }

    /** Acts on the structure as cached now - a reload may have replaced it during the permission warm-up. */
    private void whenLoaded(CommandSender sender, CachedGateStructure resolved, java.util.function.Consumer<CachedGateStructure> action) {
        CachedGateStructure current = gateManager.getStructure(resolved.getId());
        if (current == null) {
            sender.sendMessage(ChatColor.RED + "Gate " + quoted(resolved) + " is no longer loaded.");
            return;
        }
        action.accept(current);
    }

    private void openAll(CommandSender sender, CachedGateStructure structure, String verb) {
        if (!support.mayControlStructure(sender, "open", structure.getId())) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to open " + structureLabel(structure) + ".");
            return;
        }
        List<CachedGateDoor> doors = support.doorsOf(structure);
        if (doors.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Gate " + quoted(structure) + " has no loaded doors.");
            return;
        }
        int started = 0;
        int already = 0;
        List<String> skipped = new ArrayList<>();
        for (CachedGateDoor door : doors) {
            if (!door.isEffectivelyActive()) {
                skipped.add(door.getName() + " (not active)");
            } else if (door.isEffectivelyDestroyed()) {
                skipped.add(door.getName() + " (destroyed)");
            } else if (gateManager.openGate(door.getId())) {
                started++;
                notifyWhenDone(sender, door);
            } else {
                already++;
            }
        }
        report(sender, structure, verb, started, already, "already open", skipped);
    }

    private void closeAll(CommandSender sender, CachedGateStructure structure, String verb) {
        if (!support.mayControlStructure(sender, "close", structure.getId())) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to close " + structureLabel(structure) + ".");
            return;
        }
        List<CachedGateDoor> doors = support.doorsOf(structure);
        if (doors.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Gate " + quoted(structure) + " has no loaded doors.");
            return;
        }
        int started = 0;
        int already = 0;
        List<String> skipped = new ArrayList<>();
        for (CachedGateDoor door : doors) {
            if (!door.isEffectivelyActive()) {
                skipped.add(door.getName() + " (not active)");
            } else if (gateManager.closeGate(door.getId())) {
                started++;
                notifyWhenDone(sender, door);
            } else {
                already++;
            }
        }
        report(sender, structure, verb, started, already, "already closed", skipped);
    }

    private void notifyWhenDone(CommandSender sender, CachedGateDoor door) {
        gateManager.setAnimationCompletionCallback(door.getId(), state ->
            sender.sendMessage(ChatColor.GREEN + "Door '" + door.getName() + "' of gate '"
                + GateCommandSupport.structureName(door) + "' is now " + state + "."));
    }

    private static void report(CommandSender sender, CachedGateStructure structure, String verb,
                               int started, int already, String alreadyText, List<String> skipped) {
        StringBuilder line = new StringBuilder();
        line.append(verb).append(' ').append(structureLabel(structure)).append(": ")
            .append(started).append(started == 1 ? " door" : " doors").append(" moving");
        if (already > 0) {
            line.append(", ").append(already).append(' ').append(alreadyText);
        }
        ChatColor color = started > 0 ? ChatColor.GREEN : ChatColor.YELLOW;
        sender.sendMessage(color + line.toString() + ".");
        if (!skipped.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Skipped: " + String.join(", ", skipped) + ".");
        }
    }

    private static String quoted(CachedGateStructure structure) {
        return "'" + structure.getName() + "' (#" + structure.getId() + ")";
    }

    // === info / list ===

    public boolean executeInfo(CommandSender sender, String[] args) {
        CachedGateStructure structure = support.structure(sender, args,
            Request.of(STRUCTURE_ROOT, "info", true, "Usage: /gate info <structure name|id> | here"));
        if (structure == null) {
            return true;
        }
        List<CachedGateDoor> doors = support.doorsOf(structure);
        sender.sendMessage(ChatColor.GOLD + "━━━ Gate Info: " + structure.getName() + " ━━━");
        sender.sendMessage(ChatColor.GRAY + "Gate structure ID: " + ChatColor.WHITE + structure.getId()
            + ChatColor.GRAY + "  Doors: " + ChatColor.WHITE + doors.size());
        if (structure.isSiegeObjective()) {
            sender.sendMessage(ChatColor.GRAY + "Siege objective: " + ChatColor.WHITE + "✓");
        }
        List<String> overrides = new ArrayList<>();
        addOverride(overrides, "active", structure.getIsActiveOverride());
        addOverride(overrides, "destroyed", structure.getIsDestroyedOverride());
        addOverride(overrides, "invincible", structure.getIsInvincibleOverride());
        addOverride(overrides, "canrespawn", structure.getCanRespawnOverride());
        addOverride(overrides, "openedstate", structure.getOpenedStateOverride());
        sender.sendMessage(ChatColor.GRAY + "Overrides: " + ChatColor.WHITE + (overrides.isEmpty() ? "none" : String.join(", ", overrides)));
        for (CachedGateDoor door : doors) {
            String jammed = door.isJammed() ? " " + ChatColor.RED + "(JAMMED)" : "";
            String inactive = door.isEffectivelyActive() ? "" : ChatColor.DARK_GRAY + " inactive";
            String destroyed = door.isEffectivelyDestroyed() ? ChatColor.RED + " destroyed" : "";
            sender.sendMessage(ChatColor.AQUA + " #" + door.getId() + " " + door.getName() + " "
                + GateCommandSupport.formatState(door.getCurrentState()) + jammed + inactive + destroyed
                + ChatColor.GRAY + String.format(" %.0f/%.0f HP", door.getHealthCurrent(), door.getHealthMax()));
        }
        sender.sendMessage(ChatColor.GRAY + "Door details: /gatedoor info <door>");
        return true;
    }

    private static void addOverride(List<String> overrides, String field, Object value) {
        if (value != null) {
            overrides.add(field + "=" + value);
        }
    }

    /** {@code /gate list}: every loaded gate structure with its doors' states. */
    public boolean executeList(CommandSender sender, String[] args) {
        Location senderLoc = sender instanceof Player player ? player.getLocation() : null;
        List<CachedGateStructure> structures = new ArrayList<>(gateManager.getAllStructures().values());
        structures.sort(Comparator.comparingInt(CachedGateStructure::getId));

        if (structures.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No gates are loaded. Use /gate reload after confirming API connectivity.");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "━━━ Gates ━━━");
        for (CachedGateStructure structure : structures) {
            List<CachedGateDoor> doors = support.doorsOf(structure);
            long open = doors.stream().filter(door -> GateToggle.isOpenOrOpening(door.getCurrentState())).count();
            String states = doors.isEmpty() ? ChatColor.DARK_GRAY + "no doors loaded"
                : ChatColor.GREEN.toString() + open + " open" + ChatColor.GRAY + "/" + ChatColor.RED + (doors.size() - open) + " closed";
            String distance = "";
            if (senderLoc != null) {
                distance = doors.stream()
                    .filter(door -> GateDoorCommand.sameWorld(senderLoc, door))
                    .mapToDouble(door -> door.getAnchorPoint().distance(senderLoc.toVector()))
                    .min()
                    .stream().mapToObj(d -> ChatColor.GRAY + String.format(" (%.0fm)", d))
                    .findFirst().orElse("");
            }
            sender.sendMessage(ChatColor.AQUA + "#" + structure.getId() + " " + structure.getName()
                + ChatColor.GRAY + " [" + doors.size() + (doors.size() == 1 ? " door] " : " doors] ") + states + distance);
        }
        sender.sendMessage(ChatColor.GRAY + "Doors: /gatedoor list [structure]");
        return true;
    }

    // === admin ===

    /** Repairs every door of the structure (full health, not destroyed). */
    public boolean executeRepair(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        CachedGateStructure structure = support.structure(sender, args,
            Request.of(STRUCTURE_ROOT, "repair", true, "Usage: /gate repair <structure name|id> | here"));
        if (structure == null) {
            return true;
        }
        List<CachedGateDoor> doors = support.doorsOf(structure);
        if (doors.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Gate " + quoted(structure) + " has no loaded doors.");
            return true;
        }
        doors.forEach(support::repair);
        sender.sendMessage(ChatColor.GREEN + "Repaired " + structureLabel(structure) + ": " + doors.size()
            + (doors.size() == 1 ? " door" : " doors") + " back to full health.");
        if (Boolean.TRUE.equals(structure.getIsDestroyedOverride())) {
            sender.sendMessage(ChatColor.YELLOW + "Note: the override destroyed=true still applies; clear it with /gate override "
                + structure.getId() + " destroyed clear.");
        }
        return true;
    }

    /** Teleports to the structure's first door (lowest id). */
    public boolean executeTeleport(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can teleport.");
            return true;
        }
        CachedGateStructure structure = support.structure(sender, args,
            Request.of(STRUCTURE_ROOT, "tp", false, "Usage: /gate tp <structure name|id> | here"));
        if (structure == null) {
            return true;
        }
        List<CachedGateDoor> doors = support.doorsOf(structure);
        if (doors.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Gate " + quoted(structure) + " has no loaded doors to teleport to.");
            return true;
        }
        if (support.teleportTo(player, doors.get(0))) {
            sender.sendMessage(ChatColor.GREEN + "Teleported to " + structureLabel(structure) + " (" + doorLabel(doors.get(0)) + ").");
        }
        return true;
    }

    /**
     * {@code /gate reload [district <id>]}. Bare "reload" does a full reload; "reload district
     * <id>" force-refreshes one district's gates via DistrictGateLoader (a district's gates already
     * load the first time a player enters it; this forces a refresh after editing them in the web app).
     */
    public boolean executeReload(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length >= 2 && "district".equalsIgnoreCase(args[0])) {
            return reloadDistrict(sender, args[1]);
        }
        sender.sendMessage(ChatColor.YELLOW + "Reloading gates from API...");
        gateManager.reloadGates().thenRun(() -> {
            int gateCount = gateManager.getAllGates().size();
            sender.sendMessage(ChatColor.GREEN + "Loaded " + gateCount + " gate doors from API.");
        }).exceptionally(ex -> {
            sender.sendMessage(ChatColor.RED + "Failed to reload gates: " + ex.getMessage());
            return null;
        });
        return true;
    }

    private boolean reloadDistrict(CommandSender sender, String districtIdArg) {
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
     * {@code /gate override <structure> <field> <value|clear>} - decision 5.0-B's structure-level
     * cascading override: sets/clears one of the nullable override columns on GateStructure so every
     * door's effective value reflects it immediately (see CachedGateDoor.isEffectivelyXxx()).
     * Persists via PATCH .../overrides, and mirrors onto the in-memory CachedGateStructure at once.
     *
     * <p>Covers active, destroyed, invincible, canrespawn, openedstate - the fields the backlog's
     * admin force-state and Siege capture-destroys-all-doors use cases need. The other
     * cascade-overridable fields (pass-through/display) are supported by the model and API but not
     * exposed here yet.
     */
    public boolean executeOverride(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /gate override <structure name|id> | here <field> <value|clear>");
            sender.sendMessage(ChatColor.YELLOW + "Fields: active, destroyed, invincible, canrespawn, openedstate");
            return true;
        }

        String field = args[args.length - 2].toLowerCase(Locale.ROOT);
        String valueArg = args[args.length - 1];
        CachedGateStructure structure = support.structure(sender, Arrays.copyOf(args, args.length - 2),
            Request.of(STRUCTURE_ROOT, "override", false, "Usage: /gate override <structure> <field> <value|clear>")
                .withTrailing(args[args.length - 2], valueArg));
        if (structure == null) {
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
                String value = clear ? null : valueArg.toUpperCase(Locale.ROOT);
                if (!clear && !isValidOpenedState(value)) {
                    sender.sendMessage(ChatColor.RED + "Value for 'openedstate' must be CLOSED, OPEN, or clear.");
                    return true;
                }
                request.setClearOpenedStateOverride(clear);
                request.setOpenedStateOverride(value);
                structure.setOpenedStateOverride(value);
                // Give the override real, immediate effect by driving each door through the normal
                // state machine (which already resolves effective active/destroyed).
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
            ? ChatColor.GREEN + "Cleared " + field + " override on " + structureLabel(structure) + "."
            : ChatColor.GREEN + "Set " + field + " override on " + structureLabel(structure) + " to " + valueArg + ".");
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

    private boolean requireAdmin(CommandSender sender) {
        if (support.isStructureAdmin(sender)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
        return false;
    }

    // === passthrough ===

    /**
     * {@code /gate passthrough <default|instant|teleport>}: sets the sender's own preferred gate
     * pass-through method, updating the in-memory cache immediately and persisting asynchronously.
     */
    public boolean executePassThrough(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can set a pass-through method.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /gate passthrough <default|instant|teleport>");
            return true;
        }
        GatePassThroughMethod method = switch (args[0].toLowerCase(Locale.ROOT)) {
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

    // === deprecated forms (one release) ===

    /**
     * {@code /knk gate admin <action> ...} - before KNG-77 these addressed one door. reload and
     * override were already structure-wide and are now plain {@code /gate reload|override}.
     */
    private boolean executeDeprecatedAdmin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "'/knk gate admin' is gone: use /gate help (whole gate) or /gatedoor help (one door).");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (action) {
            case "reload", "override" -> {
                deprecated(sender, "/knk gate admin " + action, "/gate " + action);
                return onCommand(sender, null, STRUCTURE_ROOT, prepend(action, rest));
            }
            case "health", "repair", "tp", "active", "invincible" -> {
                deprecated(sender, "/knk gate admin " + action, "/gatedoor " + action + " (one door) or /gate " + action + " (whole gate)");
                return doorCommand.onCommand(sender, null, GateCommandSupport.DOOR_ROOT, prepend(action, rest));
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown admin gate action: " + args[0] + ". See /gate help and /gatedoor help.");
                return true;
            }
        }
    }

    /** {@code /knk gate door capture|redefine ...} - now {@code /gatedoor capture|redefine ...}. */
    private boolean executeDeprecatedDoor(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "" : " " + args[0].toLowerCase(Locale.ROOT);
        deprecated(sender, "/knk gate door" + action, "/gatedoor" + action);
        return doorCommand.onCommand(sender, null, GateCommandSupport.DOOR_ROOT, args);
    }

    private static void deprecated(CommandSender sender, String old, String replacement) {
        sender.sendMessage(ChatColor.GOLD + old.trim() + " is deprecated; use " + replacement + ".");
    }

    private static String[] prepend(String first, String[] rest) {
        String[] args = new String[rest.length + 1];
        args[0] = first;
        System.arraycopy(rest, 0, args, 1, rest.length);
        return args;
    }

    // === tab completion ===

    /** Completion for the arguments after {@code gate}; no lookups beyond the gate cache. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        String current = args[args.length - 1];
        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, current);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open", "close", "toggle", "info", "repair", "tp" -> {
                return args.length == 2 ? filterByPrefix(structureSelectors(), current) : List.of();
            }
            case "override" -> {
                if (args.length == 2) {
                    return filterByPrefix(structureSelectors(), current);
                }
                if (args.length == 3) {
                    return filterByPrefix(OVERRIDE_FIELDS, current);
                }
                if (args.length == 4) {
                    return "openedstate".equalsIgnoreCase(args[2])
                        ? filterByPrefix(List.of("OPEN", "CLOSED", "clear"), current)
                        : filterByPrefix(List.of("true", "false", "clear"), current);
                }
                return List.of();
            }
            case "reload" -> {
                return args.length == 2 ? filterByPrefix(List.of("district"), current) : List.of();
            }
            case "passthrough" -> {
                return args.length == 2 ? filterByPrefix(List.of("default", "instant", "teleport"), current) : List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private List<String> structureSelectors() {
        List<String> selectors = new ArrayList<>();
        selectors.add(GateCommandKeywords.HERE);
        selectors.addAll(doorCommand.structureSelectors());
        return selectors;
    }
}
