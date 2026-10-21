package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.duel.ArenaQueue;
import net.knightsandkings.knk.paper.duel.DuelMatch;
import net.knightsandkings.knk.paper.duel.DuelService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /arena [name]} (arena duels DESIGN §6.1, §9.3): the arena you stand in, or the named one. The arena menu
 * ({@code arena_main}) is P6's (KNG-220): it opens through the {@code DuelMenuPort} (or {@link #setMenu}). Until then, and whenever no menu is
 * set, the command answers in chat: the queue, the live matches, and buttons to challenge a player and to claim.
 * Node {@code knk.duel}.
 */
public final class ArenaCommand implements TabExecutor {

    private final Supplier<DuelService> service;
    private final Supplier<List<KnkArena>> arenas;
    private final Function<Player, Optional<KnkArena>> arenaAt;
    private final Supplier<net.knightsandkings.knk.paper.duel.ui.DuelMenuPort> menus;
    private volatile BiConsumer<Player, KnkArena> menu;

    public ArenaCommand(Supplier<DuelService> service, Supplier<List<KnkArena>> arenas,
                        Function<Player, Optional<KnkArena>> arenaAt,
                        Supplier<net.knightsandkings.knk.paper.duel.ui.DuelMenuPort> menus) {
        this.service = service;
        this.arenas = arenas;
        this.arenaAt = arenaAt;
        this.menus = menus == null ? () -> null : menus;
    }

    /** P6: open the {@code arena_main} menu for a player and an arena instead of the chat summary. */
    public void setMenu(BiConsumer<Player, KnkArena> menu) {
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open an arena.");
            return true;
        }
        Optional<KnkArena> arena = args.length > 0 ? byName(String.join(" ", args)) : arenaAt.apply(player);
        if (arena.isEmpty()) {
            List<String> names = arenas.get().stream().filter(KnkArena::duelEnabled).map(ArenaCommand::label).toList();
            player.sendMessage(ChatColor.RED + (args.length > 0 ? "No arena called " + String.join(" ", args) + "."
                    : "You aren't in an arena.") + (names.isEmpty() ? "" : " Arenas: " + String.join(", ", names)));
            return true;
        }
        var port = menus.get();
        if (port != null && port.openArena(player, args.length > 0 ? String.join(" ", args) : null)) return true;
        BiConsumer<Player, KnkArena> open = menu;
        if (open != null) {
            open.accept(player, arena.get());
        } else {
            summary(player, arena.get());
        }
        return true;
    }

    private void summary(Player player, KnkArena arena) {
        DuelService duels = service.get();
        player.sendMessage(Component.text("⚔ " + label(arena), NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(arena.duelEnabled() && duels != null ? "" : " (duels closed)", NamedTextColor.RED)));
        if (duels != null) {
            Optional<ArenaQueue> queue = duels.queueOf(arena.id());
            int waiting = queue.map(ArenaQueue::queuedCount).orElse(0);
            int busy = queue.map(q -> q.allocator().busyCount()).orElse(0);
            int total = queue.map(q -> q.allocator().totalCount()).orElse(arena.usableBattlegrounds().size());
            player.sendMessage(Component.text("   " + waiting + "/" + arena.maxQueuedMatches() + " waiting, " + busy + "/"
                    + total + " battlegrounds busy", NamedTextColor.YELLOW));
            List<DuelMatch> live = duels.matches().stream().filter(m -> m.arena().id() == arena.id()).toList();
            for (DuelMatch m : live) {
                player.sendMessage(Component.text("   " + m.nameOf(net.knightsandkings.knk.core.duel.DuelSide.CHALLENGER)
                        + " vs " + m.nameOf(net.knightsandkings.knk.core.duel.DuelSide.OPPONENT) + " — "
                        + m.phase().name().toLowerCase(Locale.ROOT).replace('_', ' '), NamedTextColor.GRAY));
            }
            if (live.isEmpty()) player.sendMessage(Component.text("   No duels right now.", NamedTextColor.GRAY));
        }
        player.sendMessage(Component.text("   ")
                .append(Component.text("[Challenge a player]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand("/duel "))
                        .hoverEvent(HoverEvent.showText(Component.text("Stand in the arena and type a name", NamedTextColor.GRAY))))
                .append(Component.text(" "))
                .append(Component.text("[My claims]", NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/duel claim"))
                        .hoverEvent(HoverEvent.showText(Component.text("Collect duel winnings or returned items", NamedTextColor.GRAY)))));
    }

    private Optional<KnkArena> byName(String name) {
        String wanted = name.trim();
        return arenas.get().stream()
                .filter(a -> wanted.equalsIgnoreCase(a.key()) || wanted.equalsIgnoreCase(a.name()))
                .findFirst();
    }

    private static String label(KnkArena arena) {
        return arena.name() != null && !arena.name().isBlank() ? arena.name() : arena.key();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return arenas.get().stream().filter(KnkArena::duelEnabled).map(KnkArena::key)
                .filter(k -> k != null && k.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
