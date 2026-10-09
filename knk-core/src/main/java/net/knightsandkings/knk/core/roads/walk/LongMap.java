package net.knightsandkings.knk.core.roads.walk;

import java.util.Arrays;

/**
 * A minimal open-addressing map from {@code long} keys to objects (KNG-51 §5: "long-keyed maps") for
 * the walk search's per-request state. {@code HashMap<Long, V>} boxes every key, and
 * {@code Long.hashCode} of packed {@code BlockKey}s ({@code x} high bits XOR {@code z} low bits)
 * collides heavily over a small area, which the 96×96 timing showed as treeified buckets. Keys are
 * spread with the MurmurHash3 finaliser; linear probing; no removal. Not thread-safe.
 */
final class LongMap<V> {

    private static final int MIN_CAPACITY = 16;

    private long[] keys;
    private Object[] values;
    private boolean[] used;
    private int size;
    private int mask;
    private int resizeAt;

    LongMap(int expected) {
        int capacity = MIN_CAPACITY;
        while (capacity * 0.6 < expected) {
            capacity <<= 1;
        }
        allocate(capacity);
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new Object[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * 0.6);
    }

    private static int spread(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) key;
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        int i = spread(key) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                return (V) values[i];
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    void put(long key, V value) {
        int i = spread(key) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        if (++size > resizeAt) {
            grow();
        }
    }

    int size() {
        return size;
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        boolean[] oldUsed = used;
        allocate(keys.length << 1);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], uncheckedCast(oldValues[i]));
            }
        }
        Arrays.fill(oldValues, null);
    }

    @SuppressWarnings("unchecked")
    private V uncheckedCast(Object value) {
        return (V) value;
    }
}
