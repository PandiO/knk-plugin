package net.knightsandkings.knk.paper.realtime;

import java.util.Objects;
import java.util.logging.Logger;

import net.knightsandkings.knk.api.realtime.PrivacyEvents;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.privacy.AccountErasureService;
import net.knightsandkings.knk.core.privacy.ErasedAccount;

/**
 * Realtime S5 (PandiO/knk-workspace#184, DESIGN §7.4): {@code AccountErased} from the game hub goes to
 * {@link AccountErasureService} (kick, then delete what the server keeps), and every (re)connect catches up through
 * {@code GET api/privacy/erasures?since={lastId}} (registered as a resync step). Nothing here runs with
 * {@code realtime.enabled: false}: then the hourly offline-identity check is the only path, as before.
 */
public final class ErasureRealtimeSync {

    private final AccountErasureService service;
    private final Logger logger;

    public ErasureRealtimeSync(AccountErasureService service, Logger logger) {
        this.service = Objects.requireNonNull(service, "service");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Registers the handler (before the client starts: it listens for the registered types). */
    public void register(RealtimeDispatcher dispatcher) {
        dispatcher.register(PrivacyEvents.ACCOUNT_ERASED, 1, Runnable::run, this::onAccountErased);
    }

    /** Resync step: the service runs the catch-up on its own thread, so the main thread doesn't wait for the API. */
    public void resync() {
        service.catchUp();
    }

    void onAccountErased(RealtimeEnvelope envelope) {
        ErasedAccount account = PrivacyEvents.parseAccountErased(envelope);
        if (account == null) {
            // The catch-up on the next (re)connect still finds it.
            logger.warning("[KnK Privacy] Ignored an AccountErased event without a usable erasure id");
            return;
        }
        service.pushed(account);
    }
}
