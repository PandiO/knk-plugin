package net.knightsandkings.knk.paper.inventory;

import net.knightsandkings.knk.core.nbt.NbtIo;
import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.NbtTag.IntTag;
import net.knightsandkings.knk.core.nbt.NbtTag.StringTag;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt;
import net.knightsandkings.knk.paper.inventory.OfflinePlayerStorage.SaveResult;
import net.knightsandkings.knk.paper.inventory.OfflinePlayerStorage.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-13: reading and safely writing playerdata/<uuid>.dat (no server needed - items stay NBT). */
class OfflinePlayerStorageTest {

    private static final int SERVER_VERSION = 4556;
    private final UUID carol = UUID.randomUUID();

    @TempDir
    Path playerData;

    private OfflinePlayerStorage storage() {
        return new OfflinePlayerStorage(() -> playerData, () -> SERVER_VERSION);
    }

    private static CompoundTag item(String id) {
        return new CompoundTag().put("id", new StringTag("minecraft:" + id)).put("count", new IntTag(1));
    }

    private Path writeFile(int dataVersion) throws IOException {
        CompoundTag root = new CompoundTag().put("DataVersion", new IntTag(dataVersion)).put("XpLevel", new IntTag(30));
        PlayerStorageNbt.setInventory(root, Map.of(0, item("diamond_sword")), Map.of());
        Path file = playerData.resolve(carol + ".dat");
        Files.write(file, NbtIo.writeCompressed(root));
        return file;
    }

    @Test
    void aPlayerWhoNeverPlayedHasNoSnapshot() throws IOException {
        assertTrue(storage().load(carol).isEmpty());
    }

    @Test
    void savesAnEditAndKeepsTheOldFile() throws IOException {
        Path file = writeFile(SERVER_VERSION);
        byte[] before = Files.readAllBytes(file);
        Snapshot snapshot = storage().load(carol).orElseThrow();
        assertTrue(snapshot.sameVersionAsServer());

        CompoundTag edited = snapshot.root().deepCopy();
        PlayerStorageNbt.setEnderChest(edited, Map.of(3, item("emerald")));

        assertEquals(SaveResult.SAVED, storage().save(snapshot, edited));
        CompoundTag saved = NbtIo.readCompressed(Files.readAllBytes(file));
        assertEquals(item("emerald"), PlayerStorageNbt.enderChest(saved).get(3));
        assertEquals(new IntTag(30), saved.get("XpLevel").orElseThrow());
        assertArrayEquals(before, Files.readAllBytes(playerData.resolve(carol + ".dat_old")));
        try (var files = Files.list(playerData)) {
            assertEquals(2, files.count()); // no temp file left behind
        }
    }

    @Test
    void refusesWhenTheFileChangedSinceItWasRead() throws IOException {
        Path file = writeFile(SERVER_VERSION);
        Snapshot snapshot = storage().load(carol).orElseThrow();
        CompoundTag joinedAndQuit = snapshot.root().deepCopy().put("XpLevel", new IntTag(31));
        Files.write(file, NbtIo.writeCompressed(joinedAndQuit));

        assertEquals(SaveResult.FILE_CHANGED, storage().save(snapshot, snapshot.root()));
        assertEquals(new IntTag(31), NbtIo.readCompressed(Files.readAllBytes(file)).get("XpLevel").orElseThrow());
        assertFalse(Files.exists(playerData.resolve(carol + ".dat_old")));
    }

    @Test
    void refusesAFileFromAnOlderVersion() throws IOException {
        Path file = writeFile(3953);
        byte[] before = Files.readAllBytes(file);
        Snapshot snapshot = storage().load(carol).orElseThrow();

        assertFalse(snapshot.sameVersionAsServer());
        assertEquals(3953, snapshot.dataVersion());
        assertEquals(SaveResult.VERSION_MISMATCH, storage().save(snapshot, snapshot.root()));
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
