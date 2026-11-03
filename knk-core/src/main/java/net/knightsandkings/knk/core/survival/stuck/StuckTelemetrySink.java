package net.knightsandkings.knk.core.survival.stuck;

/** Receives stuck-enemy telemetry (DESIGN §3c, §11); the Paper adapter logs it. */
@FunctionalInterface
public interface StuckTelemetrySink {
    void record(StuckEvent event);

    static StuckTelemetrySink none() {
        return event -> { };
    }
}
