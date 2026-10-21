package net.knightsandkings.knk.core.duel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One arena's battlegrounds, free or busy (DESIGN D13: one match per battleground). Battlegrounds are
 * handed out in the arena's order ({@code SortOrder}); only enabled ones are passed in. A refresh of
 * the arena's settings keeps busy battlegrounds busy until their match releases them, even if the
 * refresh removed them.
 */
public final class BattlegroundAllocator {
    private final Set<Integer> battlegrounds = new LinkedHashSet<>();
    private final Map<Integer, String> busy = new HashMap<>();

    /** @param enabledBattlegroundIds the arena's enabled battlegrounds, in sort order */
    public BattlegroundAllocator(List<Integer> enabledBattlegroundIds) {
        setBattlegrounds(enabledBattlegroundIds);
    }

    /** Arena settings changed: the new enabled list, in sort order. */
    public void setBattlegrounds(List<Integer> enabledBattlegroundIds) {
        battlegrounds.clear();
        battlegrounds.addAll(enabledBattlegroundIds);
    }

    /** The first free battleground, now busy with {@code matchToken}; empty when all are busy. */
    public Optional<Integer> allocate(String matchToken) {
        Objects.requireNonNull(matchToken, "matchToken");
        Integer held = battlegroundOf(matchToken);
        if (held != null) return Optional.of(held);
        for (Integer id : battlegrounds) {
            if (!busy.containsKey(id)) {
                busy.put(id, matchToken);
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** Frees the match's battleground; returns it, or null when it held none. */
    public Integer release(String matchToken) {
        Integer id = battlegroundOf(matchToken);
        if (id != null) busy.remove(id);
        return id;
    }

    public Integer battlegroundOf(String matchToken) {
        for (Map.Entry<Integer, String> e : busy.entrySet()) {
            if (e.getValue().equals(matchToken)) return e.getKey();
        }
        return null;
    }

    /** The match on a battleground, or null when it's free. */
    public String matchOn(int battlegroundId) {
        return busy.get(battlegroundId);
    }

    public boolean hasFree() {
        return freeCount() > 0;
    }

    public int freeCount() {
        int free = 0;
        for (Integer id : battlegrounds) {
            if (!busy.containsKey(id)) free++;
        }
        return free;
    }

    public int busyCount() {
        return busy.size();
    }

    /** Enabled battlegrounds ("5/8 battlegrounds busy"). */
    public int totalCount() {
        return battlegrounds.size();
    }

    public List<Integer> battlegrounds() {
        return new ArrayList<>(battlegrounds);
    }
}
