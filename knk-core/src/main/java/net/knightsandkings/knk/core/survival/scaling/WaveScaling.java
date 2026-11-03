package net.knightsandkings.knk.core.survival.scaling;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Enemy count and stat multipliers per round (DESIGN §7, S21, S29, S30, S54, S55).
 * <ul>
 *   <li>Count: {@code floor(countCurve(r) × (1 + p·(players − 1)))}, never below 1, capped by the count cap.</li>
 *   <li>Stat: {@code 1 + w·(statCurve(r) − 1)} with b = 1, so round 1 is exactly 1; optionally × the per-player
 *       factor; then capped by the stat cap. Multipliers never drop below 0 (a negative curve is clamped).</li>
 * </ul>
 * The multipliers are inputs for the NPC platform's modifier pipeline (§5b); attack speed and walking speed are
 * applied, bounded and capped there, not here.
 */
public final class WaveScaling {
    private final WaveScalingSettings settings;

    public WaveScaling(WaveScalingSettings settings) {
        this.settings = settings;
    }

    public WaveScalingSettings settings() {
        return settings;
    }

    /** Enemies in {@code round} with {@code playersAtRoundStart} players (ghosts included). */
    public int enemyCount(int round, int playersAtRoundStart) {
        double raw = settings.countCurve().value(round) * settings.perPlayer().multiplier(playersAtRoundStart);
        int count = (int) Math.floor(Math.max(1, raw));
        if (settings.countCap() > 0) count = Math.min(count, settings.countCap());
        return Math.max(1, count);
    }

    /** The multiplier of one stat in {@code round}. */
    public double statMultiplier(ScaledStat stat, int round, int playersAtRoundStart) {
        double curve = settings.statCurve().value(round);
        double weighted = 1 + settings.statWeights().weight(stat) * (curve - 1);
        if (settings.perPlayer().applyToStats()) {
            weighted *= settings.perPlayer().multiplier(playersAtRoundStart);
        }
        return Math.max(0, Math.min(settings.statCap(), weighted));
    }

    /** All stat multipliers of a round. */
    public Map<ScaledStat, Double> statMultipliers(int round, int playersAtRoundStart) {
        Map<ScaledStat, Double> result = new EnumMap<>(ScaledStat.class);
        for (ScaledStat stat : ScaledStat.values()) {
            result.put(stat, statMultiplier(stat, round, playersAtRoundStart));
        }
        return result;
    }

    public RoundPreview preview(int round, int playersAtRoundStart) {
        return new RoundPreview(round, playersAtRoundStart, enemyCount(round, playersAtRoundStart),
            statMultipliers(round, playersAtRoundStart));
    }

    /** Previews rounds {@code fromRound..toRound} inclusive for the editor (§11). */
    public List<RoundPreview> preview(int fromRound, int toRound, int playersAtRoundStart) {
        if (fromRound < 1 || toRound < fromRound) throw new IllegalArgumentException("invalid round range");
        List<RoundPreview> previews = new ArrayList<>();
        for (int round = fromRound; round <= toRound; round++) {
            previews.add(preview(round, playersAtRoundStart));
        }
        return List.copyOf(previews);
    }
}
