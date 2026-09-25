package net.knightsandkings.knk.paper.siege;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Own-gear snapshot and restore (DESIGN §9.1–9.2, D2). At the hub teleport a member's inventory
 * (storage, armour, off-hand), XP, health, food, saturation, potion effects, game mode and location
 * are written to {@code plugins/<plugin>/siege-vault/<uuid>.yml} <b>before</b> anything else happens,
 * and kept in memory. The player keeps their gear for the match.
 * <p>
 * Restore: on end, leave, kick or elimination: clear inventory → snapshot → teleport back → delete
 * the file. On quit mid-match the inventory and stats are restored inside {@code PlayerQuitEvent}
 * (the player is still valid there) and the file is marked {@code inventoryRestored}; the return
 * location is applied on the next join. A leftover file with no active membership (crash, restart,
 * a failed restore) triggers a full restore + teleport on join. A member who is dead at the end gets
 * their inventory back at once and is sent to the return location when they respawn.
 * <p>
 * Items use Paper's {@code ItemStack.serializeItemsAsBytes} (data-version aware). Main thread only;
 * the file writes are small and must finish before the teleport, so they are synchronous.
 */
public final class SiegePlayerVault {

    private static final int FORMAT_VERSION = 1;

    /** An in-memory snapshot. */
    public record Snapshot(
            UUID playerId,
            int lobbyId,
            String matchToken,
            Instant takenAt,
            ItemStack[] contents,
            int level,
            float exp,
            double health,
            int foodLevel,
            float saturation,
            List<PotionEffect> effects,
            GameMode gameMode,
            Location returnLocation,
            boolean inventoryRestored
    ) {
        Snapshot withInventoryRestored() {
            return new Snapshot(playerId, lobbyId, matchToken, takenAt, contents, level, exp, health, foodLevel,
                    saturation, effects, gameMode, returnLocation, true);
        }
    }

    private final File directory;
    private final Logger logger;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** Members who were dead when their match ended: sent to this location on respawn. */
    private final Map<UUID, Location> pendingRespawn = new ConcurrentHashMap<>();
    private Consumer<Player> afterRestore = player -> { };

    public SiegePlayerVault(File directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Runs after every inventory restore (5c: the siege-enchantment stripping sweep). */
    public void setAfterRestore(Consumer<Player> afterRestore) {
        this.afterRestore = afterRestore == null ? player -> { } : afterRestore;
    }

    // ==================== Snapshot ====================

    /**
     * Snapshot to file, then memory. Returns false (and leaves nothing behind) when the file can't
     * be written: the caller must then not take the player into the match.
     */
    public boolean snapshot(Player player, int lobbyId, String matchToken) {
        Snapshot snapshot = new Snapshot(
                player.getUniqueId(),
                lobbyId,
                matchToken,
                Instant.now(),
                cloneContents(player.getInventory().getContents()),
                player.getLevel(),
                player.getExp(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                List.copyOf(player.getActivePotionEffects()),
                player.getGameMode(),
                player.getLocation().clone(),
                false);
        try {
            write(snapshot);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "[Siege] Could not write the vault file for " + player.getName()
                    + "; not taking them into the match", e);
            deleteFile(player.getUniqueId());
            return false;
        }
        snapshots.put(player.getUniqueId(), snapshot);
        return true;
    }

    public boolean has(UUID playerId) {
        return snapshots.containsKey(playerId) || file(playerId).isFile();
    }

    public boolean hasFile(UUID playerId) {
        return file(playerId).isFile();
    }

    // ==================== Restore ====================

    /**
     * End, leave, kick, elimination: restore everything and teleport back; delete the file.
     * A dead player gets everything but the teleport now and the location on respawn.
     *
     * @return false when there was no snapshot for this player
     */
    public boolean restore(Player player) {
        Optional<Snapshot> found = load(player.getUniqueId());
        if (found.isEmpty()) return false;
        Snapshot snapshot = found.get();
        if (!snapshot.inventoryRestored()) {
            applyInventory(player, snapshot);
        }
        applyStats(player, snapshot);
        if (player.isDead()) {
            pendingRespawn.put(player.getUniqueId(), resolveLocation(snapshot.returnLocation()));
        } else {
            teleportBack(player, snapshot);
        }
        forget(player.getUniqueId());
        afterRestore.accept(player);
        return true;
    }

    /**
     * Quit mid-match: inventory and stats now, location on the next join (DESIGN §9.2). The file stays,
     * marked {@code inventoryRestored}, so a crash before the rejoin still gets the player home.
     */
    public void restoreOnQuit(Player player) {
        Optional<Snapshot> found = load(player.getUniqueId());
        if (found.isEmpty()) return;
        Snapshot snapshot = found.get();
        if (!snapshot.inventoryRestored()) {
            applyInventory(player, snapshot);
        }
        applyStats(player, snapshot);
        afterRestore.accept(player);
        Snapshot marked = snapshot.withInventoryRestored();
        try {
            write(marked);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "[Siege] Could not mark the vault of " + player.getName()
                    + " as restored; the next join restores it again (same snapshot)", e);
        }
        snapshots.remove(player.getUniqueId());
    }

    /**
     * On join with a leftover vault and no active membership: finish what quit started (location
     * only) or, after a crash, restore everything. Deletes the file.
     */
    public boolean restoreOnJoin(Player player) {
        return restore(player);
    }

    /** Where a member who died before the end must respawn (consumed). */
    public Optional<Location> takePendingRespawn(UUID playerId) {
        return Optional.ofNullable(pendingRespawn.remove(playerId));
    }

