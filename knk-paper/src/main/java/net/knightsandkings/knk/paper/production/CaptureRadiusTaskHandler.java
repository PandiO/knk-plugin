package net.knightsandkings.knk.paper.production;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.production.survey.CaptureRadius;
import net.knightsandkings.knk.paper.tasks.ChatTaskInput;
import net.knightsandkings.knk.paper.tasks.IWorldTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskApiErrors;
import net.knightsandkings.knk.paper.tasks.WorldTaskLifecycle;

/**
 * The {@code CaptureRadius} world task (PRODUCTION_KINDS § 3.3, PandiO/knk-workspace#679): the production area form in
 * the web app starts it for a {@code Radius} area; an admin claims it with {@code /knk task <code>}, stands at the centre
 * and works in chat:
 * <ol>
 *   <li>{@code radius <blocks>}: the centre is where they stand, the circle is drawn with particles;</li>
 *   <li>{@code center}: moves the centre to where they stand now;</li>
 *   <li>{@code confirm}: completes the task with {@code shape}, {@code worldName}, {@code centerX/Y/Z} and {@code radius}
 *       ({@link CaptureRadius#toJson}); the form saves the area;</li>
 *   <li>{@code cancel} hands the task back, {@code discard} cancels it for good.</li>
 * </ol>
 * Registered by task type only, and only while {@code production.enabled} is on.
 */
public final class CaptureRadiusTaskHandler implements IWorldTaskHandler, ChatTaskInput {

    private static final Logger LOGGER = Logger.getLogger(CaptureRadiusTaskHandler.class.getName());
    static final String PREFIX = ChatColor.GOLD + "[Capture radius] ";
    static final int PREVIEW_SECONDS = 60;

    private static final class Session {
        final int taskId;
        final int maxRadius;
        CaptureRadius.Draft draft;
        boolean completing;

        Session(int taskId, int maxRadius) {
            this.taskId = taskId;
            this.maxRadius = maxRadius;
        }
    }

    private final WorldTasksApi worldTasksApi;
    private final AreaOutlineRenderer renderer;
    private final Executor mainThread;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile WorldTaskLifecycle lifecycle = WorldTaskLifecycle.NONE;

    public CaptureRadiusTaskHandler(WorldTasksApi worldTasksApi, AreaOutlineRenderer renderer, Executor mainThread) {
        this.worldTasksApi = worldTasksApi;
        this.renderer = renderer;
        this.mainThread = mainThread;
    }

    @Override
    public String getFieldName() {
        return CaptureRadius.TASK_TYPE;
    }

    @Override
    public void startTask(Player player, int taskId, String inputJson) {
        CaptureRadius.Input input = CaptureRadius.parseInput(inputJson);
        Session session = new Session(taskId, input.maxRadius());
        sessions.put(player.getUniqueId(), session);
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Production area radius, task #" + taskId + ".");
        player.sendMessage(ChatColor.YELLOW + "Stand at the centre of the area and type 'radius <blocks>' (1 to "
                + input.maxRadius() + "). Type 'center' to move the centre to where you stand, 'confirm' to send it.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to hand the task back, or 'discard' to cancel it for good.");
        if (input.radius() != null && input.radius() <= input.maxRadius()) {
            setDraft(player, session, input.radius());
        }
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
            renderer.stop(player);
            player.sendMessage(PREFIX + ChatColor.RED + "Task stopped.");
        }
    }

    @Override
    public Integer getTaskId(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : session.taskId;
    }

    @Override
    public void setLifecycle(WorldTaskLifecycle lifecycle) {
        this.lifecycle = lifecycle != null ? lifecycle : WorldTaskLifecycle.NONE;
    }

    @Override
    public boolean onPlayerChat(Player player, String message) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || message == null || message.isBlank()) {
            return false;
        }
        String[] parts = message.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "radius" -> {
                Optional<String> refusal = CaptureRadius.refusal(argument, session.maxRadius);
                if (refusal.isPresent()) {
                    player.sendMessage(PREFIX + ChatColor.RED + refusal.get());
                } else {
                    setDraft(player, session, CaptureRadius.parseRadius(argument));
                }
            }
            case "center", "centre" -> {
                if (session.draft == null) {
                    player.sendMessage(PREFIX + ChatColor.RED + "Type 'radius <blocks>' first.");
                } else {
                    setDraft(player, session, session.draft.radius());
                }
            }
            case "confirm" -> confirm(player, session);
            case "cancel", "discard" -> {
                boolean discard = command.equals("discard");
                cancel(player);
                if (discard) {
                    lifecycle.discarded(player, session.taskId);
                    player.sendMessage(ChatColor.GRAY + "[WorldTask] Task " + session.taskId + " discarded; the web app shows it as cancelled.");
                } else {
                    lifecycle.handedBack(player, session.taskId);
                    player.sendMessage(ChatColor.GRAY + "[WorldTask] Task " + session.taskId + " handed back; its task code works again.");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setDraft(Player player, Session session, int radius) {
        Location at = player.getLocation();
        session.draft = new CaptureRadius.Draft(player.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), radius);
        renderer.show(player, List.of(new AreaOutlineRenderer.Outline(session.draft.shape(at.getBlockY() - 2,
                at.getBlockY() + 2), false)), PREVIEW_SECONDS);
        player.sendMessage(PREFIX + ChatColor.GRAY + "Centre " + ChatColor.WHITE + at.getBlockX() + " " + at.getBlockY() + " "
                + at.getBlockZ() + ChatColor.GRAY + " in " + player.getWorld().getName() + ", radius " + ChatColor.WHITE
                + radius + ChatColor.GRAY + " blocks (drawn for " + PREVIEW_SECONDS + " s). Type 'confirm' to send it.");
    }

    private void confirm(Player player, Session session) {
        if (session.draft == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Nothing set yet: type 'radius <blocks>' first.");
            return;
        }
        if (session.completing) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Already sending...");
            return;
        }
        session.completing = true;
        UUID playerId = player.getUniqueId();
        CaptureRadius.Draft draft = session.draft;
        worldTasksApi.complete(session.taskId, CaptureRadius.toJson(draft))
                .whenComplete((completed, failure) -> mainThread.execute(() -> {
                    session.completing = false;
                    if (failure != null) {
                        if (WorldTaskApiErrors.taskMovedOn(failure) || WorldTaskApiErrors.status(failure) == 422) {
                            sessions.remove(playerId, session);
                            renderer.stop(player);
                        }
                        player.sendMessage(PREFIX + ChatColor.RED + "The task could not be completed: "
                                + WorldTaskApiErrors.message(failure).orElse(String.valueOf(failure.getMessage())));
                        LOGGER.warning("Completing CaptureRadius task " + session.taskId + " failed: " + failure);
                        return;
                    }
                    sessions.remove(playerId, session);
                    renderer.stop(player);
                    player.sendMessage(PREFIX + ChatColor.GREEN + "✓ Radius " + draft.radius() + " around " + draft.centerX()
                            + " " + draft.centerY() + " " + draft.centerZ() + " sent to the web form. Save the area there.");
                }));
    }
}
