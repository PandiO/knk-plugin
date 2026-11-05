package net.knightsandkings.knk.paper.realtime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.WorldTaskEvents;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.tasks.WorldTaskApiErrors;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.tasks.WorldTaskLeases;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * WT2 (PandiO/knk-workspace#181, DESIGN §8.3–§8.4): world tasks over the game hub. Registered on the
 * {@link RealtimeDispatcher} before the client starts; with {@code realtime.enabled: false} none of this runs and tasks
 * reach the game with their task code only, as before.
 * <ul>
 *   <li>{@code WorldTaskOffered}, mode {@code auto-claim} (the player pressed "Send to Minecraft"): checks the player is
 *       here, holds {@value #TASKS_PERMISSION} and has no task open; then claims it (claim mode {@code offer}) and starts
 *       its handler. Otherwise it answers {@code offline}, {@code no-permission}, {@code unavailable} or {@code busy}
 *       ({@code WorldTaskOfferResult}); the web app shows the task code or the busy choices. PandiO/knk-workspace#239:
 *       {@value #TASKS_PERMISSION} is checked like a command's node ({@link AccessCheck}: Bukkit or the knk permission
 *       model), and a refused player is told why in chat.</li>
 *   <li>mode {@code accept} ("Hand off to…" from someone else): a clickable {@code [Accept] [Decline]} line
 *       ({@code /knk task accept|decline <id>}); no answer within the offer's timeout (2 min) is {@code timed-out}.</li>
 *   <li>{@code WorldTaskCancelled}: the handler of a task this server holds stops at once (cancelled in the web app,
 *       expired, or released by the web app's "Replace"), instead of on the next heartbeat; an open offer is dropped.</li>
 *   <li>{@code WorldTaskOfferWithdrawn}: the Accept/Decline line is no longer valid (claimed with the code, timed out).</li>
 *   <li>{@code WorldTaskCreated} (a headless scan): the headless poller runs once now.</li>
 * </ul>
 * The checks run on the main thread (the dispatcher's executor); REST calls are asynchronous and come back on it.
 */
public final class WorldTaskRealtimeSync implements WorldTaskHandlerRegistry.OfferResponder {
    public static final String TASKS_PERMISSION = "knk.tasks";

    /**
     * PandiO/knk-workspace#239: whether a player may take world tasks ({@value #TASKS_PERMISSION}). The plugin wires Bukkit
     * <em>or</em> {@code KnkPermissible.checkAsync} (group grants made in the web app, as for commands); the default is
     * Bukkit only. Never completes exceptionally; {@link PermissionDecision#UNAVAILABLE} when it can't be asked.
     */
    public interface AccessCheck {
        CompletableFuture<PermissionDecision> check(Player player);

        AccessCheck BUKKIT_ONLY = player -> CompletableFuture.completedFuture(
            player.hasPermission(TASKS_PERMISSION) ? PermissionDecision.ALLOWED : PermissionDecision.DENIED);
    }

    /** Schedules work on the main thread after a delay. */
    public interface Scheduler {
        void later(Duration delay, Runnable task);
    }

    /** Starts the handler of a claimed task (main thread); see {@code KnkTaskClaimCommand.startClaimed}. */
    public interface Starter {
        boolean start(Player player, WorldTaskDto claimed);
    }

    /** One open Accept/Decline offer. */
    record Offer(int taskId, UUID player, String label, String from, Instant deadline) {
    }

    private final Executor mainThread;
    private final Function<UUID, Player> players;
    private final WorldTaskHandlerRegistry registry;
    private final WorldTasksApi worldTasksApi;
    private final WorldTaskLeases leases;
    private final String serverId;
    private final BiPredicate<String, Object> hubSend;
    private final Runnable headlessPollNow;
    private final Starter starter;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Logger logger;
    private final Map<Integer, Offer> offers = new ConcurrentHashMap<>();
    private volatile AccessCheck access = AccessCheck.BUKKIT_ONLY;

    /**
     * @param worldTasksApi the lease-tracking API (a claim is held and heartbeated)
     * @param hubSend       {@link RealtimeService#send}
     */
    public WorldTaskRealtimeSync(Executor mainThread, Function<UUID, Player> players, WorldTaskHandlerRegistry registry,
                                 WorldTasksApi worldTasksApi, WorldTaskLeases leases, String serverId,
                                 BiPredicate<String, Object> hubSend, Runnable headlessPollNow, Starter starter,
                                 Scheduler scheduler, Clock clock, Logger logger) {
        this.mainThread = mainThread;
        this.players = players;
        this.registry = registry;
        this.worldTasksApi = worldTasksApi;
        this.leases = leases;
        this.serverId = serverId;
        this.hubSend = hubSend;
        this.headlessPollNow = headlessPollNow;
        this.starter = starter;
        this.scheduler = scheduler;
        this.clock = clock;
        this.logger = logger;
    }

    /** #239: how {@value #TASKS_PERMISSION} is checked (default: Bukkit only). */
    public void setAccessCheck(AccessCheck access) {
        this.access = access == null ? AccessCheck.BUKKIT_ONLY : access;
    }

    /** Registers the handlers (before the client starts) and the Accept/Decline responder. */
    public void register(RealtimeDispatcher dispatcher) {
        dispatcher.register(WorldTaskEvents.WORLD_TASK_OFFERED, 1, mainThread, this::onOffered);
        dispatcher.register(WorldTaskEvents.WORLD_TASK_CANCELLED, 1, mainThread, this::onCancelled);
        dispatcher.register(WorldTaskEvents.WORLD_TASK_OFFER_WITHDRAWN, 1, mainThread, this::onWithdrawn);
        dispatcher.register(WorldTaskEvents.WORLD_TASK_CREATED, 1, Runnable::run, envelope -> headlessPollNow.run());
        registry.setOfferResponder(this);
    }

    // ---- events (main thread) ----

    void onOffered(RealtimeEnvelope envelope) {
        WorldTaskEvents.Offered event = WorldTaskEvents.Offered.parse(envelope);
        if (event == null) {
            return;
        }
        int taskId = event.taskId();
        UUID uuid = event.uuid();
        Player player = players.apply(uuid);
        if (player == null || !player.isOnline()) {
            answer(taskId, uuid, "offline");
            return;
        }
        CompletableFuture<PermissionDecision> decision;
        try {
            decision = access.check(player);
        } catch (RuntimeException error) {
            decision = CompletableFuture.failedFuture(error);
        }
        decision.whenComplete((answer, error) -> mainThread.execute(() ->
            onOfferChecked(event, error == null && answer != null ? answer : PermissionDecision.UNAVAILABLE)));
    }

    /** The rest of {@link #onOffered} once {@value #TASKS_PERMISSION} is known (main thread). */
    private void onOfferChecked(WorldTaskEvents.Offered event, PermissionDecision decision) {
        int taskId = event.taskId();
        UUID uuid = event.uuid();
        String label = event.label();
        Player player = players.apply(uuid);
        if (player == null || !player.isOnline()) {
            answer(taskId, uuid, "offline");
            return;
        }
        if (decision == PermissionDecision.DENIED) {
            player.sendMessage(ChatColor.YELLOW + "[WorldTask] " + sender(event) + " tried to send you a task (" + label
                + "), but you don't have world-task access in game (" + TASKS_PERMISSION + "). Ask an admin for it.");
            answer(taskId, uuid, "no-permission");
            return;
        }
        if (decision != PermissionDecision.ALLOWED) {
            player.sendMessage(ChatColor.YELLOW + "[WorldTask] A task from the web app (" + label + ") could not start: "
                + "your world-task access can't be checked right now. The web app shows its task code.");
            answer(taskId, uuid, "unavailable");
            return;
        }
        if (registry.isHandling(uuid) || hasOtherOffer(uuid, taskId)) {
            player.sendMessage(ChatColor.YELLOW + "[WorldTask] A task from the web app (" + label + ") could not start: "
                + "you still have a task open. Finish it, or type 'cancel' to hand it back.");
            answer(taskId, uuid, "busy");
            return;
        }

        if (!event.autoClaim()) {
            int timeout = event.timeoutSeconds();
            Offer offer = new Offer(taskId, uuid, label, event.fromUsername(), clock.instant().plusSeconds(timeout));
            offers.put(taskId, offer);
            showOffer(player, offer, timeout);
            scheduler.later(Duration.ofSeconds(timeout), () -> timeOut(offer));
            return;
        }

        player.sendMessage(ChatColor.AQUA + "[WorldTask] Task from the web app: " + ChatColor.WHITE + label);
        claim(player, taskId, label);
    }

    private static String sender(WorldTaskEvents.Offered event) {
        String from = event.fromUsername();
        return from == null || from.isBlank() || event.autoClaim() ? "The web app" : from;
    }

    void onCancelled(RealtimeEnvelope envelope) {
        WorldTaskEvents.Cancelled event = WorldTaskEvents.Cancelled.parse(envelope);
        if (event == null) {
            return;
        }
        int taskId = event.taskId();
        String status = event.status();
        String reason = event.reason();
        Offer offer = offers.remove(taskId);
        if (offer != null) {
            Player player = players.apply(offer.player());
            if (player != null) {
                player.sendMessage(ChatColor.YELLOW + "[WorldTask] The offer of " + offer.label() + " was withdrawn"
                    + ("Expired".equalsIgnoreCase(status) ? " (expired)." : " (cancelled in the web app)."));
            }
        }
        leases.take(taskId).ifPresent(held -> {
            Player holder = held.playerId() == null ? null : players.apply(held.playerId());
            if (holder != null && registry.stopTask(holder, taskId)) {
                holder.sendMessage(ChatColor.YELLOW + "[WorldTask] Task " + taskId + " " + stopMessage(status, reason));
            }
        });
    }

    void onWithdrawn(RealtimeEnvelope envelope) {
        WorldTaskEvents.OfferWithdrawn event = WorldTaskEvents.OfferWithdrawn.parse(envelope);
        if (event == null) {
            return;
        }
        Offer offer = offers.remove(event.taskId());
        if (offer == null) {
            return;
        }
        Player player = players.apply(offer.player());
        if (player != null) {
            String reason = event.reason();
            player.sendMessage(ChatColor.GRAY + "[WorldTask] The offer of " + offer.label() + " is no longer open"
                + (reason != null ? ": " + reason : "."));
        }
    }

    // ---- Accept / Decline (main thread, from /knk task accept|decline <id>) ----

    @Override
    public void respond(Player player, int taskId, boolean accept) {
        Offer offer = offers.get(taskId);
        if (offer == null || !offer.player().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "There is no open offer for task " + taskId + ".");
            return;
        }
        if (!clock.instant().isBefore(offer.deadline())) {
            timeOut(offer);
            return;
        }
        offers.remove(taskId);
        if (!accept) {
            player.sendMessage(ChatColor.GRAY + "[WorldTask] Declined " + offer.label() + ". The web app shows the task code.");
            answer(taskId, offer.player(), "declined");
            return;
        }
        if (registry.isHandling(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You still have a task open. Finish it, or type 'cancel' to hand it back, then accept again.");
            offers.put(taskId, offer);
            return;
        }
        claim(player, taskId, offer.label());
    }

    /** Open Accept/Decline offers (diagnostics, tests). */
    Map<Integer, Offer> openOffers() {
        return Map.copyOf(offers);
    }

    // ---- internals ----

    private void claim(Player player, int taskId, String label) {
        worldTasksApi.claim(taskId, ClaimTaskDto.byOffer(serverId, player.getName(), player.getUniqueId().toString()))
            .whenComplete((claimed, error) -> mainThread.execute(() -> {
                if (error != null) {
                    int status = WorldTaskApiErrors.status(error);
                    String message = WorldTaskApiErrors.message(error).orElse(error.getMessage());
                    player.sendMessage(ChatColor.RED + "[WorldTask] Could not take " + label + ": " + message);
                    if (status != 409 && status != 404) {
                        logger.warning("Realtime: claiming offered world task " + taskId + " failed: " + error.getMessage());
                    }
                    return;
                }
                if (claimed == null) {
                    return;
                }
                player.sendMessage(ChatColor.GREEN + "✓ Task claimed. Type 'cancel' to hand it back.");
                starter.start(player, claimed);
            }));
    }

    private void showOffer(Player player, Offer offer, int timeoutSeconds) {
        String who = offer.from() == null || offer.from().isBlank() ? "Someone" : offer.from();
        player.sendMessage(Component.text("[WorldTask] " + who + " sent you a task from the web app: ", NamedTextColor.AQUA)
            .append(Component.text(offer.label(), NamedTextColor.WHITE)));
        player.sendMessage(Component.text("[Accept]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/knk task accept " + offer.taskId()))
            .hoverEvent(HoverEvent.showText(Component.text("Claim the task and start it")))
            .append(Component.text("  "))
            .append(Component.text("[Decline]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/knk task decline " + offer.taskId()))
                .hoverEvent(HoverEvent.showText(Component.text("The web app shows the task code instead"))))
            .append(Component.text("  (open for " + formatDuration(timeoutSeconds) + ")", NamedTextColor.GRAY)));
    }

    private void timeOut(Offer offer) {
        if (!offers.remove(offer.taskId(), offer)) {
            return;
        }
        Player player = players.apply(offer.player());
        if (player != null) {
            player.sendMessage(ChatColor.GRAY + "[WorldTask] The offer of " + offer.label() + " timed out.");
        }
        answer(offer.taskId(), offer.player(), "timed-out");
    }

    private boolean hasOtherOffer(UUID uuid, int taskId) {
        return offers.values().stream().anyMatch(o -> o.player().equals(uuid) && o.taskId() != taskId);
    }

    private void answer(int taskId, UUID uuid, String result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("result", result);
        body.put("uuid", uuid.toString());
        if (!hubSend.test(WorldTaskEvents.WORLD_TASK_OFFER_RESULT, body)) {
            logger.fine("Realtime: offer answer for task " + taskId + " not sent (" + result + "); the API times the offer out");
        }
    }

    static String stopMessage(String status, String reason) {
        if ("Pending".equalsIgnoreCase(status)) {
            return "was handed back" + (reason != null ? ": " + reason : ".");
        }
        if ("Expired".equalsIgnoreCase(status)) {
            return "expired.";
        }
        return "was cancelled in the web app.";
    }

    static String formatDuration(int seconds) {
        return seconds % 60 == 0 ? (seconds / 60) + " min" : seconds + " s";
    }
}
