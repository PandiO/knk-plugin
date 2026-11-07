package net.knightsandkings.knk.core.survival.mysterybox;

import net.knightsandkings.knk.core.lootbox.LootboxReel;

import java.util.List;

/**
 * The in-world carousel of a roll (DESIGN §8a, modeled on the lootbox reel §3.9): the API's {@code reel} (entry ids drawn
 * with the real odds, the result last) stepped with the lootbox ease-out — fast first, slowing down to
 * {@code maxStepTicks} on the last step. Pure: the Paper side shows {@link #entryAt} every {@link #delayTicks} ticks.
 *
 * @param entryIds   the reel as served (never empty; the last id is the result)
 * @param delayTicks the ticks to wait before showing each entry (same length as {@code entryIds})
 */
public record MysteryBoxReelPlan(List<Integer> entryIds, List<Integer> delayTicks) {

    public MysteryBoxReelPlan {
        if (entryIds == null || entryIds.isEmpty()) throw new IllegalArgumentException("a reel needs at least the result");
        entryIds = List.copyOf(entryIds);
        if (delayTicks == null || delayTicks.size() != entryIds.size()) throw new IllegalArgumentException("one delay per entry");
        delayTicks = List.copyOf(delayTicks);
    }

    /** Plans the carousel for an API reel; a reel that does not end with the result is corrected (the result is appended). */
    public static MysteryBoxReelPlan of(List<Integer> reel, int resultEntryId, int maxStepTicks) {
        List<Integer> ids = reel == null ? List.of() : reel.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty() || ids.get(ids.size() - 1) != resultEntryId) {
            ids = new java.util.ArrayList<>(ids);
            ids.add(resultEntryId);
        }
        // The lootbox planner's ease-out: one delay per step; the strip itself is the API's reel, not the planner's.
        LootboxReel<Integer> timing = LootboxReel.plan(List.of(), resultEntryId, ids.size(), 1, Math.max(1, maxStepTicks), () -> 0.0);
        return new MysteryBoxReelPlan(ids, timing.delays());
    }

    public int steps() {
        return entryIds.size();
    }

    public int entryAt(int step) {
        return entryIds.get(Math.max(0, Math.min(step, entryIds.size() - 1)));
    }

    public int resultEntryId() {
        return entryIds.get(entryIds.size() - 1);
    }

    public boolean isLast(int step) {
        return step >= entryIds.size() - 1;
    }

    /** The whole animation in ticks. */
    public int totalTicks() {
        return delayTicks.stream().mapToInt(Integer::intValue).sum();
    }
}