    /** The member's snapshot location (for Phase 7 lockdown exits, menus). */
    public Optional<Location> returnLocation(UUID playerId) {
        Snapshot s = snapshots.get(playerId);
        return s == null ? Optional.empty() : Optional.of(s.returnLocation().clone());
    }

    // ==================== Internals ====================

    private void applyInventory(Player player, Snapshot snapshot) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.getInventory().setContents(cloneContents(snapshot.contents()));
        player.setLevel(snapshot.level());
        player.setExp(Math.max(0f, Math.min(0.9999f, snapshot.exp())));
        player.updateInventory();
    }

    private void applyStats(Player player, Snapshot snapshot) {
        if (!player.isDead()) {
            double max = maxHealth(player);
            player.setHealth(Math.max(0.5, Math.min(max, snapshot.health())));
            player.setFoodLevel(snapshot.foodLevel());
            player.setSaturation(snapshot.saturation());
        }
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.addPotionEffects(snapshot.effects());
        if (snapshot.gameMode() != null) player.setGameMode(snapshot.gameMode());
        player.setFireTicks(0);
        player.setFallDistance(0f);
    }

    private void teleportBack(Player player, Snapshot snapshot) {
        player.teleport(resolveLocation(snapshot.returnLocation()));
    }

    private Location resolveLocation(Location location) {
        if (location != null && location.getWorld() != null) return location.clone();
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    private void forget(UUID playerId) {
        snapshots.remove(playerId);
        deleteFile(playerId);
    }

    private Optional<Snapshot> load(UUID playerId) {
        Snapshot inMemory = snapshots.get(playerId);
        if (inMemory != null) return Optional.of(inMemory);
        File file = file(playerId);
        if (!file.isFile()) return Optional.empty();
        try {
            return Optional.of(read(playerId, file));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "[Siege] Vault file " + file.getName() + " is unreadable; left in place for a manual restore", e);
            return Optional.empty();
        }
    }

    private void write(Snapshot s) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", FORMAT_VERSION);
        yaml.set("uuid", s.playerId().toString());
        yaml.set("lobbyId", s.lobbyId());
        yaml.set("matchToken", s.matchToken());
        yaml.set("takenAt", s.takenAt().toString());
        yaml.set("inventoryRestored", s.inventoryRestored());
        yaml.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(nonNull(s.contents()))));
        yaml.set("level", s.level());
        yaml.set("exp", (double) s.exp());
        yaml.set("health", s.health());
        yaml.set("food", s.foodLevel());
        yaml.set("saturation", (double) s.saturation());
        yaml.set("effects", new ArrayList<>(s.effects()));
        yaml.set("gameMode", s.gameMode() != null ? s.gameMode().name() : null);
        Location loc = s.returnLocation();
        if (loc != null && loc.getWorld() != null) {
            yaml.set("location.world", loc.getWorld().getName());
            yaml.set("location.x", loc.getX());
            yaml.set("location.y", loc.getY());
            yaml.set("location.z", loc.getZ());
            yaml.set("location.yaw", (double) loc.getYaw());
            yaml.set("location.pitch", (double) loc.getPitch());
        }
        File target = file(s.playerId());
        File temp = new File(directory, s.playerId() + ".yml.tmp");
        yaml.save(temp);
        if (target.exists() && !target.delete()) {
            throw new IOException("Could not replace " + target);
        }
        if (!temp.renameTo(target)) {
            throw new IOException("Could not move " + temp + " to " + target);
        }
    }

    private Snapshot read(UUID playerId, File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String items = yaml.getString("items", "");
        ItemStack[] contents = items.isEmpty()
                ? new ItemStack[0]
                : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(items));
        List<PotionEffect> effects = new ArrayList<>();
        for (Object o : yaml.getList("effects", List.of())) {
            if (o instanceof PotionEffect effect) effects.add(effect);
        }
        GameMode gameMode = null;
        String gm = yaml.getString("gameMode");
        if (gm != null) {
            try {
                gameMode = GameMode.valueOf(gm);
            } catch (IllegalArgumentException ignored) { }
        }
        Location location = null;
        String worldName = yaml.getString("location.world");
        World world = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (world != null) {
            location = new Location(world, yaml.getDouble("location.x"), yaml.getDouble("location.y"),
                    yaml.getDouble("location.z"), (float) yaml.getDouble("location.yaw"), (float) yaml.getDouble("location.pitch"));
        }
        Instant takenAt;
        try {
            takenAt = Instant.parse(yaml.getString("takenAt", Instant.EPOCH.toString()));
        } catch (RuntimeException e) {
            takenAt = Instant.EPOCH;
        }
        return new Snapshot(playerId, yaml.getInt("lobbyId"), yaml.getString("matchToken"), takenAt, contents,
                yaml.getInt("level"), (float) yaml.getDouble("exp"), yaml.getDouble("health", 20.0),
                yaml.getInt("food", 20), (float) yaml.getDouble("saturation", 5.0), effects, gameMode, location,
                yaml.getBoolean("inventoryRestored", false));
    }

    private File file(UUID playerId) {
        return new File(directory, playerId + ".yml");
    }

    private void deleteFile(UUID playerId) {
        File file = file(playerId);
        if (file.exists() && !file.delete()) {
            logger.warning("[Siege] Could not delete vault file " + file.getName());
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /** serializeItemsAsBytes wants no nulls: empty slots become air, which round-trips as empty. */
    private static ItemStack[] nonNull(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? ItemStack.empty() : contents[i];
        }
        return copy;
    }
}
