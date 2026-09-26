package net.knightsandkings.knk.paper.user;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.messaging.FrozenGate;
import net.knightsandkings.knk.core.messaging.ParticipantId;
import net.knightsandkings.knk.core.messaging.PrivateMessageGate;
import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.core.messaging.RateLimitGate;
import net.knightsandkings.knk.core.messaging.RateLimiter;
import net.knightsandkings.knk.core.messaging.ReplyTargets;
import net.knightsandkings.knk.paper.chat.PrivateMessageFormat;
import net.knightsandkings.knk.paper.chat.PrivateMessageFormat.Party;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.format.TextColor;

/**
 * Player-to-player private messaging - /msg and /reply (rebuild of v1's MessageCommands),
 * hardened per docs/specs/private-messages/DESIGN.md §3.3. No permission is needed to send.
 * <p>
 * {@link #send} runs one message through: permission snapshot (the nodes the gates need, resolved
 * async through {@link KnkPermissible}) → gate chain ({@link FrozenGate}, {@link RateLimitGate};
 * the Phase 2 ignore gate and a future mute slot in here) → length cap → delivery (Adventure lines,
 * sound) → reply links ({@link ReplyTargets}: reciprocal on every delivered message) → social spy
 * ({@link SpyService}) → log ({@link PrivateMessageLogger}). Everything after the permission
 * snapshot runs on the main thread.
 * <p>
 * The console takes part as {@link ParticipantId#CONSOLE}: it can /msg a player, a player can /r
 * the console, and the console can /r its last partner.
 */
public class MessagingService {

    private static final Logger LOGGER = Logger.getLogger(MessagingService.class.getName());
    private static final Key PLING = Key.key("block.note_block.pling");

    private final KnkConfig.PrivateMessagesConfig config;
    private final KnkPermissible knkPermissible;
    private final AdminFreezeManager freezeManager;
    private final SpyService spyService;
    private final PrivateMessageLogger messageLog;
    private final VisiblePlayers visiblePlayers;
    private final Function<Player, TextColor> rankColor;
    private final Supplier<CommandSender> console;
    private final Executor mainThread;
    private final Clock clock;
    private final ReplyTargets replyTargets;
    private final RateLimiter rateLimiter;
    private final List<PrivateMessageGate> gates;

