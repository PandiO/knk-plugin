package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult;
import net.knightsandkings.knk.paper.duel.ui.DuelGui;
import net.knightsandkings.knk.paper.duel.ui.DuelInviteToggles;
import net.knightsandkings.knk.paper.duel.ui.DuelNegotiationUi;
import net.knightsandkings.knk.paper.duel.ui.DuelProposalEditor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.mockito.invocation.Invocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * P5's negotiation UI on top of P4's {@link DuelHarness}: a fake {@link DuelGui} whose inventories are maps, players
 * whose open inventory is tracked, an anvil whose answer the test types, and chat capture.
 */
final class DuelUiHarness {

    final DuelHarness h;
    final FakeGui gui = new FakeGui();
    final DuelNegotiationUi ui;
    final DuelInviteToggles toggles;
    /** The anvil prompt waiting for the test's answer. */
    Consumer<String> anvilDone;
    Runnable anvilCancelled;
    final Map<UUID, Inventory> open = new HashMap<>();
    CheckResult check = new CheckResult(true, true, 0, 5, List.of());
    /** Whether the players stand inside the arena's region. */
    boolean inArena = true;

    DuelUiHarness(Path dataFolder) {
        h = new DuelHarness(dataFolder);
        trackScreens(h.alice);
        trackScreens(h.bob);
        when(h.api.check(any())).thenAnswer(inv -> CompletableFuture.completedFuture(check));
        toggles = new DuelInviteToggles(dataFolder.resolve("duel-toggles.yml").toFile(), Logger.getLogger("test"));
        ui = new DuelNegotiationUi(new DuelNegotiationUi.Ports(h.service, h.api, h.duelItems,
                player -> inArena ? Optional.of(h.arena) : Optional.empty(),
                id -> CompletableFuture.completedFuture(Optional.of(h.arena)),
                (viewer, sender) -> false,
                (viewer, target) -> true,
                id -> h.players.containsKey(id) ? h.players.get(id).player() : null,
                id -> id.equals(h.alice.id()) ? 1 : id.equals(h.bob.id()) ? 2 : null,
                id -> java.util.OptionalLong.of(5_000),
                null,
                (player, prompt, done, cancelled) -> {
                    anvilDone = done;
                    anvilCancelled = cancelled;
                },
                gui, kit -> List.of("Iron Sword"), toggles, Runnable::run, Runnable::run, Logger.getLogger("test")));
        ui.rememberArena(h.arena);
    }

    private void trackScreens(DuelTestItems.TestPlayer who) {
        Player player = who.player();
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenAnswer(inv -> open.get(who.id()));
        when(player.getOpenInventory()).thenAnswer(inv -> open.containsKey(who.id()) ? view : null);
        when(player.openInventory(any(Inventory.class))).thenAnswer(inv -> {
            open.put(who.id(), inv.getArgument(0));
            return view;
        });
        doAnswer(inv -> {
            open.remove(who.id());
            return null;
        }).when(player).closeInventory();
    }

    /** The holder of the player's open menu, or null. */
    InventoryHolder screen(DuelTestItems.TestPlayer who) {
        Inventory inv = open.get(who.id());
        return inv == null ? null : inv.getHolder(false);
    }

    DuelProposalEditor.Holder editorOf(DuelTestItems.TestPlayer who) {
        return (DuelProposalEditor.Holder) screen(who);
    }

    /** A click in the open editor's top slot. */
    void clickTop(DuelTestItems.TestPlayer who, int slot) {
        ui.editor().click(who.player(), editorOf(who), true, slot, null);
    }

    /** A click on the player's own inventory slot while the editor is open. */
    void clickBottom(DuelTestItems.TestPlayer who, int slot) {
        ui.editor().click(who.player(), editorOf(who), false, slot, who.contents[slot]);
    }

    /** The name the fake GUI gave the icon in {@code slot} of the player's open menu, or null. */
    String iconAt(DuelTestItems.TestPlayer who, int slot) {
        Inventory inv = open.get(who.id());
        return inv == null ? null : gui.nameOf(inv.getItem(slot));
    }

    /** Every chat line and action bar the player got, as plain text. */
    static List<String> chat(DuelTestItems.TestPlayer who) {
        List<String> out = new ArrayList<>();
        for (Invocation call : mockingDetails(who.player()).getInvocations()) {
            String name = call.getMethod().getName();
            if (!name.equals("sendMessage") && !name.equals("sendActionBar")) continue;
            Object arg = call.getArguments().length == 0 ? null : call.getArgument(0);
            if (arg instanceof Component c) out.add(PlainTextComponentSerializer.plainText().serialize(c));
            else if (arg instanceof String s) out.add(ChatColor.stripColor(s));
        }
        return out;
    }

    static String allChat(DuelTestItems.TestPlayer who) {
        return String.join("\n", chat(who));
    }

    /** Every run-command the player's chat buttons carry. */
    static List<String> buttons(DuelTestItems.TestPlayer who) {
        List<String> out = new ArrayList<>();
        for (Invocation call : mockingDetails(who.player()).getInvocations()) {
            if (call.getMethod().getName().equals("sendMessage") && call.getArguments().length > 0
                    && call.getArgument(0) instanceof Component c) {
                collect(c, out);
            }
        }
        return out;
    }

    private static void collect(Component c, List<String> out) {
        ClickEvent click = c.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
            out.add(((ClickEvent.Payload.Text) click.payload()).value());
        }
        for (Component child : c.children()) collect(child, out);
    }

    /** Inventories as maps; icons are mocks with a readable name. */
    static final class FakeGui implements DuelGui {
        private final Map<ItemStack, String> names = new HashMap<>();

        @Override
        public Inventory create(InventoryHolder holder, int size, Component title) {
            Map<Integer, ItemStack> slots = new HashMap<>();
            Inventory inv = mock(Inventory.class);
            when(inv.getHolder(false)).thenReturn(holder);
            when(inv.getHolder()).thenReturn(holder);
            when(inv.getSize()).thenReturn(size);
            doAnswer(call -> {
                ItemStack item = call.getArgument(1);
                if (item == null) slots.remove((int) call.getArgument(0));
                else slots.put(call.getArgument(0), item);
                return null;
            }).when(inv).setItem(anyInt(), any());
            when(inv.getItem(anyInt())).thenAnswer(call -> slots.get((int) call.getArgument(0)));
            doAnswer(call -> {
                slots.clear();
                return null;
            }).when(inv).clear();
            return inv;
        }

        @Override
        public ItemStack icon(Material material, Component name, List<Component> lore) {
            return named(material, plain(name) + " | " + lore.stream().map(FakeGui::plain).collect(Collectors.joining(" / ")));
        }

        @Override
        public ItemStack head(UUID player, Component name, List<Component> lore) {
            return icon(Material.PLAYER_HEAD, name, lore);
        }

        @Override
        public ItemStack stakeIcon(ItemStack copy, List<Component> extraLore) {
            return named(Material.PAPER, "stake " + describe(copy));
        }

        @Override
        public String describe(ItemStack stack) {
            return stack == null ? "unknown" : stack.toString();
        }

        String nameOf(ItemStack icon) {
            return icon == null ? null : names.get(icon);
        }

        private ItemStack named(Material material, String name) {
            ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(material);
            names.put(item, name);
            return item;
        }

        private static String plain(Component c) {
            return PlainTextComponentSerializer.plainText().serialize(c);
        }
    }
}
