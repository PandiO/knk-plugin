package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi.Team;
import net.knightsandkings.knk.core.siege.weapons.SpotDraft;
import net.knightsandkings.knk.core.siege.weapons.SpotFacing;
import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;
import net.knightsandkings.knk.core.siege.weapons.SpotSizeClass;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The in-game weapon spot task (SIEGE_WEAPONS § 4, W4, W5, W44, W70; A02-A03; PandiO/knk-workspace#783). An admin
 * starts it with {@code /knk weaponspot <scenario> <team> <name>}, selects the spot with WorldEdit (or names a
 * WorldGuard region) and works in chat like the other world tasks:
 * <ol>
 *   <li>{@code save}: reads the WorldEdit selection; {@code region <id>}: reads a WorldGuard region. The facing is
 *       pre-filled from where the admin looks (rounded to north, east, south or west) unless they set one; the size
 *       class is worked out as width across × length along the facing × height; a selection smaller than the small
 *       class is refused; a floor that isn't solid or a volume that isn't clear is a warning.</li>
 *   <li>{@code facing <north|east|south|west>}: overrides the facing and works the size class out again.</li>
 *   <li>{@code confirm}: creates the spot through the API ({@code POST api/SiegeScenarios/{id}/weapon-spots}).</li>
 *   <li>{@code cancel}: stops the task; nothing is saved.</li>
 * </ol>
 * Like {@code GateDoorRegionCaptureHandler} this is a direct admin command that writes through the API, not a
 * form-field task of {@code WorldTaskHandlerRegistry}: a form-field task fills one field of the web form, a spot needs
 * bounds, facing and class together. Only registered while {@code siege.weapons.enabled} is on.
 */
public class WeaponSpotTask {

    private static final Logger LOGGER = Logger.getLogger(WeaponSpotTask.class.getName());
    static final String PREFIX = ChatColor.GOLD + "[Weapon spot] ";

    /** One admin's open task. {@code facingOverride} is set by 'facing' before the first capture. */
    private static final class Session {
        final int scenarioId;
        final Team team;
        final String name;
        SpotFacing facingOverride;
        SpotDraft draft;
        boolean saving;

        Session(int scenarioId, Team team, String name) {
            this.scenarioId = scenarioId;
            this.team = team;
            this.name = name;
        }
    }

    private final SiegeWeaponSpotsApi api;
    private final SpotSelections selections;
    private final Function<World, SpotSiteCheck.BlockProbe> probes;
    private final Executor mainThread;
    // Read from the async chat thread (isHandling), written on the main thread.
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public WeaponSpotTask(SiegeWeaponSpotsApi api, SpotSelections selections,
                          Function<World, SpotSiteCheck.BlockProbe> probes, Executor mainThread) {
        this.api = api;
        this.selections = selections;
        this.probes = probes;
        this.mainThread = mainThread;
    }

    public boolean isHandling(UUID playerId) {
        return playerId != null && sessions.containsKey(playerId);
    }

    public boolean isHandling(Player player) {
        return isHandling(player.getUniqueId());
    }

    /** Starts the task for a resolved scenario team; replaces nothing (the command refuses a second task). */
    public void start(Player player, int scenarioId, Team team, String name) {
        sessions.put(player.getUniqueId(), new Session(scenarioId, team, name));
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Weapon spot '" + name + "' for " + team.displayName()
                + " in scenario " + scenarioId + ".");
        player.sendMessage(ChatColor.YELLOW + "Select the spot with WorldEdit, look the way the weapon should point,"
                + " then type 'save'. Or type 'region <id>' to use a WorldGuard region.");
        player.sendMessage(ChatColor.GRAY + "Type 'facing <north|east|south|west>' to set the facing yourself, 'cancel' to stop."
                + " Size classes (wide × long × high along the facing): "
                + SpotSizeClass.SMALL.apiName() + " " + dims(SpotSizeClass.SMALL) + ", "
                + SpotSizeClass.MEDIUM.apiName() + " " + dims(SpotSizeClass.MEDIUM) + ", "
                + SpotSizeClass.LARGE.apiName() + " " + dims(SpotSizeClass.LARGE) + ".");
    }

    /** Chat input on the main thread; true when the message belonged to the task (it is hidden from chat). */
    public boolean onPlayerChat(Player player, String message) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || message == null) return false;
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
            case "cancel", "discard" -> cancel(player);
            default -> {
                return false;
            }
        }
        return true;
    }

    public void cancel(Player player) {
        if (sessions.remove(player.getUniqueId()) != null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Stopped; no spot was created.");
        }
    }

    /** Quit or plugin disable: the task ends without a message. */
    public void forget(UUID playerId) {
        sessions.remove(playerId);
    }

    private void capture(Player player, Session session, SpotSelections.Read read, String regionId) {
        if (!read.ok()) {
            player.sendMessage(PREFIX + ChatColor.RED + read.error());
            return;
        }
        SpotSiteCheck.Result site = SpotSiteCheck.check(read.bounds(), probes.apply(player.getWorld()));
        session.draft = SpotDraft.capture(read.bounds(), regionId, player.getLocation().getYaw(), session.facingOverride, site);
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
        player.sendMessage(ChatColor.YELLOW + "Type 'confirm' to create the spot"
                + (draft.site().hasWarnings() ? " anyway" : "") + ", 'facing <direction>' to turn it, 'save' to select again,"
                + " or 'cancel'.");
    }

    private void confirm(Player player, Session session) {
        if (session.draft == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Nothing selected yet: type 'save' (WorldEdit) or 'region <id>' first.");
            return;
        }
        Optional<SpotSizeClass> sizeClass = session.draft.sizeClass();
        if (sizeClass.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.RED + session.draft.refusal().orElse("The selection is too small."));
            return;
        }
        if (session.saving) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Already saving...");
            return;
        }
        session.saving = true;
        SpotDraft draft = session.draft;
        UUID playerId = player.getUniqueId();
        player.sendMessage(PREFIX + ChatColor.GRAY + "Saving...");
        api.createSpot(session.scenarioId, new SiegeWeaponSpotsApi.NewSpot(session.team.id(), session.name,
                        draft.bounds(), draft.wgRegionId(), draft.facing(), sizeClass.get()))
                .whenComplete((created, failure) -> mainThread.execute(() -> {
                    session.saving = false;
                    if (failure != null) {
                        player.sendMessage(PREFIX + ChatColor.RED + "The API refused the spot: " + reason(failure)
                                + " Fix it and type 'confirm' again, or 'cancel'.");
                        LOGGER.warning("Creating weapon spot '" + session.name + "' in scenario " + session.scenarioId
                                + " failed: " + failure);
                        return;
                    }
                    sessions.remove(playerId, session);
                    player.sendMessage(PREFIX + ChatColor.GREEN + "Created spot " + created.id() + " '" + created.name()
                            + "' for " + session.team.displayName() + ": facing " + created.facing() + ", size class "
                            + created.sizeClass() + ".");
                    LOGGER.info(player.getName() + " created weapon spot " + created.id() + " in scenario "
                            + session.scenarioId + " (" + draft.bounds().describe() + ", " + created.facing() + ", "
                            + created.sizeClass() + ")");
                }));
    }

    private static String reason(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof ApiException api && api.getStatusCode() > 0) {
            String body = api.getResponseBody();
            if (body == null || body.isBlank()) return "HTTP " + api.getStatusCode() + ".";
            String text = body.trim();
            // BadRequest("...") comes back as a JSON string when the client asks for JSON.
            if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length() - 1).replace("\\\"", "\"");
            }
            return text;
        }
        return "the API can't be reached (" + cause.getClass().getSimpleName() + ").";
    }

    private static String dims(SpotSizeClass sizeClass) {
        return sizeClass.width() + "×" + sizeClass.length() + "×" + sizeClass.height();
    }
}
