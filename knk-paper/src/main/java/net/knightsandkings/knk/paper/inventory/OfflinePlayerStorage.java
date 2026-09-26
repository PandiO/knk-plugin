package net.knightsandkings.knk.paper.inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.nbt.NbtIo;
import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt;

/**
 * An offline player's saved data file, {@code <main world>/playerdata/<uuid>.dat} (KNG-13). Items go
 * through Paper's own item serialization ({@link ItemStack#deserializeBytes}, which also upgrades
 * items saved by an older version, and {@link ItemStack#serializeAsBytes}), so no server internals.
 * <p>
 * A {@link Snapshot} remembers a hash of the bytes it was read from; {@link #save} refuses when the
 * file changed since (the player joined and quit, another admin saved first). Writes go to a temp
 * file, keep the previous file as {@code <uuid>.dat_old} and then replace it - the same shape as the
 * server's own save. Main thread only (the online check and the server's own load of the file on join
 * are main-thread too); the files are a few kilobytes.
 */
public class OfflinePlayerStorage {

    public record Snapshot(UUID uuid, Path file, byte[] sha256, CompoundTag root, int dataVersion, boolean sameVersionAsServer) {
    }

    public enum SaveResult { SAVED, FILE_CHANGED, VERSION_MISMATCH }

    private final Supplier<Path> playerDataDirectory;
    private final IntSupplier serverDataVersion;

    public OfflinePlayerStorage(Supplier<Path> playerDataDirectory, IntSupplier serverDataVersion) {
        this.playerDataDirectory = Objects.requireNonNull(playerDataDirectory, "playerDataDirectory");
        this.serverDataVersion = Objects.requireNonNull(serverDataVersion, "serverDataVersion");
    }

    /** The player's saved data, or empty if they never played on this server. */
    public Optional<Snapshot> load(UUID uuid) throws IOException {
        Path file = fileOf(uuid);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(file);
        CompoundTag root = NbtIo.readCompressed(bytes);
        int dataVersion = PlayerStorageNbt.dataVersion(root);
        return Optional.of(new Snapshot(uuid, file, sha256(bytes), root, dataVersion, dataVersion == serverDataVersion.getAsInt()));
    }

    /**
     * Writes {@code root} over the snapshot's file. Refuses when the file isn't at the server's data
     * version (its other data would skip the upgrade) or changed since the snapshot was read.
     */
    public SaveResult save(Snapshot snapshot, CompoundTag root) throws IOException {
        if (!snapshot.sameVersionAsServer()) {
            return SaveResult.VERSION_MISMATCH;
        }
        Path file = snapshot.file();
        if (!Files.isRegularFile(file) || !Arrays.equals(sha256(Files.readAllBytes(file)), snapshot.sha256())) {
            return SaveResult.FILE_CHANGED;
        }
        Path directory = file.getParent();
        String name = file.getFileName().toString();
        Path temp = Files.createTempFile(directory, snapshot.uuid() + "-", ".knk-tmp"); // not *.dat: the server lists those as players
        try {
            Files.write(temp, NbtIo.writeCompressed(root));
            Files.copy(file, directory.resolve(name + "_old"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return SaveResult.SAVED;
    }

    /** An item from the file as an ItemStack, upgraded from {@code dataVersion} to the server's. */
    public ItemStack toItemStack(CompoundTag item, int dataVersion) {
        return ItemStack.deserializeBytes(NbtIo.writeCompressed(PlayerStorageNbt.forDeserialize(item, dataVersion)));
    }

    /** An ItemStack as an item for the file. */
    public CompoundTag toNbt(ItemStack item) {
        return PlayerStorageNbt.fromSerialized(NbtIo.readCompressed(item.serializeAsBytes()));
    }

    private Path fileOf(UUID uuid) {
        return playerDataDirectory.get().resolve(uuid + ".dat");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
