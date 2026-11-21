package net.knightsandkings.knk.core.production.harvest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker's shares booked while the player was offline (PRODUCTION_KINDS § 6.2: the share goes to the player's inventory),
 * paid when they join. Per player and blueprint; persisted on disk ({@link #encode}). Main thread.
 */
public final class OwedPay {

    private final Map<UUID, Map<Integer, Long>> owed = new LinkedHashMap<>();
    private boolean dirty;

    public void add(UUID player, int blueprintId, long units) {
        if (units <= 0) {
            return;
        }
        owed.computeIfAbsent(player, p -> new LinkedHashMap<>()).merge(blueprintId, units, Long::sum);
        dirty = true;
    }

    /** Everything owed to {@code player}, removed from the list. */
    public Map<Integer, Long> take(UUID player) {
        Map<Integer, Long> pay = owed.remove(player);
        if (pay == null) {
            return Map.of();
        }
        dirty = true;
        return pay;
    }

    public int players() {
        return owed.size();
    }

    public boolean dirty() {
        return dirty;
    }

    /** {@code uuid TAB blueprintId TAB units} per line. */
    public List<String> encode() {
        dirty = false;
        List<String> lines = new ArrayList<>();
        owed.forEach((player, items) -> items.forEach((blueprint, units) ->
                lines.add(player + "\t" + blueprint + "\t" + units)));
        return lines;
    }

    public int load(List<String> lines) {
        int loaded = 0;
        for (String line : lines) {
            String[] f = line == null ? new String[0] : line.split("\t");
            if (f.length != 3) {
                continue;
            }
            try {
                add(UUID.fromString(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2]));
                loaded++;
            } catch (IllegalArgumentException e) {
                // a broken line is skipped
            }
        }
        dirty = false;
        return loaded;
    }
}
