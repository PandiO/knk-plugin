package net.knightsandkings.knk.paper.realtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

import net.knightsandkings.knk.api.realtime.ModerationEvents;
import net.knightsandkings.knk.api.realtime.ModerationEvents.PlayerModeration;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.realtime.RecentActionIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Kick and ban from the web app (PandiO/knk-workspace#574, DESIGN §6.10): the game server's handlers for
 * {@code PlayerKick} and {@code PlayerBan} v1.
 * <ol>
 *   <li>{@code PlayerKick}: kicks the player with {@code uuid} if they are online on this server, with the reason (or
 *       {@code realtime.moderation.kick-default-reason}); otherwise nothing.</li>
 *   <li>{@code PlayerBan}: adds the profile to Paper's profile ban list, permanent, with the reason and the staff
 *       member's username as source, whether the player is online or not (an existing ban entry is kept as it is);
 *       then kicks them with {@code realtime.moderation.ban-message} if online. Unban stays {@code /pardon}.</li>
 * </ol>
 * Idempotent like {@link UserStateSync}: an {@code actionId} seen before is skipped, and both actions are harmless to
 * repeat anyway. Threads (P6): parsing and the duplicate check run on the realtime thread; the ban list and the kick
 * run on {@code mainThread}. Nothing here runs with {@code realtime.enabled: false} (the API refuses web kick/ban
 * while no game server is connected).
 */
public final class ModerationRealtimeSync {

    /** The server's profile ban list (main thread). */
    public interface Bans {
        boolean isBanned(UUID uuid, String username);

        /** Adds a permanent ban. */
        void ban(UUID uuid, String username, String reason, String source);
    }

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final String TAG = "[KnK Moderation] ";

    private final Executor mainThread;
    private final Function<UUID, Player> onlinePlayer;
    private final Bans bans;
    private final ModerationSettings settings;
    private final RecentActionIds handled;
    private final Logger logger;

    public ModerationRealtimeSync(Executor mainThread, Function<UUID, Player> onlinePlayer, Bans bans,
                                  ModerationSettings settings, RecentActionIds handled, Logger logger) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
        this.bans = Objects.requireNonNull(bans, "bans");
        this.settings = settings == null ? ModerationSettings.defaults() : settings;
        this.handled = handled == null ? new RecentActionIds() : handled;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Registers both handlers (before the client starts: it listens for the registered types). */
    public void register(RealtimeDispatcher dispatcher) {
        dispatcher.register(ModerationEvents.PLAYER_KICK, 1, Runnable::run, this::onKick);
        dispatcher.register(ModerationEvents.PLAYER_BAN, 1, Runnable::run, this::onBan);
    }

    // ---- handlers (realtime thread) ----

    void onKick(RealtimeEnvelope envelope) {
        PlayerModeration action = accept(envelope);
        if (action != null) {
            mainThread.execute(() -> kick(action));
        }
    }

    void onBan(RealtimeEnvelope envelope) {
        PlayerModeration action = accept(envelope);
        if (action != null) {
            mainThread.execute(() -> ban(action));
        }
    }

    /** The action to carry out, or null for a malformed payload (logged) or an action id handled before. */
    private PlayerModeration accept(RealtimeEnvelope envelope) {
        PlayerModeration action = PlayerModeration.parse(envelope);
        if (action == null) {
            logger.warning(TAG + envelope.type() + " (seq " + envelope.seq()
                + ") has no usable action id, user id or uuid; ignored");
            return null;
        }
        if (!handled.firstTime(action.actionId())) {
            logger.fine(TAG + envelope.type() + " " + action.actionId() + " was handled already; skipped");
            return null;
        }
        return action;
    }

    // ---- actions (main thread) ----

    void kick(PlayerModeration action) {
        Player player = onlinePlayer.apply(action.uuid());
        if (player == null || !player.isOnline()) {
            logger.info(TAG + "Web kick of " + who(action) + " by " + staff(action) + ": not online on this server");
            return;
        }
        String text = action.reason() != null ? action.reason()
            : ModerationSettings.fill(settings.kickDefaultReason(), null, action.username(), action.staffUsername());
        player.kick(AMPERSAND.deserialize(text), PlayerKickEvent.Cause.KICK_COMMAND);
        logger.info(TAG + "Kicked " + who(action) + " (web, by " + staff(action) + ", action " + action.actionId()
            + "): " + (action.reason() == null ? "no reason" : action.reason()));
    }

    void ban(PlayerModeration action) {
        String reason = action.reason() != null ? action.reason() : ModerationSettings.DEFAULT_BAN_REASON;
        String source = action.staffUsername() != null && !action.staffUsername().isBlank()
            ? action.staffUsername() : settings.banSource();
        try {
            if (bans.isBanned(action.uuid(), action.username())) {
                logger.info(TAG + "Web ban of " + who(action) + " by " + staff(action) + ": already banned; entry kept");
            } else {
                bans.ban(action.uuid(), action.username(), reason, source);
                logger.info(TAG + "Banned " + who(action) + " (web, by " + staff(action) + ", action "
                    + action.actionId() + "): " + reason);
            }
        } catch (RuntimeException e) {
            // Still kick: a player staff banned should not stay online because the ban list could not be written.
            logger.log(Level.SEVERE, TAG + "Adding the web ban of " + who(action) + " to the ban list failed", e);
        }
        Player player = onlinePlayer.apply(action.uuid());
        if (player != null && player.isOnline()) {
            Component message = AMPERSAND.deserialize(
                ModerationSettings.fill(settings.banMessage(), reason, action.username(), action.staffUsername()));
            player.kick(message, PlayerKickEvent.Cause.BANNED);
        }
    }

    private static String who(PlayerModeration action) {
        return (action.username() == null ? "?" : action.username()) + " (" + action.uuid() + ")";
    }

    private static String staff(PlayerModeration action) {
        return action.staffUsername() != null ? action.staffUsername()
            : (action.staffUserId() != null ? "user " + action.staffUserId() : "staff");
    }
}
