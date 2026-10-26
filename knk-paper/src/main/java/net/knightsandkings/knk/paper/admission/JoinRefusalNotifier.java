package net.knightsandkings.knk.paper.admission;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.knightsandkings.knk.core.admission.JoinRefusal;
import net.knightsandkings.knk.core.admission.JoinRefusalThrottle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Tells online holders of {@value #NODE} when a player's join is refused, with the reason (PandiO/knk-workspace#159):
 * <ul>
 *   <li>a Knights and Kings pre-login check (admission gate, missing world, unusable hub): the reason it recorded in
 *       {@link JoinRefusalReasons}; another plugin's pre-login refusal by its result or disconnect text;</li>
 *   <li>the vanilla checks (ban, IP ban, whitelist, full server) at Paper's login validation, classified by
 *       {@link VanillaChecks}.</li>
 * </ul>
 * One notice per player per {@code join-refusal-notify.cooldown-seconds} ({@link JoinRefusalThrottle}), so a retry
 * loop, or one refusal seen by both checks, gives one line. Messages go out on the main thread.
 */
public final class JoinRefusalNotifier implements Listener {

    public static final String NODE = "knk.notify.joinattempt";
    private static final Logger LOGGER = Logger.getLogger(JoinRefusalNotifier.class.getName());
    private static final int MAX_DETAIL = 120;

    /** The vanilla refusal reasons, asked of the server (kept out so the classification can be tested). */
    public interface VanillaChecks {
        /** The ban reason ("" when none was given), or null when the profile isn't banned. */
        String profileBanReason(PlayerProfile profile);

        boolean ipBanned(InetSocketAddress address);

        boolean whitelistRefuses(UUID uuid);

        boolean full();
    }

    private final JoinRefusalReasons reasons;
    private final JoinRefusalThrottle throttle;
    private final BooleanSupplier enabled;
    private final Supplier<Collection<? extends Player>> online;
    private final BiPredicate<Player, String> permission;
    private final Executor mainThread;
    private final VanillaChecks vanilla;

    public JoinRefusalNotifier(JoinRefusalReasons reasons, JoinRefusalThrottle throttle, BooleanSupplier enabled,
                               Supplier<Collection<? extends Player>> online, BiPredicate<Player, String> permission,
                               Executor mainThread, VanillaChecks vanilla) {
        this.reasons = Objects.requireNonNull(reasons, "reasons");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.online = Objects.requireNonNull(online, "online");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.vanilla = Objects.requireNonNull(vanilla, "vanilla");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        var recorded = reasons.take(event.getUniqueId());
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return; // a later listener let them in after all
        }
        notifyStaff(recorded.orElseGet(() -> new JoinRefusal(event.getUniqueId(), event.getName(),
            fromResult(event.getLoginResult()), fromResult(event.getLoginResult()) == JoinRefusal.Reason.OTHER
                ? plain(event.kickMessage()) : null)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        if (event.isAllowed() || !(event.getConnection() instanceof PlayerLoginConnection login)) {
            return;
        }
        PlayerProfile profile = login.getAuthenticatedProfile();
        if (profile == null || profile.getId() == null) {
            return;
        }
        notifyStaff(classify(profile, login.getClientAddress(), event.getKickMessage()));
    }

    /** The vanilla refusal for a profile: ban, IP ban, whitelist, full; else the disconnect text. */
    JoinRefusal classify(PlayerProfile profile, InetSocketAddress address, Component kickMessage) {
        UUID uuid = profile.getId();
        String name = profile.getName();
        String banReason = vanilla.profileBanReason(profile);
        if (banReason != null) {
            return new JoinRefusal(uuid, name, JoinRefusal.Reason.BANNED, banReason);
        }
        if (address != null && vanilla.ipBanned(address)) {
            return new JoinRefusal(uuid, name, JoinRefusal.Reason.BANNED, "IP ban");
        }
        if (vanilla.whitelistRefuses(uuid)) {
            return new JoinRefusal(uuid, name, JoinRefusal.Reason.NOT_WHITELISTED, null);
        }
        if (vanilla.full()) {
            return new JoinRefusal(uuid, name, JoinRefusal.Reason.SERVER_FULL, null);
        }
        return new JoinRefusal(uuid, name, JoinRefusal.Reason.OTHER, plain(kickMessage));
    }

    static JoinRefusal.Reason fromResult(AsyncPlayerPreLoginEvent.Result result) {
        return switch (result) {
            case KICK_BANNED -> JoinRefusal.Reason.BANNED;
            case KICK_WHITELIST -> JoinRefusal.Reason.NOT_WHITELISTED;
            case KICK_FULL -> JoinRefusal.Reason.SERVER_FULL;
            default -> JoinRefusal.Reason.OTHER;
        };
    }

    /** Sends the notice to every online holder of {@value #NODE} on the main thread (throttled per player). */
    void notifyStaff(JoinRefusal refusal) {
        if (!enabled.getAsBoolean() || !throttle.tryAcquire(refusal.uuid())) {
            return;
        }
        LOGGER.info("[KnK Join] " + refusal.describe());
        String line = ChatColor.GOLD + "[Join] " + ChatColor.WHITE + refusal.name() + ChatColor.GRAY
            + " tried to join and was refused: " + ChatColor.YELLOW + refusal.reason().text()
            + (refusal.detail() != null ? ChatColor.GRAY + " (" + refusal.detail() + ")" : "");
        mainThread.execute(() -> {
            for (Player player : online.get()) {
                if (permission.test(player, NODE)) {
                    player.sendMessage(line);
                }
            }
        });
    }

    /** The server's own ban lists, whitelist and player limit. */
    public static VanillaChecks bukkitChecks() {
        return new BukkitVanillaChecks();
    }

    private static String plain(Component message) {
        if (message == null) {
            return null;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(message).replaceAll("\\s+", " ").strip();
        return text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL - 3) + "..." : text;
    }
}
