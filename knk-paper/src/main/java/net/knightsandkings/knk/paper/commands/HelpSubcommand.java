package net.knightsandkings.knk.paper.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Help subcommand showing available commands or details for specific subcommand. KNG-107: besides
 * the /knk subcommands, it lists the plugin's other commands the sender may use (holding one of
 * their nodes, or open to everyone), so a player without admin nodes still gets help.
 */
public class HelpSubcommand {
    /** A plugin command other than /knk: its plugin.yml text and the nodes any one of which it needs (empty = open). */
    public record OtherCommand(String name, String description, String usage, List<String> aliases, List<String> anyOf) {
        public OtherCommand {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        }

        boolean answersTo(String label) {
            return name.equalsIgnoreCase(label) || aliases.stream().anyMatch(label::equalsIgnoreCase);
        }
    }

    private final CommandRegistry registry;
    private Supplier<List<OtherCommand>> otherCommands = List::of;
    private BiPredicate<CommandSender, Collection<String>> holdsAny = (sender, nodes) -> true;

    public HelpSubcommand(CommandRegistry registry) {
        this.registry = registry;
    }

    /** The other commands to list, and the cached check deciding which the sender sees. */
    public void setOtherCommands(Supplier<List<OtherCommand>> otherCommands, BiPredicate<CommandSender, Collection<String>> holdsAny) {
        this.otherCommands = otherCommands;
        this.holdsAny = holdsAny;
    }

    /** The other commands the sender may use (cached check). */
    public List<OtherCommand> otherCommandsFor(CommandSender sender) {
        return otherCommands.get().stream()
                .filter(command -> command.anyOf().isEmpty() || holdsAny.test(sender, command.anyOf()))
                .toList();
    }

