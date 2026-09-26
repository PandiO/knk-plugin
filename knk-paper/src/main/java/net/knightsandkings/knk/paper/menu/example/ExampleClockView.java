package net.knightsandkings.knk.paper.menu.example;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * InventoryMenu Phase 9 demo root {@code $exampleClock$} (E2): a ticking value
 * so the {@code example.domain} menus can show {@code AutoRefreshTicks} (E4)
 * re-resolving a {@code Ttl} binding while the menu stays open.
 */
public final class ExampleClockView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final long enabledAtMillis;

    ExampleClockView(long enabledAtMillis) {
        this.enabledAtMillis = enabledAtMillis;
    }

    /** Whole seconds since the plugin enabled. */
    public long getSeconds() {
        return (System.currentTimeMillis() - enabledAtMillis) / 1000L;
    }

    /** Server wall-clock time, HH:mm:ss. */
    public String getTime() {
        return LocalTime.now().format(TIME);
    }
}
