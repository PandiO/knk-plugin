package net.knightsandkings.knk.api.client;

import java.util.concurrent.ExecutorService;

/** Test access to the client's package-private executor wrapper. */
public final class KnkApiClientTestAccess {

    private KnkApiClientTestAccess() {
    }

    public static ExecutorService wrap(ExecutorService executor) {
        return CorrelationPropagatingExecutorService.wrap(executor);
    }
}
