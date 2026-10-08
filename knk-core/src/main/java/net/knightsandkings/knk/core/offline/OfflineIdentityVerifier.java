package net.knightsandkings.knk.core.offline;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore.Identity;

/**
 * The cleanup pass of the offline security cache (KNG-58): re-asks the API about every identity it
 * hasn't confirmed for a while.
 * <ul>
 *   <li>The API knows no account for the UUID (404) - erased (KNG-34: the UUID is cleared), or
 *       never existed: everything about it is deleted.</li>
 *   <li>The UUID belongs to another account now (a returning player after an erasure): the old
 *       account's data is deleted and the new one recorded.</li>
 *   <li>Same account: refreshed (mode, freeze, name, confirmation time).</li>
 *   <li>The API can't be reached: the pass stops and nothing changes; the max age still applies.</li>
 * </ul>
 * Sequential, one lookup at a time, so it never floods the API. Run it off the main thread.
 */
public final class OfflineIdentityVerifier {

    private static final Logger LOGGER = Logger.getLogger(OfflineIdentityVerifier.class.getName());

    /** What one pass did. */
    public record Result(int checked, int refreshed, int forgotten, boolean apiUnreachable) {
    }

    private final OfflineSecurityStore store;
    /** UUID to account; completes with null when the API knows no such user, exceptionally when unreachable. */
    private final Function<UUID, CompletableFuture<UserSummary>> lookup;

    public OfflineIdentityVerifier(OfflineSecurityStore store, Function<UUID, CompletableFuture<UserSummary>> lookup) {
        this.store = store;
        this.lookup = lookup;
    }

    /** Re-check every identity unconfirmed for at least {@code olderThan}. Blocks until done. */
    public Result run(Duration olderThan) {
        List<Identity> due = store.identitiesUnverifiedFor(olderThan);
        int checked = 0;
        int refreshed = 0;
        int forgotten = 0;
        for (Identity identity : due) {
            UserSummary current;
            try {
                current = lookup.apply(identity.uuid()).join();
            } catch (RuntimeException e) {
                LOGGER.fine("[KnK Offline] Identity check stopped, API unreachable: " + e.getMessage());
                return new Result(checked, refreshed, forgotten, true);
            }
            checked++;
            if (current == null || current.id() == null || !identity.uuid().equals(current.uuid())) {
                store.forgetUser(identity.uuid());
                forgotten++;
                continue;
            }
            if (current.id() != identity.userId()) {
                forgotten++;
            } else {
                refreshed++;
            }
            store.recordUser(current);
        }
        return new Result(checked, refreshed, forgotten, false);
    }
}
