package net.knightsandkings.knk.core.survival.spawn;

/** One entry of the special-wave schedule (S14, S53, §10 "Special-wave schedule"). */
public sealed interface SpecialWaveEntry {

    String profileId();

    /** Whether this entry selects wave {@code wave}. */
    boolean matches(int wave);

    /**
     * A recurring entry: every {@code interval} waves from {@code startWave} on (wave 5, 10, 15 for interval 5 and
     * start 5). {@code priority} orders conflicting recurring entries: the lowest value wins (priority 1 beats 2);
     * two recurring entries that can hit the same wave must have different priorities or readiness fails.
     */
    record Recurring(int interval, int startWave, String profileId, int priority) implements SpecialWaveEntry {
        public Recurring {
            if (interval < 1) throw new IllegalArgumentException("interval must be >= 1");
            if (startWave < 1) throw new IllegalArgumentException("startWave must be >= 1");
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId is required");
        }

        /** "Every nth wave": waves n, 2n, 3n… */
        public static Recurring every(int interval, String profileId, int priority) {
            return new Recurring(interval, interval, profileId, priority);
        }

        @Override
        public boolean matches(int wave) {
            return wave >= startWave && (wave - startWave) % interval == 0;
        }

        /** Whether some wave is selected by both entries (Chinese remainder: offsets agree modulo gcd). */
        public boolean conflictsWith(Recurring other) {
            int g = gcd(interval, other.interval);
            return Math.floorMod(startWave - other.startWave, g) == 0;
        }

        private static int gcd(int a, int b) {
            while (b != 0) {
                int t = a % b;
                a = b;
                b = t;
            }
            return a;
        }
    }

    /** An explicit wave-number entry; it beats every recurring entry for that wave (S53). */
    record Explicit(int wave, String profileId) implements SpecialWaveEntry {
        public Explicit {
            if (wave < 1) throw new IllegalArgumentException("wave must be >= 1");
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId is required");
        }

        @Override
        public boolean matches(int wave) {
            return this.wave == wave;
        }
    }
}
