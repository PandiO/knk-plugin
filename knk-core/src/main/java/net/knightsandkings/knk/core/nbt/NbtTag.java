package net.knightsandkings.knk.core.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft's NBT tag types, just enough to read a player data file, change a few lists and write it
 * back unchanged otherwise (KNG-13). Every tag type round-trips, including ones this plugin never
 * looks at. {@link NbtIo} reads and writes them.
 */
public sealed interface NbtTag {

    /** The tag type id in the binary format. */
    byte id();

    record ByteTag(byte value) implements NbtTag {
        public byte id() { return 1; }
    }

    record ShortTag(short value) implements NbtTag {
        public byte id() { return 2; }
    }

    record IntTag(int value) implements NbtTag {
        public byte id() { return 3; }
    }

    record LongTag(long value) implements NbtTag {
        public byte id() { return 4; }
    }

    record FloatTag(float value) implements NbtTag {
        public byte id() { return 5; }
    }

    record DoubleTag(double value) implements NbtTag {
        public byte id() { return 6; }
    }

    record ByteArrayTag(byte[] value) implements NbtTag {
        public byte id() { return 7; }

        @Override
        public boolean equals(Object o) {
            return o instanceof ByteArrayTag other && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    record StringTag(String value) implements NbtTag {
        public StringTag {
            Objects.requireNonNull(value, "value");
        }

        public byte id() { return 8; }
    }

    /** A list; {@code elementType} is 0 (end) for an empty list with no declared type. */
    record ListTag(byte elementType, List<NbtTag> values) implements NbtTag {
        public ListTag {
            values = new ArrayList<>(values);
            for (NbtTag value : values) {
                if (value.id() != elementType) {
                    throw new IllegalArgumentException("List of type " + elementType + " can't hold a tag of type " + value.id());
                }
            }
        }

        public static ListTag of(byte elementType, List<? extends NbtTag> values) {
            return new ListTag(values.isEmpty() ? elementType : values.get(0).id(), new ArrayList<>(values));
        }

        public byte id() { return 9; }
    }

    /** A compound: named tags in insertion order. Mutable, so a loaded file can be edited in place. */
    record CompoundTag(Map<String, NbtTag> values) implements NbtTag {
        public CompoundTag {
            values = new LinkedHashMap<>(values);
        }

        public CompoundTag() {
            this(new LinkedHashMap<>());
        }

        public byte id() { return 10; }

        public Optional<NbtTag> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        public boolean contains(String key) {
            return values.containsKey(key);
        }

        public CompoundTag put(String key, NbtTag value) {
            values.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public NbtTag remove(String key) {
            return values.remove(key);
        }

        /** A copy whose nested compounds and lists are copies too. */
        public CompoundTag deepCopy() {
            CompoundTag copy = new CompoundTag();
            values.forEach((key, value) -> copy.put(key, NbtTag.deepCopy(value)));
            return copy;
        }

        public Optional<CompoundTag> getCompound(String key) {
            return get(key).filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast);
        }

        public Optional<ListTag> getList(String key) {
            return get(key).filter(ListTag.class::isInstance).map(ListTag.class::cast);
        }

        /** A byte, short or int value as an int. */
        public Optional<Integer> getInt(String key) {
            return get(key).flatMap(tag -> switch (tag) {
                case ByteTag b -> Optional.of((int) b.value());
                case ShortTag s -> Optional.of((int) s.value());
                case IntTag i -> Optional.of(i.value());
                default -> Optional.empty();
            });
        }
    }

    record IntArrayTag(int[] value) implements NbtTag {
        public byte id() { return 11; }

        @Override
        public boolean equals(Object o) {
            return o instanceof IntArrayTag other && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    record LongArrayTag(long[] value) implements NbtTag {
        public byte id() { return 12; }

        @Override
        public boolean equals(Object o) {
            return o instanceof LongArrayTag other && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    static NbtTag deepCopy(NbtTag tag) {
        return switch (tag) {
            case CompoundTag compound -> compound.deepCopy();
            case ListTag list -> new ListTag(list.elementType(), list.values().stream().map(NbtTag::deepCopy).toList());
            case ByteArrayTag bytes -> new ByteArrayTag(bytes.value().clone());
            case IntArrayTag ints -> new IntArrayTag(ints.value().clone());
            case LongArrayTag longs -> new LongArrayTag(longs.value().clone());
            default -> tag; // the scalar records are immutable
        };
    }
}
