package net.knightsandkings.knk.paper.admission;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import net.knightsandkings.knk.core.admission.AdmissionPolicy;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Decision;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Deny;
import net.knightsandkings.knk.core.domain.admission.AdmissionResult;
import net.knightsandkings.knk.core.ports.api.AdmissionApi;

/**
 * The admission gate (invitations DESIGN §5.2, plan §6.2). With {@code admission.mode: invite}, every connection
 * that isn't an operator's is checked with the API at pre-login - after Mojang authentication, so the UUID is
 * genuine - and a player without an admitted account is turned away with a claim code on the disconnect screen.
 *
 * <p>LOWEST, so it runs before {@code KnkWorldsListener} (LOW), {@code PlayerListener} (NORMAL, which creates the
 * account) and {@code HubAdmissionListener} (HIGH); all three skip a login that is no longer ALLOWED. The event is
 * async, so the call blocks this connection's thread for at most {@code admission.api-timeout-ms}. A timeout or an
 * API error falls back to the offline security store: a player the server knows gets in, a stranger is told to
 * retry. In {@code whitelist} (default) and {@code open} mode the listener does nothing.
 *
 * <p>The claim code is a credential: it goes on the disconnect screen only, never in the log.
 */
public final class AdmissionListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(AdmissionListener.class.getName());

    private final AdmissionSettings settings;
    private final AdmissionApi api;
    private final Predicate<UUID> operator;
    private final Predicate<UUID> knownOffline;
    private final Clock clock;

    /**
     * @param operator     whether a UUID is a server operator (operators bypass the gate)
     * @param knownOffline whether the offline security store holds a fresh identity for a UUID
     */
    public AdmissionListener(AdmissionSettings settings, AdmissionApi api, Predicate<UUID> operator,
                             Predicate<UUID> knownOffline, Clock clock) {
        this.settings = settings != null ? settings : AdmissionSettings.defaults();
        this.api = api;
        this.operator = operator != null ? operator : uuid -> false;
        this.knownOffline = knownOffline != null ? knownOffline : uuid -> false;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public AdmissionSettings settings() {
        return settings;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        Decision decision = evaluate(uuid, name);
        if (decision instanceof Deny deny) {
            LOGGER.info("[KnK Admission] Refused " + name + " (" + uuid + "): " + deny.messageKey());
            Map<String, String> placeholders = new HashMap<>(deny.placeholders());
            placeholders.put("url", AdmissionMessages.url(settings.linkUrl()));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                AdmissionMessages.screen(settings.message(deny.messageKey()), placeholders));
        }
    }

    /** The gate's decision for one connection; asks the API only in invite mode and never for an operator. */
    Decision evaluate(UUID uuid, String name) {
        boolean isOperator = settings.mode() == AdmissionPolicy.Mode.INVITE && operator.test(uuid);
        if (!AdmissionPolicy.needsCheck(settings.mode(), isOperator)) {
            return AdmissionPolicy.decide(settings.mode(), isOperator, null, false, clock.instant());
        }
        AdmissionResult result = ask(uuid, name);
        boolean known = result == null && knownOffline.test(uuid);
        return AdmissionPolicy.decide(settings.mode(), false, result, known, clock.instant());
    }

    /** The API's answer, or null when it can't be had within the timeout. */
    private AdmissionResult ask(UUID uuid, String name) {
        CompletableFuture<AdmissionResult> future;
        try {
            future = api.check(uuid, name);
        } catch (RuntimeException e) {
            LOGGER.warning("[KnK Admission] Can't check " + name + " (" + uuid + "): " + e.getMessage());
            return null;
        }
        try {
            return future.get(settings.apiTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOGGER.warning("[KnK Admission] The API didn't answer for " + name + " (" + uuid + ") within "
                + settings.apiTimeoutMs() + " ms; using the offline store.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.warning("[KnK Admission] Can't check " + name + " (" + uuid + "): " + cause.getMessage()
                + "; using the offline store.");
        }
        return null;
    }
}
