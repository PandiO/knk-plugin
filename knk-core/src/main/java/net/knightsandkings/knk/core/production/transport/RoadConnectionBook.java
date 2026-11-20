package net.knightsandkings.knk.core.production.transport;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which structures are connected to their town's road network (DESIGN § 7.6, D5), from the lanes this server measured
 * since it started (PandiO/knk-workspace#681). A structure with at least one reachable lane is {@link
 * RoadConnection#Connected}; one whose every measured lane is unreachable is {@link RoadConnection#Unconnected}; a
 * structure with no measured lane is not judged (it stays as the API has it, {@code Unmeasured} by default).
 * <p>
 * The lane list holds only the pairs due now, so the book keeps every lane's last result: a producer with a reachable
 * lane measured an hour ago is not called unconnected because its other, newly due lane has no road. It also remembers
 * what was last sent, so an unchanged state is not sent again, and the ids the API answered 404 for (warehouses, which
 * are not production structures). Thread-safe.
 */
public final class RoadConnectionBook {

    private final Map<Long, Boolean> lanes = new HashMap<>();
    private final Map<Integer, RoadConnection> sent = new HashMap<>();
    private final Map<Integer, Boolean> notProducers = new HashMap<>();

    public synchronized void record(LaneMeasurement lane) {
        lanes.put(key(lane.fromStructureId(), lane.toStructureId()), lane.reachable());
    }

    /** The judged state of each of {@code structureIds} that has a measured lane, in the given order. */
    public synchronized Map<Integer, RoadConnection> judge(Iterable<Integer> structureIds) {
        Map<Integer, RoadConnection> judged = new LinkedHashMap<>();
        for (Integer id : structureIds) {
            if (id == null || judged.containsKey(id)) {
                continue;
            }
            Boolean any = null;
            for (Map.Entry<Long, Boolean> lane : lanes.entrySet()) {
                long k = lane.getKey();
                if ((int) (k >> 32) == id || (int) k == id) {
                    any = Boolean.TRUE.equals(any) || lane.getValue();
                }
            }
            if (any != null) {
                judged.put(id, any ? RoadConnection.Connected : RoadConnection.Unconnected);
            }
        }
        return judged;
    }

    /** Whether {@code state} differs from what was last sent for {@code structureId} (and it is worth sending). */
    public synchronized boolean shouldSend(int structureId, RoadConnection state) {
        return !notProducers.containsKey(structureId) && sent.get(structureId) != state;
    }

    public synchronized void sent(int structureId, RoadConnection state) {
        sent.put(structureId, state);
    }

    /** The API answered 404: not a production structure (a warehouse); never sent again until a restart. */
    public synchronized void notAProducer(int structureId) {
        notProducers.put(structureId, Boolean.TRUE);
    }

    public synchronized Set<Integer> notProducers() {
        return Set.copyOf(notProducers.keySet());
    }

    public synchronized int lanesKnown() {
        return lanes.size();
    }

    /** A lane counts in both directions: one key for (a, b) and (b, a). */
    private static long key(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
