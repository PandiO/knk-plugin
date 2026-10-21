package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.paper.minigame.MinigamePlayerVault;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test fixtures for the duel runtime: named item mocks (a clone is the same item, so identity checks show that the
 * very same stacks come back), a fake codec, and players whose inventory and ender chest are real arrays behind
 * mocks.
 */
final class DuelTestItems {

    private final Map<String, ItemStack[]> encoded = new HashMap<>();

    ItemStack item(String name) {
        ItemStack item = mock(ItemStack.class, name);
        when(item.clone()).thenReturn(item);
        when(item.getAmount()).thenReturn(1);
        when(item.isSimilar(item)).thenReturn(true);
        return item;
    }

    MinigamePlayerVault.ItemCodec codec() {
        return new MinigamePlayerVault.ItemCodec() {
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
    }

    TestPlayer player(String name) {
        return new TestPlayer(name);
    }

    /** A player with a 41-slot inventory (36 storage, 4 armour, off-hand) and a 27-slot ender chest. */
    static final class TestPlayer {
        private final Player player = mock(Player.class);
        private final PlayerInventory inventory = mock(PlayerInventory.class);
        private final Inventory enderChest = mock(Inventory.class);
        private final UUID id;
        ItemStack[] contents = new ItemStack[41];
        final List<ItemStack> enderChestContents = new ArrayList<>();
        private boolean inventoryFull;
        private boolean enderChestFull;
        boolean online = true;
        boolean dead;
        Location location;

        TestPlayer(String name) {
            id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getEnderChest()).thenReturn(enderChest);
            when(player.isOnline()).thenAnswer(inv -> online);
            when(player.isDead()).thenAnswer(inv -> dead);
            when(player.getLocation()).thenAnswer(inv -> location);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getHealth()).thenReturn(20.0);
            when(player.getFoodLevel()).thenReturn(20);
            when(player.getActivePotionEffects()).thenReturn(List.of());
            when(inventory.getContents()).thenAnswer(inv -> contents.clone());
            when(inventory.getStorageContents()).thenAnswer(inv -> Arrays.copyOf(contents, 36));
            when(inventory.getSize()).thenReturn(41);
            when(inventory.getItem(anyInt())).thenAnswer(inv -> contents[(int) inv.getArgument(0)]);
            doAnswer(inv -> {
                contents[(int) inv.getArgument(0)] = inv.getArgument(1);
                return null;
            }).when(inventory).setItem(anyInt(), any());
            doAnswer(inv -> {
                ItemStack[] given = inv.getArgument(0);
                contents = Arrays.copyOf(given, 41);
                return null;
            }).when(inventory).setContents(any(ItemStack[].class));
            doAnswer(inv -> {
                contents = new ItemStack[41];
                return null;
            }).when(inventory).clear();
            when(inventory.addItem(any(ItemStack[].class))).thenAnswer(inv -> {
                HashMap<Integer, ItemStack> rest = new HashMap<>();
                Object[] stacks = inv.getArguments();
                for (int i = 0; i < stacks.length; i++) {
                    ItemStack stack = (ItemStack) stacks[i];
                    int free = firstFree();
                    if (inventoryFull || free < 0) rest.put(i, stack);
                    else contents[free] = stack;
                }
                return rest;
            });
            when(enderChest.addItem(any(ItemStack[].class))).thenAnswer(inv -> {
                HashMap<Integer, ItemStack> rest = new HashMap<>();
                Object[] stacks = inv.getArguments();
                for (int i = 0; i < stacks.length; i++) {
                    if (enderChestFull) rest.put(i, (ItemStack) stacks[i]);
                    else enderChestContents.add((ItemStack) stacks[i]);
                }
                return rest;
            });
        }

        private int firstFree() {
            for (int i = 0; i < 36; i++) if (contents[i] == null) return i;
            return -1;
        }

        Player player() {
            return player;
        }

        UUID id() {
            return id;
        }

        void inventoryFull(boolean full) {
            inventoryFull = full;
        }

        void enderChestFull(boolean full) {
            enderChestFull = full;
        }

        /** Every non-empty stack in the inventory, in slot order. */
        List<ItemStack> received() {
            return Arrays.stream(contents).filter(Objects::nonNull).toList();
        }

        List<ItemStack> enderChestReceived() {
            return List.copyOf(enderChestContents);
        }

        void give(ItemStack... stacks) {
            for (ItemStack stack : stacks) contents[firstFree()] = stack;
        }
    }
}
