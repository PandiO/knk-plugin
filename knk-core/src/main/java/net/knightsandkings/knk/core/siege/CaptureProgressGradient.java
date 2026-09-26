package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The objective banner's capture-progress gradient (v1 {@code Objective.setBannerStages} /
 * {@code setCurrentCapturePoints}, v2 {@code MGObjective.PATTERNS_DEF}): eight stages from "held"
 * to "captured", drawn from the holder's and the leading attacker's banner base colours.
 * <p>
 * Stage = {@code ceil(points / capturePoints × 8)} as in legacy, with points 0 → stage 1 and a
 * non-zero stage 1 → 2; the banner index is {@code stage − 1}:
 * <ul>
 *   <li>0 (captured): the attacker's colour, plain;</li>
 *   <li>1–6: holder base + attacker {@code gradient} + (index − 1) holder {@code gradient_up}
 *       layers, so the attacker's colour recedes as the points climb;</li>
 *   <li>7 (full or nearly): the holder's colour, plain.</li>
 * </ul>
 * v1 hard-coded a RED base on stage 6; v3 uses the holder's colour there too. The result is a
 * {@link BannerPatternSpec}, the same form team banners and the Phase 8b menus use.
 */
public final class CaptureProgressGradient {
    private CaptureProgressGradient() {}

    public static final int STAGES = 8;
    private static final String GRADIENT = "minecraft:gradient";
    private static final String GRADIENT_UP = "minecraft:gradient_up";

    /** The banner index 0 (captured) … 7 (full). */
    public static int index(int points, int capturePoints) {
        if (capturePoints <= 0 || points <= 0) return 0;
        int stage = (int) Math.ceil((double) Math.min(points, capturePoints) / capturePoints * STAGES);
        if (stage == 1) stage = 2;
        return Math.max(0, Math.min(STAGES - 1, stage - 1));
    }

    /**
     * @param holderColor   the holding team's banner base colour (a dye colour name); white when null
     * @param attackerColor the leading attacker's base colour; the holder's when null
     */
    public static BannerPatternSpec banner(int index, String holderColor, String attackerColor) {
        String holder = holderColor == null ? "WHITE" : holderColor;
        String attacker = attackerColor == null ? holder : attackerColor;
        if (index <= 0) return BannerPatternSpec.parse(attacker + "|");
        if (index >= STAGES - 1) return BannerPatternSpec.parse(holder + "|");
        StringBuilder raw = new StringBuilder(holder).append('|').append(GRADIENT).append(':').append(attacker);
        for (int i = 1; i < index; i++) {
            raw.append(',').append(GRADIENT_UP).append(':').append(holder);
        }
        return BannerPatternSpec.parse(raw.toString());
    }

    /**
     * The objective banner to show (smoke test 2026-09-26): the holder's <b>full team banner</b> while
     * the objective is fully held, and for good once it is captured for good (the capturer is then the
     * holder); the attacker's full banner at the moment of capture; the base-colour gradient in between
     * (the v2 transition from holding to attacking banner). A missing design falls back to the plain
     * team colour.
     *
     * @param holderDesign   the holding team's banner, or null
     * @param attackerDesign the leading attacker's banner, or null
     */
    public static BannerPatternSpec objectiveBanner(int points, int capturePoints, boolean capturedFinal,
                                                    BannerPatternSpec holderDesign, String holderColor,
                                                    BannerPatternSpec attackerDesign, String attackerColor) {
        int index = capturedFinal ? STAGES - 1 : index(points, capturePoints);
        if (index >= STAGES - 1) return full(holderDesign, holderColor);
        if (index <= 0) return full(attackerDesign, attackerColor != null ? attackerColor : holderColor);
        return banner(index, holderColor, attackerColor);
    }

    private static BannerPatternSpec full(BannerPatternSpec design, String color) {
        if (design != null && !design.isEmpty() && design.baseColor() != null) return design;
        return banner(STAGES - 1, color, color);
    }

    /**
     * The attacking team to colour an objective with: the enemy team with the most members inside
     * the radius (ties: first in scenario order); with no attacker present, the previous one; with
     * none ever, the first enemy team in scenario order.
     *
     * @param teamOrder the scenario's team ids in order
     */
    public static Integer leadingAttacker(List<Presence> present, int holderTeamId, AllianceResolver alliances,
                                          List<Integer> teamOrder, Integer previous) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (present != null) {
            for (Presence p : present) {
                if (alliances.areEnemies(p.teamId(), holderTeamId)) counts.merge(p.teamId(), 1, Integer::sum);
            }
        }
        Integer best = null;
        int bestCount = 0;
        for (Integer teamId : teamOrder) {
            int c = counts.getOrDefault(teamId, 0);
            if (c > bestCount) {
                best = teamId;
                bestCount = c;
            }
        }
        if (best != null) return best;
        if (previous != null && alliances.areEnemies(previous, holderTeamId)) return previous;
        return teamOrder.stream().filter(t -> alliances.areEnemies(t, holderTeamId)).findFirst().orElse(null);
    }
}
