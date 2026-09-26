package net.knightsandkings.knk.core.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.knightsandkings.knk.core.nbt.NbtTag.ByteArrayTag;
import net.knightsandkings.knk.core.nbt.NbtTag.ByteTag;
import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.NbtTag.DoubleTag;
import net.knightsandkings.knk.core.nbt.NbtTag.FloatTag;
import net.knightsandkings.knk.core.nbt.NbtTag.IntArrayTag;
import net.knightsandkings.knk.core.nbt.NbtTag.IntTag;
import net.knightsandkings.knk.core.nbt.NbtTag.ListTag;
import net.knightsandkings.knk.core.nbt.NbtTag.LongArrayTag;
import net.knightsandkings.knk.core.nbt.NbtTag.LongTag;
import net.knightsandkings.knk.core.nbt.NbtTag.ShortTag;
import net.knightsandkings.knk.core.nbt.NbtTag.StringTag;

/**
 * Reads and writes gzip-compressed NBT with a named root compound - the format of the server's
 * {@code playerdata/<uuid>.dat} files and of Paper's {@code ItemStack.serializeAsBytes()} (both use
 * Minecraft's {@code NbtIo.writeCompressed}) - KNG-13. Big-endian, strings in Java's modified UTF-8,
 * as {@link DataOutput#writeUTF} writes them.
 */
public final class NbtIo {

    private static final int MAX_DEPTH = 512;

    private NbtIo() {
    }

    public static CompoundTag readCompressed(byte[] data) {
        try {
            return readCompressed(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CompoundTag readCompressed(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new GZIPInputStream(in));
        byte type = data.readByte();
        if (type != 10) {
            throw new IOException("Root tag must be a compound, got type " + type);
        }
        data.readUTF(); // root name, "" in practice
        return (CompoundTag) readPayload(data, type, 0);
    }

    public static byte[] writeCompressed(CompoundTag root) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            writeCompressed(root, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static void writeCompressed(CompoundTag root, OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        DataOutputStream data = new DataOutputStream(gzip);
        data.writeByte(root.id());
        data.writeUTF("");
        writePayload(data, root);
        data.flush();
        gzip.finish();
    }

    private static NbtTag readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        }
        return switch (type) {
            case 1 -> new ByteTag(in.readByte());
            case 2 -> new ShortTag(in.readShort());
            case 3 -> new IntTag(in.readInt());
            case 4 -> new LongTag(in.readLong());
            case 5 -> new FloatTag(in.readFloat());
            case 6 -> new DoubleTag(in.readDouble());
            case 7 -> {
                byte[] value = new byte[length(in)];
                in.readFully(value);
                yield new ByteArrayTag(value);
            }
            case 8 -> new StringTag(in.readUTF());
            case 9 -> {
                byte elementType = in.readByte();
                int length = length(in);
                if (elementType == 0 && length > 0) {
                    throw new IOException("Non-empty list without an element type");
                }
                List<NbtTag> values = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    values.add(readPayload(in, elementType, depth + 1));
                }
                yield new ListTag(elementType, values);
            }
            case 10 -> {
                CompoundTag compound = new CompoundTag();
                byte childType;
                while ((childType = in.readByte()) != 0) {
                    String name = in.readUTF();
                    compound.put(name, readPayload(in, childType, depth + 1));
                }
                yield compound;
            }
            case 11 -> {
                int[] value = new int[length(in)];
                for (int i = 0; i < value.length; i++) {
                    value[i] = in.readInt();
                }
                yield new IntArrayTag(value);
            }
            case 12 -> {
                long[] value = new long[length(in)];
                for (int i = 0; i < value.length; i++) {
                    value[i] = in.readLong();
                }
                yield new LongArrayTag(value);
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int length(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative NBT length " + length);
        }
        return length;
    }

    private static void writePayload(DataOutput out, NbtTag tag) throws IOException {
        switch (tag) {
            case ByteTag t -> out.writeByte(t.value());
            case ShortTag t -> out.writeShort(t.value());
            case IntTag t -> out.writeInt(t.value());
            case LongTag t -> out.writeLong(t.value());
            case FloatTag t -> out.writeFloat(t.value());
            case DoubleTag t -> out.writeDouble(t.value());
            case ByteArrayTag t -> {
                out.writeInt(t.value().length);
                out.write(t.value());
            }
            case StringTag t -> out.writeUTF(t.value());
            case ListTag t -> {
                out.writeByte(t.values().isEmpty() ? t.elementType() : t.values().get(0).id());
                out.writeInt(t.values().size());
                for (NbtTag value : t.values()) {
                    writePayload(out, value);
                }
            }
            case CompoundTag t -> {
                for (Map.Entry<String, NbtTag> entry : t.values().entrySet()) {
                    out.writeByte(entry.getValue().id());
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue());
                }
                out.writeByte(0);
            }
            case IntArrayTag t -> {
                out.writeInt(t.value().length);
                for (int value : t.value()) {
                    out.writeInt(value);
                }
            }
            case LongArrayTag t -> {
                out.writeInt(t.value().length);
                for (long value : t.value()) {
                    out.writeLong(value);
                }
            }
        }
    }
}
