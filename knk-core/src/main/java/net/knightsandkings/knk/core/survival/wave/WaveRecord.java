package net.knightsandkings.knk.core.survival.wave;

/**
 * Highest wave reached and last fully completed wave (DESIGN §3): a wipe in wave 10 means reached 10, completed 9.
 * Completed waves drive the base reward, so a failed wave never earns a cleared-wave bonus.
 */
public record WaveRecord(int reached, int completed) {

    public static final WaveRecord NONE = new WaveRecord(0, 0);

    public WaveRecord {
        if (reached < 0 || completed < 0) throw new IllegalArgumentException("wave counts must be >= 0");
        if (completed > reached) throw new IllegalArgumentException("completed " + completed + " > reached " + reached);
    }

    /** A new wave started: it is now reached, not completed. */
    public WaveRecord started(int round) {
        return new WaveRecord(Math.max(reached, round), completed);
    }

    /** The wave {@code round} ended with its queue exhausted and no enemy alive. */
    public WaveRecord completed(int round) {
        return new WaveRecord(Math.max(reached, round), Math.max(completed, round));
    }
}