    /**
     * @param rankColor name colour per player (TabListTeam: owner, staff, premium tier, default);
     *                  null means default
     * @param clock     wall clock; its zone decides the "Sent 21:04" hover time
     */
    public MessagingService(KnkConfig.PrivateMessagesConfig config, KnkPermissible knkPermissible,
                            AdminFreezeManager freezeManager, SpyService spyService, PrivateMessageLogger messageLog,
                            VisiblePlayers visiblePlayers, Function<Player, TextColor> rankColor,
                            Supplier<CommandSender> console, Executor mainThread, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.knkPermissible = Objects.requireNonNull(knkPermissible, "knkPermissible must not be null");
        this.freezeManager = Objects.requireNonNull(freezeManager, "freezeManager must not be null");
        this.spyService = Objects.requireNonNull(spyService, "spyService must not be null");
        this.messageLog = Objects.requireNonNull(messageLog, "messageLog must not be null");
        this.visiblePlayers = Objects.requireNonNull(visiblePlayers, "visiblePlayers must not be null");
        this.rankColor = Objects.requireNonNull(rankColor, "rankColor must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.replyTargets = new ReplyTargets(clock);
        KnkConfig.PrivateMessagesConfig.RateLimitConfig limit = config.rateLimit();
        this.rateLimiter = new RateLimiter(limit.maxMessages(), limit.window(), limit.duplicateWindow(), clock);
        this.gates = List.of(new FrozenGate(), new RateLimitGate(rateLimiter));
    }

    /** Joins command arguments from {@code from} into the message text, trimmed ("" when there is none). */
    public static String joinText(String[] args, int from) {
        if (args.length <= from) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, from, args.length)).strip();
    }

    public static ParticipantId participantOf(CommandSender sender) {
        return sender instanceof Player player ? ParticipantId.player(player.getUniqueId()) : ParticipantId.CONSOLE;
    }

    /**
     * Sends {@code text} from {@code sender} to {@code recipient} (a player, or the console for a
     * player's /r). The caller has already resolved {@code recipient} vanish-safely.
     */
    public void send(CommandSender sender, CommandSender recipient, String text, boolean viaReply) {
        String capped = text.length() > config.maxLength() ? text.substring(0, config.maxLength()) : text;
        boolean senderFrozen = sender instanceof Player frozenCandidate && freezeManager.isFrozen(frozenCandidate.getUniqueId());

        CompletableFuture<Set<String>> senderNodes = sender instanceof Player senderPlayer
                ? nodesHeld(senderPlayer, PrivateMessageNodes.BYPASS_RATE_LIMIT)
                : CompletableFuture.completedFuture(Set.of());
        CompletableFuture<Set<String>> recipientNodes = senderFrozen && recipient instanceof Player target
                ? nodesHeld(target, PrivateMessageNodes.FREEZE)
                : CompletableFuture.completedFuture(Set.of());

        senderNodes.thenCombine(recipientNodes, (held, recipientHeld) -> new PrivateMessageGate.SendAttempt(
                        participantOf(sender), participantOf(recipient), capped, viaReply, senderFrozen, held, recipientHeld))
                .thenAccept(attempt -> mainThread.execute(() -> deliver(sender, recipient, attempt)))
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "Private message from " + sender.getName() + " failed", ex);
                    return null;
                });
    }

    /** /reply: sends to {@code sender}'s last partner, or explains why it can't. */
    public void reply(CommandSender sender, String text) {
        ReplyTargets.Resolution resolution = replyTargets.resolve(participantOf(sender),
                (replier, partner) -> presenceOf(sender, partner));
        switch (resolution.outcome()) {
            case NO_TARGET -> sender.sendMessage(ChatColor.RED + "Nobody to reply to.");
            case NOT_FOUND -> VisiblePlayers.sendNotFound(sender, resolution.link().partnerName());
            case PARTNER_OFFLINE -> sender.sendMessage(ChatColor.RED + resolution.link().partnerName() + " is no longer online.");
            case OK -> {
                ParticipantId partner = resolution.link().partner();
                CommandSender target = partner.isConsole() ? console.get() : visiblePlayers.onlineIgnoringVisibility(partner.uuid());
                if (target == null) {
                    VisiblePlayers.sendNotFound(sender, resolution.link().partnerName());
                    return;
                }
                send(sender, target, text, true);
            }
        }
    }

    /** Drops a quitting player's reply link and rate-limit history. */
    public void forget(UUID uuid) {
        ParticipantId participant = ParticipantId.player(uuid);
        replyTargets.forget(participant);
        rateLimiter.forget(participant);
    }

    ReplyTargets replyTargets() {
        return replyTargets;
    }

    private void deliver(CommandSender sender, CommandSender recipient, PrivateMessageGate.SendAttempt attempt) {
        if (sender instanceof Player senderPlayer && !senderPlayer.isOnline()) {
            return;
        }
        Party senderParty = partyOf(sender);
        Party recipientParty = partyOf(recipient);
        if (recipient instanceof Player target && !target.isOnline()) {
            VisiblePlayers.sendNotFound(sender, recipientParty.name());
            return;
        }

        Instant now = clock.instant();
        Optional<PrivateMessageGate.Denial> denial = PrivateMessageGate.firstDenial(gates, attempt);
        if (denial.isPresent()) {
            // Refused messages set no reply link and are not echoed to spies (DESIGN.md §3.3.4).
            sender.sendMessage(ChatColor.RED + denial.get().message());
            LOGGER.fine(() -> "Private message " + sender.getName() + " -> " + recipientParty.name()
                    + " refused: " + denial.get().reason());
            messageLog.log(entry(now, outcomeOf(denial.get().reason()), sender, senderParty, recipient, recipientParty, attempt));
            return;
        }

        LocalTime time = LocalTime.ofInstant(now, clock.getZone());
        String text = attempt.text();
        sender.sendMessage(PrivateMessageFormat.toSender(recipientParty, text));
        recipient.sendMessage(PrivateMessageFormat.toRecipient(senderParty, text, time));
        KnkConfig.PrivateMessagesConfig.SoundConfig sound = config.sound();
        if (recipient instanceof Player target && sound.enabled()) {
            target.playSound(Sound.sound(PLING, Sound.Source.MASTER, sound.volume(), sound.pitch()));
        }

        replyTargets.recordDelivered(attempt.sender(), senderParty.name(), attempt.recipient(), recipientParty.name(),
                VisiblePlayers.canSee(sender, recipient), VisiblePlayers.canSee(recipient, sender));
        spyService.broadcast(attempt.sender(), attempt.recipient(),
                PrivateMessageFormat.toSpy(senderParty, recipientParty, text, time, attempt.viaReply(), false));
        messageLog.log(entry(now, PrivateMessageLogger.Outcome.DELIVERED, sender, senderParty, recipient, recipientParty, attempt));
    }

    private ReplyTargets.Presence presenceOf(CommandSender replier, ParticipantId partner) {
        Player player = visiblePlayers.onlineIgnoringVisibility(partner.uuid());
        if (player == null) {
            return ReplyTargets.Presence.OFFLINE;
        }
        return VisiblePlayers.canSee(replier, player) ? ReplyTargets.Presence.ONLINE_VISIBLE : ReplyTargets.Presence.ONLINE_HIDDEN;
    }

    private CompletableFuture<Set<String>> nodesHeld(Player player, String... nodes) {
        CompletableFuture<Set<String>> held = CompletableFuture.completedFuture(new HashSet<>());
        for (String node : nodes) {
            CompletableFuture<Boolean> check = knkPermissible.hasPermissionAsync(player, node)
                    .exceptionally(ex -> false);
            held = held.thenCombine(check, (set, allowed) -> {
                if (Boolean.TRUE.equals(allowed)) {
                    set.add(node);
                }
                return set;
            });
        }
        return held;
    }

    private Party partyOf(CommandSender participant) {
        if (participant instanceof Player player) {
            return Party.player(player.getName(), rankColor.apply(player));
        }
        return Party.ofConsole();
    }

    private static PrivateMessageLogger.Outcome outcomeOf(PrivateMessageGate.Reason reason) {
        return switch (reason) {
            case FROZEN -> PrivateMessageLogger.Outcome.BLOCKED_FROZEN;
            case RATE_LIMITED -> PrivateMessageLogger.Outcome.BLOCKED_RATE_LIMITED;
            case IGNORED -> PrivateMessageLogger.Outcome.BLOCKED_IGNORED;
        };
    }

    private static PrivateMessageLogger.Entry entry(Instant at, PrivateMessageLogger.Outcome outcome,
                                                    CommandSender sender, Party senderParty,
                                                    CommandSender recipient, Party recipientParty,
                                                    PrivateMessageGate.SendAttempt attempt) {
        return new PrivateMessageLogger.Entry(at, outcome,
                senderParty.name(), sender instanceof Player from ? from.getUniqueId() : null,
                recipientParty.name(), recipient instanceof Player to ? to.getUniqueId() : null,
                attempt.text(), attempt.viaReply());
    }
}