    /** Every node the other commands need, asked for before a listing reads them from the cache. */
    public Set<String> otherCommandNodes() {
        Set<String> nodes = new java.util.LinkedHashSet<>();
        otherCommands.get().forEach(command -> nodes.addAll(command.anyOf()));
        return nodes;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            showCommandList(sender, 1);
        } else if (args[0].matches("\\d{1,4}")) {
            showCommandList(sender, Integer.parseInt(args[0]));
        } else {
            showCommandDetail(sender, args[0]);
        }
        return true;
    }

    /** Lines per page of {@code /knk help}, like the vanilla /help (KNG-107 live test: the list ran off the chat). */
    static final int PAGE_SIZE = 8;

    private void showCommandList(CommandSender sender, int requestedPage) {
        List<String> lines = new java.util.ArrayList<>();
        for (OtherCommand other : otherCommandsFor(sender)) {
            lines.add(ChatColor.AQUA + "/" + other.name() + ChatColor.GRAY + " - " + other.description());
        }
        for (CommandRegistry.RegisteredCommand cmd : registry.listAvailable(sender)) {
            if (!"help".equalsIgnoreCase(cmd.metadata().name())) {
                lines.add(ChatColor.AQUA + "/knk " + cmd.metadata().name() + ChatColor.GRAY + " - " + cmd.metadata().description());
            }
        }

        if (lines.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No commands available.");
            return;
        }

        int pages = (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = Math.max(1, Math.min(requestedPage, pages));
        sender.sendMessage(ChatColor.GOLD + "━━━ KnK Commands " + ChatColor.GRAY + "(page " + page + "/" + pages + ")"
                + ChatColor.GOLD + " ━━━");
        lines.subList((page - 1) * PAGE_SIZE, Math.min(lines.size(), page * PAGE_SIZE)).forEach(sender::sendMessage);
        if (pages > 1) {
            sender.sendMessage(pageFooter(page, pages));
        }
        sender.sendMessage(ChatColor.GRAY + "Use " + ChatColor.WHITE + "/knk help <command>" + 
                ChatColor.GRAY + " for details");
    }

    /** "« Previous | Page 2/4 | Next »", the arrows clickable. */
    private static net.kyori.adventure.text.Component pageFooter(int page, int pages) {
        var gray = net.kyori.adventure.text.format.NamedTextColor.GRAY;
        var link = net.kyori.adventure.text.format.NamedTextColor.AQUA;
        var footer = net.kyori.adventure.text.Component.text();
        if (page > 1) {
            footer.append(net.kyori.adventure.text.Component.text("« Previous", link)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/knk help " + (page - 1))))
                    .append(net.kyori.adventure.text.Component.text(" | ", gray));
        }
        footer.append(net.kyori.adventure.text.Component.text("Page " + page + "/" + pages, gray));
        if (page < pages) {
            footer.append(net.kyori.adventure.text.Component.text(" | ", gray))
                    .append(net.kyori.adventure.text.Component.text("Next »", link)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/knk help " + (page + 1))));
        }
        return footer.build();
    }

    private void showCommandDetail(CommandSender sender, String commandName) {
        String name = commandName.startsWith("/") ? commandName.substring(1) : commandName;
        var cmd = registry.get(name);
        if (cmd.isPresent() && !registry.isListed(sender, cmd.get())) {
            cmd = Optional.empty();
        }
        if (cmd.isEmpty()) {
            Optional<OtherCommand> other = otherCommandsFor(sender).stream().filter(o -> o.answersTo(name)).findFirst();
            if (other.isPresent()) {
                showOtherCommandDetail(sender, other.get());
                return;
            }
        }

        // KNG-107: a command the sender isn't offered is "unknown" here too, not described.
        if (cmd.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unknown command: " + name);
            sender.sendMessage(ChatColor.GRAY + "Use " + ChatColor.WHITE + "/knk help" + 
                    ChatColor.GRAY + " to see available commands");
            return;
        }

        CommandMetadata meta = cmd.get().metadata();
        sender.sendMessage(ChatColor.GOLD + "━━━ /knk " + meta.name() + " ━━━");
        sender.sendMessage(ChatColor.GRAY + meta.description());
        sender.sendMessage("");
        sender.sendMessage(ChatColor.AQUA + "Usage:");
        sender.sendMessage(ChatColor.WHITE + "  " + meta.usage());
        
        if (meta.permission() != null) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "Permission: " + ChatColor.GRAY + meta.permission());
        }
        
        if (!meta.examples().isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "Examples:");
            for (String example : meta.examples()) {
                sender.sendMessage(ChatColor.WHITE + "  " + example);
            }
        }

        if ("enchantments".equalsIgnoreCase(meta.name())) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "Subcommands:");
            sender.sendMessage(ChatColor.WHITE + "  /knk enchantments list [page] [size]");
            sender.sendMessage(ChatColor.WHITE + "  /knk enchantments vanilla [page] [size]");
            sender.sendMessage(ChatColor.WHITE + "  /knk enchantments search <id|key|displayName> <value> [page] [size]");
            sender.sendMessage(ChatColor.WHITE + "  /knk enchantments apply <id|vanillaName|customKey> [level]");

            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "Search fields:");
            sender.sendMessage(ChatColor.GRAY + "  id" + ChatColor.WHITE + " - exact numeric entity id");
            sender.sendMessage(ChatColor.GRAY + "  key" + ChatColor.WHITE + " - enchantment key, e.g. minecraft:sharpness");
            sender.sendMessage(ChatColor.GRAY + "  displayName" + ChatColor.WHITE + " - display name text");

            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "Apply notes:");
            sender.sendMessage(ChatColor.GRAY + "  - You must hold an item in your main hand");
            sender.sendMessage(ChatColor.GRAY + "  - Optional [level] allows unsafe levels for vanilla enchantments");
            sender.sendMessage(ChatColor.GRAY + "  - Numeric target = KnK enchantment definition id");
            sender.sendMessage(ChatColor.GRAY + "  - Text target = vanilla enchantment name/key");
            sender.sendMessage(ChatColor.GRAY + "  - Custom definitions (IsCustom=true) are applied as custom lore enchantments");
            sender.sendMessage(ChatColor.GRAY + "  - Use /knk enchantments vanilla to discover names");
            sender.sendMessage(ChatColor.GRAY + "  - Vanilla definitions resolve via baseEnchantmentNamespaceKey / minecraft:* key");
        }
    }

    private void showOtherCommandDetail(CommandSender sender, OtherCommand other) {
        sender.sendMessage(ChatColor.GOLD + "━━━ /" + other.name() + " ━━━");
        sender.sendMessage(ChatColor.GRAY + other.description());
        sender.sendMessage("");
        sender.sendMessage(ChatColor.AQUA + "Usage:");
        sender.sendMessage(ChatColor.WHITE + "  " + other.usage().replace("<command>", other.name()));
        if (!other.aliases().isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "Aliases: " + ChatColor.GRAY + "/" + String.join(", /", other.aliases()));
        }
    }

    /** Names {@code /knk help <tab>} offers: the listed subcommands and the other commands the sender may use. */
    public List<String> completionNames(CommandSender sender) {
        List<String> names = new java.util.ArrayList<>(registry.listAvailable(sender).stream().map(c -> c.metadata().name()).toList());
        otherCommandsFor(sender).forEach(other -> names.add(other.name().toLowerCase(Locale.ROOT)));
        return names;
    }
}
