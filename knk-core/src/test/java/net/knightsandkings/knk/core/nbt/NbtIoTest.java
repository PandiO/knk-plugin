package net.knightsandkings.knk.core.nbt;

import net.knightsandkings.knk.core.nbt.NbtTag.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** KNG-13: gzip NBT as in playerdata/*.dat and ItemStack.serializeAsBytes. */
class NbtIoTest {

    private static CompoundTag everyTagType() {
        CompoundTag nested = new CompoundTag().put("name", new StringTag("Grüße ✓ \u0000 end"));
        return new CompoundTag()
                .put("byte", new ByteTag((byte) -3))
                .put("short", new ShortTag((short) 1234))
                .put("int", new IntTag(4556))
                .put("long", new LongTag(Long.MIN_VALUE))
                .put("float", new FloatTag(1.5f))
                .put("double", new DoubleTag(-2.25))
                .put("bytes", new ByteArrayTag(new byte[]{1, 2, 3}))
                .put("string", new StringTag(""))
                .put("list", ListTag.of((byte) 3, List.of(new IntTag(1), new IntTag(2))))
                .put("emptyList", new ListTag((byte) 0, List.of()))
                .put("compounds", ListTag.of((byte) 10, List.of(nested, new CompoundTag())))
                .put("nested", nested.deepCopy())
                .put("ints", new IntArrayTag(new int[]{7, -8}))
                .put("longs", new LongArrayTag(new long[]{9L, Long.MAX_VALUE}));
    }

    @Test
    void everyTagTypeRoundTrips() {
        CompoundTag root = everyTagType();

        CompoundTag read = NbtIo.readCompressed(NbtIo.writeCompressed(root));

        assertEquals(root, read);
        assertEquals(List.copyOf(root.values().keySet()), List.copyOf(read.values().keySet())); // order kept
        assertArrayEquals(NbtIo.writeCompressed(root), NbtIo.writeCompressed(read));
    }

    @Test
    void readsAHandWrittenFile() throws IOException {
        // Written byte by byte the way Minecraft's NbtIo does: type, root name, payload.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(3);
            out.writeUTF("DataVersion");
            out.writeInt(4556);
            out.writeByte(9);
            out.writeUTF("Inventory");
            out.writeByte(10);
            out.writeInt(1);
            out.writeByte(1);
            out.writeUTF("Slot");
            out.writeByte(4);
            out.writeByte(8);
            out.writeUTF("id");
            out.writeUTF("minecraft:bread");
            out.writeByte(0);
            out.writeByte(0);
        }

        CompoundTag root = NbtIo.readCompressed(bytes.toByteArray());

        assertEquals(4556, root.getInt("DataVersion").orElseThrow());
        CompoundTag item = (CompoundTag) root.getList("Inventory").orElseThrow().values().get(0);
        assertEquals(new StringTag("minecraft:bread"), item.get("id").orElseThrow());
        assertEquals(4, item.getInt("Slot").orElseThrow());
    }

    @Test
    void deepCopyIsIndependent() {
        CompoundTag root = everyTagType();
        CompoundTag copy = root.deepCopy();

        copy.getCompound("nested").orElseThrow().put("extra", new ByteTag((byte) 1));

        assertEquals(1, root.getCompound("nested").orElseThrow().values().size());
        assertNotSame(root.get("bytes").orElseThrow(), copy.get("bytes").orElseThrow());
    }

    @Test
    void aListRejectsMixedTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ListTag((byte) 3, List.of(new IntTag(1), new StringTag("x"))));
    }

    @Test
    void aRootThatIsNoCompoundIsRejected() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(8);
            out.writeUTF("");
            out.writeUTF("nope");
        }
        assertThrows(java.io.UncheckedIOException.class, () -> NbtIo.readCompressed(bytes.toByteArray()));
    }
}
