package net.knightsandkings.knk.core.survival.spawn;

/** Match-unique enemy ids for planned enemies (a stuck respawn keeps its id, §3c). */
public final class EnemyIdSequence {
    private long next;

    public EnemyIdSequence() {
        this(1);
    }

    public EnemyIdSequence(long first) {
        this.next = first;
    }

    public long next() {
        return next++;
    }

    /** The id the next call to {@link #next()} returns. */
    public long peek() {
        return next;
    }
}
