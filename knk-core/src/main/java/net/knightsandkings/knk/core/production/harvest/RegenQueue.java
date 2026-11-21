package net.knightsandkings.knk.core.production.harvest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;

/**
 * The regeneration queue (PRODUCTION_KINDS § 7, PandiO/knk-workspace#134). Mutated on the main thread; the
 * {@link #overlay()} (blocks waiting to regenerate count as their original in surveys, L12) and
 * {@link #regenerating} may be read from any thread.
 * <ol>
 *   <li>A due job is restored only when all its chunks are loaded (a restore never loads a chunk); otherwise it waits for
 *       {@link #chunkLoaded} of one of them.</li>
 *   <li>Restores are idempotent and safe: every position must still hold its placeholder's material, else the job is
 *       dropped and the world left as it is (a changed block is never overwritten); a living entity in one of the blocks
 *       postpones the job by {@code retryMillis}.</li>
 *   <li>At most {@code maxBlocks} blocks per {@link #tick} (the main-thread budget); the rest stays due.</li>
 * </ol>
 */
public final class RegenQueue {

    /** What one tick did. */
    public record TickResult(int restored, int dropped, int postponed, int waiting) {
        public static final TickResult NONE = new TickResult(0, 0, 0, 0);

        public boolean any() {
            return restored + dropped + postponed + waiting > 0;
        }
    }

    private record Due(long at, String id) {
    }

    private final Map<String, RegenJob> jobs = new LinkedHashMap<>();
    private final PriorityQueue<Due> due = new PriorityQueue<>((a, b) -> Long.compare(a.at(), b.at()));
    /** world → chunk → job ids waiting for that chunk to load. */
    private final Map<String, Map<Long, Set<String>>> waiting = new HashMap<>();
    /** world → position → original material (thread-safe for surveys). */
    private final Map<String, Map<Long, String>> originals = new ConcurrentHashMap<>();
    private final long retryMillis;
    private boolean dirty;
    private long restoredTotal;
    private long droppedTotal;
    private Consumer<String> log = message -> { };

    public RegenQueue(long retryMillis) {
        this.retryMillis = Math.max(1000, retryMillis);
    }

    public void setLog(Consumer<String> log) {
        this.log = log == null ? message -> { } : log;
    }

    /** Adds a job (replacing one with the same id). */
    public void add(RegenJob job) {
        remove(job.id());
        jobs.put(job.id(), job);
        Map<Long, String> world = originals.computeIfAbsent(job.world(), w -> new ConcurrentHashMap<>());
        for (RegenJob.Block b : job.blocks()) {
            world.put(PlaceholderOverlay.key(b.x(), b.y(), b.z()), b.originalMaterial());
        }
        due.add(new Due(job.restoreAt(), job.id()));
        dirty = true;
    }

    public RegenJob get(String id) {
        return jobs.get(id);
    }

    public Collection<RegenJob> jobs() {
        return List.copyOf(jobs.values());
    }

    public int size() {
        return jobs.size();
    }

    /** Moves the restore time of every job of a report entry to {@code createdAt + regenSeconds} (the API's answer). */
    public void setRegenSeconds(String entryKey, int regenSeconds) {
        if (entryKey == null) {
            return;
        }
        for (RegenJob job : List.copyOf(jobs.values())) {
            if (entryKey.equals(job.entryKey())) {
                long at = job.createdAt() + Math.max(0, regenSeconds) * 1000L;
                if (at != job.restoreAt()) {
                    jobs.put(job.id(), job.withRestoreAt(at));
                    due.add(new Due(at, job.id()));
                    dirty = true;
                }
            }
        }
    }

    /** The overlay surveys read (PlaceholderOverlay, L12): the original material at a position held by a job. */
    public PlaceholderOverlay overlay() {
        return (world, x, y, z) -> {
            Map<Long, String> w = originals.get(world);
            return w == null ? null : w.get(PlaceholderOverlay.key(x, y, z));
        };
    }

    /** Whether a job holds (x, y, z). */
    public boolean regenerating(String world, int x, int y, int z) {
        Map<Long, String> w = originals.get(world);
        return w != null && w.containsKey(PlaceholderOverlay.key(x, y, z));
    }

