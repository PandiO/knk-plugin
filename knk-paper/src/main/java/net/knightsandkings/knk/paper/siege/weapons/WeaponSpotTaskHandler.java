package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.siege.weapons.SpotDraft;
import net.knightsandkings.knk.core.siege.weapons.SpotFacing;
import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;
import net.knightsandkings.knk.core.siege.weapons.SpotSizeClass;
import net.knightsandkings.knk.core.siege.weapons.SpotTaskOutput;
import net.knightsandkings.knk.paper.tasks.ChatTaskInput;
import net.knightsandkings.knk.paper.tasks.IWorldTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskApiErrors;
import net.knightsandkings.knk.paper.tasks.WorldTaskLifecycle;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * The {@code SiegeWeaponSpot} world task (SIEGE_WEAPONS § 4, W4, W5, W44, W70; A02-A03; PandiO/knk-workspace#799). The
 * weapon spot form in the web app starts it ("Send to Minecraft" on its bounds); an admin claims it in game with
 * {@code /knk task-claim <code>}, selects the spot with WorldEdit (or names a WorldGuard region) and works in chat:
 * <ol>
 *   <li>{@code save}: reads the WorldEdit selection; {@code region <id>}: reads a WorldGuard region (bounding box). The
 *       facing is pre-filled from where the admin looks (rounded to north, east, south or west) unless they set one;
 *       the size class is worked out as width across × length along the facing × height; a selection smaller than
 *       the small class is refused; a floor that isn't solid or a volume that isn't clear is a warning.</li>
 *   <li>{@code facing <north|east|south|west>}: overrides the facing and works the size class out again.</li>
 *   <li>{@code confirm}: completes the world task with bounds, facing, size class, region id and warnings
 *       ({@link SpotTaskOutput}). The web form spreads that over its fields; the spot is saved by the form, not
 *       here.</li>
 *   <li>{@code cancel} hands the task back (its task code works again), {@code discard} cancels it for good.</li>
 * </ol>
 * Registered by task type only, and only while {@code siege.weapons.enabled} is on ({@link SiegeWeaponsModule}).
 */
public class WeaponSpotTaskHandler implements IWorldTaskHandler, ChatTaskInput {

    private static final Logger LOGGER = Logger.getLogger(WeaponSpotTaskHandler.class.getName());
    static final String PREFIX = ChatColor.GOLD + "[Weapon spot] ";

    /** One admin's open task. {@code facingOverride} is set by 'facing' before the first capture. */
    private static final class Session {
        final int taskId;
        SpotFacing facingOverride;
        SpotDraft draft;
        String worldName;
        boolean completing;

        Session(int taskId) {
            this.taskId = taskId;
        }
    }

    private final WorldTasksApi worldTasksApi;
    private final SpotSelections selections;
    private final Function<World, SpotSiteCheck.BlockProbe> probes;
    private final Executor mainThread;
    private final LongSupplier clock;
    // Read from the async chat thread (isHandling), written on the main thread.
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile WorldTaskLifecycle lifecycle = WorldTaskLifecycle.NONE;

    public WeaponSpotTaskHandler(WorldTasksApi worldTasksApi, SpotSelections selections,
                                 Function<World, SpotSiteCheck.BlockProbe> probes, Executor mainThread, LongSupplier clock) {
        this.worldTasksApi = worldTasksApi;
        this.selections = selections;
        this.probes = probes;
        this.mainThread = mainThread;
        this.clock = clock;
    }

    @Override
    public String getFieldName() {
        return SpotTaskOutput.TASK_TYPE;
    }

    @Override
    public void startTask(Player player, int taskId, String inputJson) {
        sessions.put(player.getUniqueId(), new Session(taskId));
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Weapon spot task #" + taskId + ".");
        player.sendMessage(ChatColor.YELLOW + "Select the spot with WorldEdit, look the way the weapon should point,"
                + " then type 'save'. Or type 'region <id>' to use a WorldGuard region.");
        player.sendMessage(ChatColor.GRAY + "Type 'facing <north|east|south|west>' to set the facing yourself."
                + " Size classes (wide × long × high along the facing): "
                + SpotSizeClass.SMALL.apiName() + " " + dims(SpotSizeClass.SMALL) + ", "
                + SpotSizeClass.MEDIUM.apiName() + " " + dims(SpotSizeClass.MEDIUM) + ", "
                + SpotSizeClass.LARGE.apiName() + " " + dims(SpotSizeClass.LARGE) + ".");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to hand the task back, or 'discard' to cancel it for good.");
        LOGGER.info("Started SiegeWeaponSpot task for player " + player.getName() + " (task " + taskId + ")");
    }

    @Override
    public boolean isHandling(Player player) {
        return isHandling(player.getUniqueId());
    }

    @Override
    public boolean isHandling(UUID playerId) {
        return playerId != null && sessions.containsKey(playerId);
    }

    @Override
    public void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Task stopped.");
            LOGGER.info("Stopped SiegeWeaponSpot task " + session.taskId + " of " + player.getName());
        }
    }

    @Override
    public Integer getTaskId(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null ? session.taskId : null;
    }

    @Override
    public void setLifecycle(WorldTaskLifecycle lifecycle) {
        this.lifecycle = lifecycle != null ? lifecycle : WorldTaskLifecycle.NONE;
    }

    @Override
    public boolean onPlayerChat(Player player, String message) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || message == null || message.isBlank()) return false;
        String[] parts = message.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "save" -> capture(player, session, selections.worldEditSelection(player), null);
            case "region" -> {
                if (argument.isEmpty()) {
                    player.sendMessage(PREFIX + ChatColor.RED + "Usage: region <WorldGuard region id>");
                } else {
                    capture(player, session, selections.worldGuardRegion(player.getWorld(), argument), argument);
                }
            }
            case "facing" -> facing(player, session, argument);
            case "confirm" -> confirm(player, session);
            case "cancel", "discard" -> endInChat(player, session.taskId, command.equals("discard"));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** #180 like the other handlers: {@code cancel} hands the task back, {@code discard} cancels it; the API is told. */
    private void endInChat(Player player, int taskId, boolean discard) {
        cancel(player);
        if (discard) {
            lifecycle.discarded(player, taskId);
            player.sendMessage(ChatColor.GRAY + "[WorldTask] Task " + taskId + " discarded; the web app shows it as cancelled.");
        } else {
            lifecycle.handedBack(player, taskId);
            player.sendMessage(ChatColor.GRAY + "[WorldTask] Task " + taskId + " handed back; its task code works again.");
        }
    }

    private void capture(Player player, Session session, SpotSelections.Read read, String regionId) {
        if (!read.ok()) {
            player.sendMessage(PREFIX + ChatColor.RED + read.error());
            return;
        }
        SpotSiteCheck.Result site = SpotSiteCheck.check(read.bounds(), probes.apply(player.getWorld()));
        session.draft = SpotDraft.capture(read.bounds(), regionId, player.getLocation().getYaw(), session.facingOverride, site);
        session.worldName = player.getWorld().getName();
        if (read.boundingBoxOnly()) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Not a cuboid: its bounding box is used.");
        }
        show(player, session.draft);
    }

    private void facing(Player player, Session session, String argument) {
        Optional<SpotFacing> facing = SpotFacing.parse(argument);
        if (facing.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: facing <north|east|south|west>");
            return;
        }
        session.facingOverride = facing.get();
        if (session.draft == null) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Facing " + facing.get().displayName()
                    + " is used when you type 'save'.");
            return;
        }
        session.draft = session.draft.withFacing(facing.get());
        show(player, session.draft);
    }

    private void show(Player player, SpotDraft draft) {
        for (String line : draft.summary()) {
            player.sendMessage(ChatColor.GRAY + line);
        }
        Optional<String> refusal = draft.refusal();
        if (refusal.isPresent()) {
            player.sendMessage(PREFIX + ChatColor.RED + refusal.get()
                    + " Select a larger area and type 'save' again, or change the facing.");
            return;
        }
        for (String warning : draft.site().warnings()) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Warning: " + warning);
        }
        player.sendMessage(ChatColor.YELLOW + "Type 'confirm' to send the spot to the web form"
                + (draft.site().hasWarnings() ? " anyway" : "") + ", 'facing <direction>' to turn it, 'save' to select again,"
                + " or 'cancel'.");
    }

    private void confirm(Player player, Session session) {
        if (session.draft == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Nothing selected yet: type 'save' (WorldEdit) or 'region <id>' first.");
            return;
        }
        Optional<String> output = SpotTaskOutput.toJson(session.draft, session.worldName, clock.getAsLong());
        if (output.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.RED + session.draft.refusal().orElse("The selection is too small."));
            return;
        }
        if (session.completing) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Already sending...");
            return;
        }
        session.completing = true;
        SpotDraft draft = session.draft;
        UUID playerId = player.getUniqueId();
        player.sendMessage(PREFIX + ChatColor.GRAY + "Completing the task...");
        worldTasksApi.complete(session.taskId, output.get())
                .whenComplete((completed, failure) -> mainThread.execute(() -> {
                    session.completing = false;
                    if (failure != null) {
                        if (WorldTaskApiErrors.taskMovedOn(failure)) {
                            // #180: cancelled in the web app, released or taken over meanwhile: nothing left to send.
                            sessions.remove(playerId, session);
                            player.sendMessage(PREFIX + ChatColor.RED + "Task " + session.taskId + " is no longer yours to complete"
                                    + WorldTaskApiErrors.currentStatus(failure).map(status -> " (" + status + ")").orElse("") + ".");
                        } else if (WorldTaskApiErrors.status(failure) == 422) {
                            // The API refused the result and failed the task; the web app offers "Start again".
                            sessions.remove(playerId, session);
                            player.sendMessage(PREFIX + ChatColor.RED + "The API refused the result and failed the task: "
                                    + WorldTaskApiErrors.message(failure).orElse("no reason given") + " Start it again in the web app.");
                        } else {
                            player.sendMessage(PREFIX + ChatColor.RED + "The task could not be completed: "
                                    + WorldTaskApiErrors.message(failure).orElse(String.valueOf(failure.getMessage()))
                                    + " Type 'confirm' again, or 'cancel'.");
                        }
                        LOGGER.warning("Completing SiegeWeaponSpot task " + session.taskId + " failed: " + failure);
                        return;
                    }
                    sessions.remove(playerId, session);
                    player.sendMessage(PREFIX + ChatColor.GREEN + "✓ Spot sent to the web form: facing "
                            + draft.facing().displayName() + ", size class " + draft.sizeClass().map(SpotSizeClass::apiName).orElse("?")
                            + ". Save the form there to create the spot.");
                    LOGGER.info(player.getName() + " completed SiegeWeaponSpot task " + session.taskId + " ("
                            + draft.bounds().describe() + ", " + draft.facing().apiName() + ")");
                }));
    }

    private static String dims(SpotSizeClass sizeClass) {
        return sizeClass.width() + "×" + sizeClass.length() + "×" + sizeClass.height();
    }
}
