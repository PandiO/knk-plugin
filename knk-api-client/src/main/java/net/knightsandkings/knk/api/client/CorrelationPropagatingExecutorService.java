package net.knightsandkings.knk.api.client;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import net.knightsandkings.knk.core.telemetry.TelemetryCorrelation;

/**
 * Wraps the API client's worker pool so a call started inside {@link TelemetryCorrelation#run} sends
 * that action's {@code X-Correlation-Id} from the worker thread (KNG-34 link 6). Without an active
 * correlation the task runs unchanged. Lifecycle calls go to the wrapped pool.
 */
final class CorrelationPropagatingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final Executor propagating;

    private CorrelationPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
        this.propagating = TelemetryCorrelation.propagating(delegate);
    }

    static ExecutorService wrap(ExecutorService executor) {
        if (executor == null || executor instanceof CorrelationPropagatingExecutorService) {
            return executor;
        }
        return new CorrelationPropagatingExecutorService(executor);
    }

    @Override
    public void execute(Runnable command) {
        propagating.execute(command);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
