package net.knightsandkings.knk.paper.minigame;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels Phase 0 (KNG-209): the vault extracted from the siege vault - snapshot and restore round trips, the
 * quit/join split, a death at the end, a failed write, one directory per minigame, and siege files written by the
 * old {@code SiegePlayerVault} still restoring.
 */
class MinigamePlayerVaultTest {

    @TempDir
    Path dataFolder;

    // Location only holds its World through a WeakReference: keep the mock strongly reachable.
    private final World world = mock(World.class);
    private final Location spawn;
    private final Location home;
    private final List<Location> teleports = new ArrayList<>();
    private final List<Player> afterRestore = new ArrayList<>();
    /** Item bytes as the fake codec writes them: a key into this map. */
    private final Map<String, ItemStack[]> encoded = new HashMap<>();
    private final ItemStack sword = item();
    private final ItemStack bread = item();
    private final ItemStack kit = item();

    MinigamePlayerVaultTest() {
        when(world.getName()).thenReturn("world");
        spawn = new Location(world, 0.5, 64, 0.5);
        home = new Location(world, 120.5, 70, -40.5, 90f, 10f);
    }

    private static ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private final MinigamePlayerVault.ItemCodec codec = new MinigamePlayerVault.ItemCodec() {
        @Override
        public byte[] encode(ItemStack[] contents) {
            String key = "items-" + encoded.size();
            encoded.put(key, contents.clone());
            return key.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ItemStack[] decode(byte[] bytes) {
            return encoded.get(new String(bytes, StandardCharsets.UTF_8)).clone();
        }
    };

    private MinigamePlayerVault vault(String minigame, File directory) {
        return new MinigamePlayerVault(minigame, directory, (player, to) -> teleports.add(to),
            Logger.getLogger("test"), codec, name -> "world".equals(name) ? world : null, () -> spawn, player -> 20.0);
    }

    private MinigamePlayerVault siegeVault() {
        MinigamePlayerVault vault = vault("siege", dataFolder.resolve("siege-vault").toFile());
        vault.setAfterRestore(afterRestore::add);
        return vault;
    }

    private static final class Gear {
        ItemStack[] contents;
    }

    private Player player(UUID id, ItemStack[] contents, Gear worn) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        worn.contents = contents;
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Alice");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenAnswer(inv -> worn.contents);
        org.mockito.Mockito.doAnswer(inv -> {
            worn.contents = inv.getArgument(0);
            return null;
        }).when(inventory).setContents(any(ItemStack[].class));
        when(player.getLevel()).thenReturn(12);
        when(player.getExp()).thenReturn(0.5f);
        when(player.getHealth()).thenReturn(14.0);
        when(player.getFoodLevel()).thenReturn(17);
        when(player.getSaturation()).thenReturn(3.0f);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(home);
        return player;
    }

    @Test
    void aSnapshotIsWrittenFirstAndRestoresEverythingThenGoesAway() throws Exception {
        UUID id = UUID.randomUUID();
        Gear worn = new Gear();
        Player alice = player(id, new ItemStack[] {sword, null, bread}, worn);
        MinigamePlayerVault vault = siegeVault();

        assertTrue(vault.snapshot(alice, "match-1", 4));
        File file = dataFolder.resolve("siege-vault").resolve(id + ".yml").toFile();
        assertTrue(file.isFile(), "siege keeps siege-vault/<uuid>.yml");
        String yaml = Files.readString(file.toPath());
        assertTrue(yaml.contains("minigame: siege") && yaml.contains("matchToken: match-1") && yaml.contains("instanceId: 4"), yaml);
        assertEquals("match-1", vault.matchTokenOf(id).orElseThrow());
        assertEquals(home, vault.returnLocation(id).orElseThrow());

        worn.contents = new ItemStack[] {kit};
        assertTrue(vault.restore(alice));

        assertArrayEquals(new ItemStack[] {sword, null, bread}, worn.contents);
        verify(alice).setLevel(12);
        verify(alice).setHealth(14.0);
        verify(alice).setFoodLevel(17);
        verify(alice).setGameMode(GameMode.SURVIVAL);
        assertEquals(List.of(home), teleports);
        assertEquals(List.of(alice), afterRestore);
        assertFalse(file.exists());
        assertFalse(vault.has(id));
        assertFalse(vault.restore(alice), "nothing left to restore");
    }

    @Test
    void afterARestartTheFileAloneRestores() {
        UUID id = UUID.randomUUID();
        Gear worn = new Gear();
        Player alice = player(id, new ItemStack[] {sword}, worn);
        assertTrue(siegeVault().snapshot(alice, "match-2", 1));
        worn.contents = new ItemStack[] {kit};

        MinigamePlayerVault restarted = siegeVault();
        assertTrue(restarted.hasFile(id));
        assertEquals(1, restarted.peek(id).orElseThrow().instanceId());
        assertTrue(restarted.restoreOnJoin(alice));

        assertArrayEquals(new ItemStack[] {sword}, worn.contents);
        assertEquals(home.getX(), teleports.get(0).getX());
        assertEquals(home.getYaw(), teleports.get(0).getYaw());
        assertFalse(restarted.hasFile(id));
    }

