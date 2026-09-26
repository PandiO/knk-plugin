package net.knightsandkings.knk.paper.teleport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportCooldowns;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Request;
import net.knightsandkings.knk.core.teleport.TeleportRequestSettings;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Player teleport requests - {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny},
 * {@code /tpcancel} (docs/specs/teleport/DESIGN.md §3.5, Phase 3). The pending state is a
 * {@link TeleportRequestBook}; the teleport itself goes through {@link TeleportService} with kind
 * {@link TeleportKind#REQUEST}, so every engine guard applies.
 * <ul>
 *   <li><b>Send</b>: refused when the requester is vanished and the target can't see them (it would
 *       reveal them - DESIGN §3.4.4), during the send cooldown ({@code teleport.request.cooldown-seconds},
 *       bypass {@value TeleportNodes#BYPASS_COOLDOWN}), and when the engine's guards already refuse
 *       the teleport (combat tag, cooldown, frozen, closed domain, siege...). The target gets a
 *       clickable {@code [Accept] [Deny]}.</li>
 *   <li><b>Accept</b>: takes the request (so a second accept finds nothing), then starts the
 *       teleport: the engine re-runs every guard for the moving player and, through
 *       {@link TeleportPlan#visited()}, the other one; the warmup is on the moving player (the
 *       requester for {@code /tpa}, the target for {@code /tpahere}); the destination is the other
 *       player's <em>live</em> location when the warmup ends.</li>
 *   <li>Requests expire ({@link #tick}) and are dropped when either player quits or dies
 *       ({@link #forget}).</li>
 * </ul>
 * A requester who isn't online, or who the answering player can't see (vanished since), counts as
 * "no pending request" - the same answer as for a name that never asked, so nothing is revealed.
 * <p>
 * Price: {@code teleport.request.price-coins} coins (default 0 = free), charged to the requester by
 * knk-web-api when the teleport commits - after the warmup, through the same charge path as warps
 * ({@link TeleportCharges}, Phase 5) - and refunded if it then doesn't happen. Without the API
 * client ({@link #setCharges} never called) a non-zero price refuses every request instead.
 * <p>
 * Threading: every public method runs on the main thread; permission lookups are async and hop
 * back through {@code mainThread}.
 */
public class TeleportRequestService {

    private static final Logger LOGGER = Logger.getLogger(TeleportRequestService.class.getName());
    static final String PAID_NOT_AVAILABLE = "Paid teleport requests aren't available right now.";

    private final TeleportService engine;
    private final Executor mainThread;
    private final TeleportService.PermissionLookup permissions;
    private final VisibleTargetResolver targets;
    private final Function<UUID, Player> onlineById;
    private final TeleportRequestBook book;
    private final TeleportCooldowns sendCooldowns = new TeleportCooldowns();
    private volatile TeleportRequestSettings settings;
    /** Whether {@code viewer} ignores {@code sender} (UUIDs); none until an ignore list is wired in. */
    private volatile BiPredicate<UUID, UUID> ignores = (viewer, sender) -> false;
    /** Charges the request fee server-side; null = paid requests are refused. */
    private volatile TeleportCharges charges;
    private boolean warnedAboutPrice;

    public TeleportRequestService(TeleportService engine, Executor mainThread, TeleportService.PermissionLookup permissions,
                                  VisibleTargetResolver targets, Function<UUID, Player> onlineById) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.onlineById = Objects.requireNonNull(onlineById, "onlineById must not be null");
        this.settings = engine.settings().request();
        this.book = new TeleportRequestBook(settings);
    }

    /**
     * Requests from a player the target ignores are swallowed: the requester is told it was sent,
     * the target never sees it. Meant for the private-messages ignore list
     * ({@code IgnoreService.ignores(viewer, sender)} on {@code claude/private-messages}) once both
     * branches meet; null turns it off.
     */
    public void setIgnoreCheck(BiPredicate<UUID, UUID> ignores) {
        this.ignores = ignores != null ? ignores : (viewer, sender) -> false;
    }

    public TeleportRequestSettings settings() {
        return settings;
    }

    /** How the {@code teleport.request.price-coins} fee is charged (Phase 5); null refuses paid requests. */
    public void setCharges(TeleportCharges charges) {
        this.charges = charges;
    }

    // ----- send -----

    /**
     * {@code requester} asks {@code target} for a teleport ({@link Direction#TO_TARGET}: {@code /tpa},
     * {@link Direction#TO_REQUESTER}: {@code /tpahere}). The caller has checked the command's node
     * and found {@code target} as {@code requester} can see them.
     */
    public void send(Player requester, Player target, Direction direction) {
        syncSettings();
        if (refusedForPrice(requester)) {
            return;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            requester.sendMessage(ChatColor.RED + "You can't send a teleport request to yourself.");
            return;
        }
        if (!targets.canSee(target, requester)) {
            requester.sendMessage(ChatColor.RED + "You're vanished - " + target.getName()
                + " can't see you. Unvanish to send a request (staff can use /tp).");
            return;
        }
        permissions.has(requester, TeleportNodes.BYPASS_COOLDOWN).exceptionally(ex -> false)
            .thenAccept(bypass -> mainThread.execute(() -> guardedStep(requester, () ->
                sendChecked(requester, target, direction, Boolean.TRUE.equals(bypass)))));
    }

    private void sendChecked(Player requester, Player target, Direction direction, boolean bypassCooldown) {
        UUID requesterId = requester.getUniqueId();
        long now = engine.now();
        if (!bypassCooldown) {
            int wait = sendCooldowns.remainingSeconds(requesterId, TeleportKind.REQUEST, now);
            if (wait > 0) {
                requester.sendMessage(ChatColor.RED + "You can send another teleport request in " + wait + " s.");
                return;
            }
        }
        TeleportPlan preview = plan(requester, target, direction);
        engine.check(preview).thenAccept(denial -> guardedStep(requester, () -> {
            if (!requester.isOnline()) {
                return;
            }
            if (!target.isOnline() || !targets.canSee(requester, target)) {
                requester.sendMessage(ChatColor.RED + "No online player named '" + target.getName() + "'.");
                return;
            }
            if (!targets.canSee(target, requester)) {
                // Vanished while the checks ran.
                requester.sendMessage(ChatColor.RED + "You're vanished - " + target.getName() + " can't see you.");
                return;
            }
            if (denial.isPresent()) {
                requester.sendMessage(ChatColor.RED + (direction == Direction.TO_TARGET
                    ? denial.get().message()
                    : target.getName() + " can't teleport to you right now."));
                return;
            }
            store(requester, target, direction, bypassCooldown);
        }));
    }

    private void store(Player requester, Player target, Direction direction, boolean bypassCooldown) {
        long now = engine.now();
        if (ignores.test(target.getUniqueId(), requester.getUniqueId())) {
            // Don't tell the requester they're ignored; the target never hears of it.
            startSendCooldown(requester, bypassCooldown, now);
            requester.sendMessage(sentMessage(target, book.expireSeconds()));
            return;
        }
        TeleportRequestBook.SendResult result = book.send(requester.getUniqueId(), target.getUniqueId(), direction, now);
        switch (result.status()) {
            case SELF -> requester.sendMessage(ChatColor.RED + "You can't send a teleport request to yourself.");
            case DUPLICATE -> requester.sendMessage(ChatColor.YELLOW + "Your request to " + target.getName()
                + " is already pending (" + result.request().secondsLeft(now) + " s left). /tpcancel to withdraw.");
            case REVERSE_PENDING -> requester.sendMessage(ChatColor.YELLOW + target.getName()
                + " already sent you a teleport request - /tpaccept " + target.getName() + " to answer it.");
            case SENT -> {
                startSendCooldown(requester, bypassCooldown, now);
                result.replaced().ifPresent(old -> {
                    Player oldTarget = onlineById.apply(old.target());
                    if (oldTarget != null && !old.target().equals(target.getUniqueId())) {
                        requester.sendMessage(ChatColor.GRAY + "Your request to " + oldTarget.getName() + " was withdrawn.");
                    }
                });
                for (Request dropped : result.dropped()) {
                    tell(dropped.requester(), "Your teleport request to " + target.getName()
                        + " was dropped: they have too many pending requests.");
                }
                requester.sendMessage(sentMessage(target, book.expireSeconds()));
                if (settings.isPaid()) {
                    requester.sendMessage(ChatColor.GRAY + "It costs you " + settings.priceCoins()
                        + " coins if the teleport happens.");
                }
                target.sendMessage(requestNotice(requester, direction, book.expireSeconds()));
            }
        }
    }

    private void startSendCooldown(Player requester, boolean bypassCooldown, long now) {
        if (!bypassCooldown) {
            sendCooldowns.start(requester.getUniqueId(), TeleportKind.REQUEST, now, settings.cooldownSeconds());
        }
    }

    private static String sentMessage(Player target, int expireSeconds) {
        return ChatColor.GREEN + "Request sent to " + target.getName() + ". It expires in " + expireSeconds
            + " s. " + ChatColor.GRAY + "/tpcancel to withdraw.";
    }

    /** "X wants to teleport to you." + clickable [Accept] [Deny] (running /tpaccept X, /tpdeny X). */
    static Component requestNotice(Player requester, Direction direction, int expireSeconds) {
        String name = requester.getName();
        String what = direction == Direction.TO_TARGET ? " wants to teleport to you." : " asks you to teleport to them.";
        return Component.text()
            .append(Component.text(name, ColorOptions.names))
            .append(Component.text(what, ColorOptions.message))
            .append(Component.newline())
            .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/tpaccept " + name))
                .hoverEvent(HoverEvent.showText(Component.text("Accept " + name + "'s request", NamedTextColor.GREEN))))
            .append(Component.space())
            .append(Component.text("[Deny]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/tpdeny " + name))
                .hoverEvent(HoverEvent.showText(Component.text("Deny " + name + "'s request", NamedTextColor.RED))))
            .append(Component.text(" Expires in " + expireSeconds + " s.", ColorOptions.message))
            .build();
    }

    // ----- answer -----

    /** {@code target} accepts the newest request, or {@code requesterName}'s. */
    public void accept(Player target, String requesterName) {
        syncSettings();
        if (refusedForPrice(target)) {
            return;
        }
        Optional<Request> taken = take(target, requesterName);
        if (taken.isEmpty()) {
            return;
        }
        Request request = taken.get();
        Player requester = onlineById.apply(request.requester());
        if (requester == null) {
            target.sendMessage(ChatColor.RED + "That request is no longer available.");
            return;
        }
        Player mover = request.direction() == Direction.TO_TARGET ? requester : target;
        Player stationary = mover == requester ? target : requester;
        target.sendMessage(ChatColor.GREEN + "Accepted " + requester.getName() + "'s teleport request.");
        if (request.direction() == Direction.TO_TARGET) {
            requester.sendMessage(ChatColor.GREEN + target.getName() + " accepted your teleport request.");
        } else {
            requester.sendMessage(ChatColor.GREEN + target.getName() + " accepted your request and is on the way.");
        }
        TeleportPlan plan = plan(requester, target, request.direction());
        TeleportCharges fees = charges;
        if (settings.isPaid() && fees != null) {
            // The requester pays, whoever moves; charged after the warmup, refunded if it fails.
            plan = plan.withCharge(fees.requestFee(requester, target, settings.priceCoins()));
        }
        engine.start(plan).thenAccept(outcome -> report(mover, stationary, outcome));
    }

    /** {@code target} denies the newest request, or {@code requesterName}'s. */
    public void deny(Player target, String requesterName) {
        Optional<Request> taken = take(target, requesterName);
        if (taken.isEmpty()) {
            return;
        }
        Player requester = onlineById.apply(taken.get().requester());
        String name = requester != null ? requester.getName() : "that player";
        target.sendMessage(ChatColor.YELLOW + "Denied " + name + "'s teleport request.");
        if (requester != null) {
            requester.sendMessage(ChatColor.RED + target.getName() + " denied your teleport request.");
        }
    }

    /**
     * The request {@code target} is answering, removed from the book - or empty after telling them
     * there's none. Requests from players who left or whom the target can't see are skipped.
     */
    private Optional<Request> take(Player target, String requesterName) {
        UUID targetId = target.getUniqueId();
        long now = engine.now();
        if (requesterName != null && !requesterName.isBlank()) {
            Player requester = targets.find(target, requesterName);
            Optional<Request> taken = requester == null ? Optional.empty()
                : book.take(targetId, requester.getUniqueId(), now);
            if (taken.isEmpty()) {
                target.sendMessage(ChatColor.RED + "No pending teleport request from " + requesterName + ".");
            }
            return taken;
        }
        for (Request pending : book.incoming(targetId, now)) {
            Player requester = onlineById.apply(pending.requester());
            if (requester != null && targets.canSee(target, requester)) {
                Optional<Request> taken = book.take(targetId, pending.requester(), now);
                if (taken.isPresent()) {
                    return taken;
                }
            }
        }
        target.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
        return Optional.empty();
    }

    /** Tell both players how an accepted request's teleport ended. */
    private void report(Player mover, Player stationary, TeleportOutcome outcome) {
        switch (outcome.status()) {
            case TELEPORTED -> {
                tell(mover, ChatColor.GREEN + "Teleported to " + stationary.getName() + ".");
                tell(stationary, ChatColor.GRAY + mover.getName() + " teleported to you.");
            }
            // The engine already told the mover why their warmup stopped.
            case CANCELLED -> tell(stationary, ChatColor.GRAY + mover.getName() + "'s teleport to you was cancelled.");
            case DENIED, FAILED -> {
                String reason = outcome.message() != null ? outcome.message() : "The teleport didn't happen.";
                tell(mover, ChatColor.RED + reason);
                tell(stationary, ChatColor.GRAY + mover.getName() + " couldn't teleport to you.");
            }
        }
    }

    // ----- withdraw / cancel -----

    /** {@code /tpcancel}: withdraw the outgoing request, or else stop the player's running warmup. */
    public void cancel(Player player) {
        Optional<Request> withdrawn = book.cancelOutgoing(player.getUniqueId(), engine.now());
        if (withdrawn.isPresent()) {
            Player target = onlineById.apply(withdrawn.get().target());
            String name = target != null ? target.getName() : "that player";
            player.sendMessage(ChatColor.YELLOW + "Your teleport request to " + name + " was withdrawn.");
            if (target != null) {
                target.sendMessage(ChatColor.GRAY + player.getName() + " withdrew their teleport request.");
            }
            return;
        }
        if (engine.isWarmingUp(player.getUniqueId())) {
            engine.cancelWarmup(player.getUniqueId(), WarmupCancelReason.BY_PLAYER);
            return;
        }
        player.sendMessage(ChatColor.RED + "You have no pending teleport request or teleport to cancel.");
    }

    // ----- lifecycle -----

    /** Expire old requests, telling both sides (called from the engine's periodic tick). Main thread. */
    public void tick() {
        syncSettings();
        long now = engine.now();
        for (Request expired : book.sweepExpired(now)) {
            Player requester = onlineById.apply(expired.requester());
            Player target = onlineById.apply(expired.target());
            if (requester != null && target != null) {
                requester.sendMessage(ChatColor.GRAY + "Your teleport request to " + target.getName() + " expired.");
                target.sendMessage(ChatColor.GRAY + "The teleport request from " + requester.getName() + " expired.");
            }
        }
        sendCooldowns.purgeExpired(now);
    }

    /** {@code player} quit or died: drop everything they sent or received, telling the other side. */
    public void forget(Player player) {
        UUID id = player.getUniqueId();
        for (Request request : book.clear(id, engine.now())) {
            UUID other = request.requester().equals(id) ? request.target() : request.requester();
            tell(other, "Teleport request " + (request.requester().equals(id) ? "from " : "to ")
                + player.getName() + " cancelled.");
        }
    }

    /** Names of players with a pending request to {@code target} that {@code target} can see (tab completion). */
    public List<String> pendingRequesterNames(Player target) {
        List<String> names = new ArrayList<>();
        for (Request request : book.incoming(target.getUniqueId(), engine.now())) {
            Player requester = onlineById.apply(request.requester());
            if (requester != null && targets.canSee(target, requester)) {
                names.add(requester.getName());
            }
        }
        return names;
    }

    // ----- plumbing -----

    /**
     * The engine plan for a request: the mover goes to the other player's location as it is when
     * the teleport happens (read again at commit), with that player as {@code visited} so the
     * engine's guards (frozen, siege) cover both.
     */
    static TeleportPlan plan(Player requester, Player target, Direction direction) {
        Player mover = direction == Direction.TO_TARGET ? requester : target;
        Player stationary = direction == Direction.TO_TARGET ? target : requester;
        return new TeleportPlan(mover, () -> stationary.isOnline() ? stationary.getLocation() : null,
            TeleportKind.REQUEST, requester, stationary, false, stationary.getName());
    }

    private boolean refusedForPrice(Player player) {
        if (!settings.isPaid() || charges != null) {
            return false;
        }
        if (!warnedAboutPrice) {
            warnedAboutPrice = true;
            LOGGER.warning("[KnK Teleport] teleport.request.price-coins is " + settings.priceCoins()
                + ", but the API client isn't available to charge it."
                + " /tpa and /tpahere are refused until it is set back to 0 or the API is reachable.");
        }
        player.sendMessage(ChatColor.RED + PAID_NOT_AVAILABLE);
        return true;
    }

    /** Pick up a reloaded {@code teleport.request} block. */
    private void syncSettings() {
        TeleportRequestSettings current = engine.settings().request();
        if (!current.equals(settings)) {
            settings = current;
            book.configure(current.expireSeconds(), current.maxIncoming());
            warnedAboutPrice = false;
        }
    }

    private void tell(UUID playerId, String message) {
        Player player = onlineById.apply(playerId);
        if (player != null) {
            player.sendMessage(ChatColor.GRAY + message);
        }
    }

    private static void tell(Player player, String message) {
        if (player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private static void guardedStep(Player player, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.SEVERE, "Teleport request of " + player.getName() + " failed", failure);
            if (player.isOnline()) {
                player.sendMessage(ChatColor.RED + "Teleport request failed - see the server log.");
            }
        }
    }
}
