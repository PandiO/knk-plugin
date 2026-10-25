package net.knightsandkings.knk.paper.gates;

import org.bukkit.scheduler.BukkitRunnable;

/**
 * Keeps the gates' damage cracks (see {@link GateCrackVisuals}) in step with their HP and state: every run
 * sends what changed (a new crack stage, a gate that opened or closed), and every
 * {@value #RESEND_EVERY} runs re-sends all of them, because the client drops a block-break animation after
 * about 20 seconds without an update.
 */
public class GateCrackVisualsTask extends BukkitRunnable {
    /** How often the task runs, in ticks. */
    public static final long PERIOD_TICKS = 5L;
    /** Every this many runs (about 5 seconds) the cracks are sent again. */
    static final int RESEND_EVERY = 20;

    private final GateCrackVisuals visuals;
    private int runs;

    public GateCrackVisualsTask(GateCrackVisuals visuals) {
        this.visuals = visuals;
    }

    @Override
    public void run() {
        runs++;
        boolean resend = runs % RESEND_EVERY == 0;
        visuals.syncAll(resend);
    }
}