    @Test
    void aSiegeFileWrittenBeforeTheExtractionStillRestores() throws Exception {
        UUID id = UUID.randomUUID();
        encoded.put("old-siege-items", new ItemStack[] {bread, sword});
        Path directory = Files.createDirectories(dataFolder.resolve("siege-vault"));
        // The exact keys SiegePlayerVault (format version 1) wrote: no minigame, the lobby in lobbyId.
        Files.writeString(directory.resolve(id + ".yml"), String.join("\n",
            "version: 1",
            "uuid: " + id,
            "lobbyId: 7",
            "matchToken: 3f2a-old",
            "takenAt: '2026-10-19T18:00:00Z'",
            "inventoryRestored: false",
            "items: " + Base64.getEncoder().encodeToString("old-siege-items".getBytes(StandardCharsets.UTF_8)),
            "level: 30",
            "exp: 0.25",
            "health: 18.0",
            "food: 20",
            "saturation: 5.0",
            "effects: []",
            "gameMode: ADVENTURE",
            "location:",
            "  world: world",
            "  x: -15.5",
            "  y: 71.0",
            "  z: 230.5",
            "  yaw: 180.0",
            "  pitch: 0.0",
            ""));
        Gear worn = new Gear();
        Player alice = player(id, new ItemStack[] {kit}, worn);
        MinigamePlayerVault vault = siegeVault();

        MinigamePlayerVault.Snapshot old = vault.peek(id).orElseThrow();
        assertEquals("siege", old.minigame());
        assertEquals("3f2a-old", old.matchToken());
        assertEquals(7, old.instanceId());
        assertTrue(vault.restoreOnJoin(alice));

        assertArrayEquals(new ItemStack[] {bread, sword}, worn.contents);
        verify(alice).setLevel(30);
        verify(alice).setGameMode(GameMode.ADVENTURE);
        assertEquals(1, teleports.size());
        assertEquals(-15.5, teleports.get(0).getX());
        assertEquals(230.5, teleports.get(0).getZ());
        assertFalse(Files.exists(directory.resolve(id + ".yml")));
    }

    @Test
    void aQuitRestoresTheInventoryNowAndTheSpotOnTheNextJoin() throws Exception {
        UUID id = UUID.randomUUID();
        Gear worn = new Gear();
        Player alice = player(id, new ItemStack[] {sword}, worn);
        MinigamePlayerVault vault = siegeVault();
        assertTrue(vault.snapshot(alice, "match-3", 2));
        worn.contents = new ItemStack[] {kit};

        vault.restoreOnQuit(alice);
        assertArrayEquals(new ItemStack[] {sword}, worn.contents);
        assertTrue(teleports.isEmpty(), "no teleport while quitting");
        assertTrue(vault.hasFile(id));
        assertTrue(Files.readString(dataFolder.resolve("siege-vault").resolve(id + ".yml")).contains("inventoryRestored: true"));

        worn.contents = new ItemStack[] {kit};
        MinigamePlayerVault afterRestart = siegeVault();
        assertTrue(afterRestart.restoreOnJoin(alice));
        assertArrayEquals(new ItemStack[] {kit}, worn.contents, "the inventory is not given twice");
        assertEquals(1, teleports.size());
        assertFalse(afterRestart.hasFile(id));
        assertEquals(2, afterRestore.size(), "the sweep runs after each restore");
    }

    @Test
    void aMemberDeadAtTheEndGetsTheSpotOnRespawn() {
        UUID id = UUID.randomUUID();
        Gear worn = new Gear();
        Player alice = player(id, new ItemStack[] {sword}, worn);
        MinigamePlayerVault vault = siegeVault();
        assertTrue(vault.snapshot(alice, "match-4", 1));
        when(alice.isDead()).thenReturn(true);

        assertTrue(vault.restore(alice));

        assertTrue(teleports.isEmpty());
        verify(alice, never()).setHealth(org.mockito.ArgumentMatchers.anyDouble());
        assertEquals(home, vault.takePendingRespawn(id).orElseThrow());
        assertTrue(vault.takePendingRespawn(id).isEmpty());
    }

    @Test
    void anUnwritableVaultRefusesTheSnapshotAndLeavesNothing() throws Exception {
        File notADirectory = Files.writeString(dataFolder.resolve("blocked"), "file").toFile();
        MinigamePlayerVault vault = vault("duel", notADirectory);
        UUID id = UUID.randomUUID();
        Player alice = player(id, new ItemStack[] {sword}, new Gear());

        assertFalse(vault.snapshot(alice, "match-5", 0));
        assertFalse(vault.has(id));
    }

    @Test
    void eachMinigameHasItsOwnDirectory() {
        UUID id = UUID.randomUUID();
        Player alice = player(id, new ItemStack[] {sword}, new Gear());
        MinigamePlayerVault siege = siegeVault();
        MinigamePlayerVault duel = vault("duel", dataFolder.resolve("duel-vault").toFile());

        assertTrue(duel.snapshot(alice, "duel-1", 0));
        assertFalse(siege.has(id));
        assertEquals("duel", duel.peek(id).orElseThrow().minigame());
        assertEquals("duel", duel.minigame());
        assertNotNull(duel.matchTokenOf(id).orElse(null));
        verify(alice, times(0)).teleport(any(Location.class));
    }
}