    /** A chunk loaded: its due jobs are tried again on the next tick. */
    public void chunkLoaded(String world, int chunkX, int chunkZ, long nowMillis) {
        Map<Long, Set<String>> w = waiting.get(world);
        Set<String> ids = w == null ? null : w.remove(HarvestAreaIndex.chunkKey(chunkX, chunkZ));
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            RegenJob job = jobs.get(id);
            if (job != null) {
                due.add(new Due(Math.min(nowMillis, job.restoreAt()), id));
            }
        }
    }

    /** Restores what is due, at most {@code maxBlocks} blocks. */
    public TickResult tick(RegenWorld world, long nowMillis, int maxBlocks) {
        int restored = 0;
        int dropped = 0;
        int postponed = 0;
        int waitingNow = 0;
        int budget = Math.max(1, maxBlocks);
        while (!due.isEmpty() && due.peek().at() <= nowMillis) {
            Due head = due.peek();
            RegenJob job = jobs.get(head.id());
            if (job == null || job.restoreAt() > nowMillis) {
                due.poll(); // stale entry (job gone, or moved later and queued again at its new time)
                continue;
            }
            if (job.blocks().size() > budget && restored + dropped > 0) {
                break; // next tick
            }
            due.poll();
            if (!chunksLoaded(world, job)) {
                Map<Long, Set<String>> w = waiting.computeIfAbsent(job.world(), x -> new HashMap<>());
                for (Long chunk : job.chunks()) {
                    w.computeIfAbsent(chunk, c -> new HashSet<>()).add(job.id());
                }
                waitingNow++;
                continue;
            }
            budget -= job.blocks().size();
            switch (restore(world, job)) {
                case RESTORED -> {
                    restored++;
                    restoredTotal++;
                    remove(job.id());
                }
                case CHANGED -> {
                    dropped++;
                    droppedTotal++;
                    remove(job.id());
                }
                case OCCUPIED -> {
                    postponed++;
                    RegenJob later = job.withRestoreAt(nowMillis + retryMillis);
                    jobs.put(later.id(), later);
                    due.add(new Due(later.restoreAt(), later.id()));
                    dirty = true;
                }
            }
            if (budget <= 0) {
                break;
            }
        }
        return restored + dropped + postponed + waitingNow == 0 ? TickResult.NONE
                : new TickResult(restored, dropped, postponed, waitingNow);
    }

    private enum Restore { RESTORED, CHANGED, OCCUPIED }

    private Restore restore(RegenWorld world, RegenJob job) {
        for (RegenJob.Block b : job.blocks()) {
            String now = BlockStates.material(world.blockData(job.world(), b.x(), b.y(), b.z()));
            if (now == null || !now.equals(b.placeholderMaterial())) {
                log.accept("regeneration job " + job.id() + " dropped: " + b.x() + " " + b.y() + " " + b.z() + " holds "
                        + now + ", not the placeholder " + b.placeholderMaterial());
                return Restore.CHANGED;
            }
        }
        for (RegenJob.Block b : job.blocks()) {
            if (world.occupied(job.world(), b.x(), b.y(), b.z())) {
                return Restore.OCCUPIED;
            }
        }
        for (RegenJob.Block b : job.blocks()) {
            world.set(job.world(), b.x(), b.y(), b.z(), b.original());
        }
        return Restore.RESTORED;
    }

    private static boolean chunksLoaded(RegenWorld world, RegenJob job) {
        for (Long chunk : job.chunks()) {
            int cx = (int) (chunk >> 32);
            int cz = (int) (long) chunk;
            if (!world.chunkLoaded(job.world(), cx, cz)) {
                return false;
            }
        }
        return true;
    }

    private void remove(String id) {
        RegenJob job = jobs.remove(id);
        if (job == null) {
            return;
        }
        dirty = true;
        Map<Long, String> w = originals.get(job.world());
        if (w != null) {
            for (RegenJob.Block b : job.blocks()) {
                w.remove(PlaceholderOverlay.key(b.x(), b.y(), b.z()));
            }
        }
        Map<Long, Set<String>> wait = waiting.get(job.world());
        if (wait != null) {
            for (Long chunk : job.chunks()) {
                Set<String> ids = wait.get(chunk);
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) {
                        wait.remove(chunk);
                    }
                }
            }
        }
    }

    /** Whether something changed since the last {@link #encode}. */
    public boolean dirty() {
        return dirty;
    }

    /** Every job, one line each (see {@link RegenJob#encode}); clears {@link #dirty}. */
    public List<String> encode() {
        dirty = false;
        List<String> lines = new ArrayList<>(jobs.size());
        for (RegenJob job : jobs.values()) {
            lines.add(job.encode());
        }
        return lines;
    }

    /** Loads saved jobs (broken lines are skipped and reported to the log); returns how many were loaded. */
    public int load(List<String> lines) {
        int loaded = 0;
        for (String line : lines) {
            if (line == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                add(RegenJob.decode(line));
                loaded++;
            } catch (IllegalArgumentException e) {
                log.accept("skipped a broken regeneration job line: " + e.getMessage());
            }
        }
        dirty = false;
        return loaded;
    }

    public long restoredTotal() {
        return restoredTotal;
    }

    public long droppedTotal() {
        return droppedTotal;
    }

    /** Jobs waiting for a chunk to load. */
    public int waitingCount() {
        Set<String> ids = new HashSet<>();
        waiting.values().forEach(w -> w.values().forEach(ids::addAll));
        return ids.size();
    }
}
