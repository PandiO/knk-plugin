package net.knightsandkings.knk.paper.commands;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.paper.production.terminal.ContainerBlocks;
import net.knightsandkings.knk.paper.production.terminal.StorageTerminalModule;

/**
 * {@code /knk storage} (PandiO/knk-workspace#74, production DESIGN § 6.2 and § 11.2, node {@value #PERMISSION}): bind the
 * chest, trapped chest or barrel the sender looks at to a storage, as a terminal or a display, and undo it.
 * <ol>
 *   <li>{@code bind <storage id> <terminal|display> [label]}: {@code POST api/StorageContainers} with the canonical block
 *       (the left half of a double chest); the block must be empty, since its own inventory is not stock.</li>
 *   <li>{@code unbind}: {@code DELETE api/StorageContainers/{id}}; a display's sample is cleared.</li>
 *   <li>{@code info}: the binding of the block looked at.</li>
 *   <li>{@code reload}: re-reads every binding now (after binding in the web app).</li>
 * </ol>
 * With {@code production.enabled: false} every form answers that production is off.
 */
public final class StorageAdminCommand {

    public static final String PERMISSION = ProductionAdminCommand.PERMISSION;
    /** How far the sender may look at the block. */
    static final int REACH = 6;

    private final Supplier<StorageTerminalModule> module;
    private final Plugin plugin;

    public StorageAdminCommand(Supplier<StorageTerminalModule> module, Plugin plugin) {
        this.module = module;
        this.plugin = plugin;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "storage",
                "Bind the chest or barrel you look at to a storage as a terminal or display, or unbind it (production.enabled)",
                "/knk storage <bind <storage id> <terminal|display> [label]|unbind|info|reload>",
                PERMISSION,
                List.of("/knk storage bind 12 terminal", "/knk storage bind 12 display North hall", "/knk storage info",
                        "/knk storage unbind"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("bind", "unbind", "info", "reload"), args[0]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bind")) {
            return filter(List.of("terminal", "display"), args[2]);
        }
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        StorageTerminalModule terminals = module.get();
        if (terminals == null) {
            sender.sendMessage(ChatColor.RED + "Production is off on this server (production.enabled: false).");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
            return true;
        }
        String form = args[0].toLowerCase(Locale.ROOT);
        if (form.equals("reload")) {
            sender.sendMessage(ChatColor.GRAY + "Re-reading the bound containers...");
            terminals.reload("command").whenComplete((count, error) -> reply(sender, error != null
                    ? ChatColor.RED + "Could not read them: " + error.getMessage()
                    : ChatColor.GREEN + "" + count + " bound container(s)."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can point at a container.");
            return true;
        }
        Block target = player.getTargetBlockExact(REACH);
        if (!ContainerBlocks.bindable(target)) {
            player.sendMessage(ChatColor.RED + "Look at a chest, trapped chest or barrel (within " + REACH + " blocks).");
            return true;
        }
        Block block = ContainerBlocks.canonical(target);
        switch (form) {
            case "bind" -> bind(player, terminals, block, args);
            case "unbind" -> unbind(player, terminals, block);
            case "info" -> info(player, terminals, block);
            default -> player.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
        }
        return true;
    }

    private void bind(Player player, StorageTerminalModule terminals, Block block, String[] args) {
        Integer storageId = args.length >= 2 ? parse(args[1]) : null;
        StorageContainerInfo.Mode mode = args.length >= 3 ? StorageContainerInfo.Mode.parse(args[2]) : null;
        if (storageId == null || mode == null) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /knk storage bind <storage id> <terminal|display> [label]");
            return;
        }
        if (terminals.bindingOf(block) != null) {
            player.sendMessage(ChatColor.RED + "This block is already bound; unbind it first.");
            return;
        }
        var inventory = ContainerBlocks.inventory(block);
        if (inventory != null && !inventory.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Empty the container first: its own items are not stock and would be lost.");
            return;
        }
        String label = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
        String type = ContainerBlocks.containerType(block.getType());
        boolean isDouble = ContainerBlocks.isDouble(block);
        BindRequest request = new BindRequest(storageId, mode, type, isDouble, block.getWorld().getName(), block.getX(),
                block.getY(), block.getZ(), label);
        player.sendMessage(ChatColor.GRAY + "Binding " + type + (isDouble ? " (double)" : "") + " at " + block.getX() + " "
                + block.getY() + " " + block.getZ() + " to storage #" + storageId + " as a " + mode.apiName() + "...");
        terminals.api().bind(request).whenComplete((info, error) -> reply(player, () -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + "Not bound: " + describe(error));
                return;
            }
            terminals.index().put(info);
            player.sendMessage(ChatColor.GREEN + "Bound to " + info.displayName() + " as a " + info.mode().apiName()
                    + " (" + info.slots() + " slots, container #" + info.id() + ").");
            if (info.mode() == StorageContainerInfo.Mode.DISPLAY) {
                terminals.requestDisplay(info);
            }
        }));
    }

    private void unbind(Player player, StorageTerminalModule terminals, Block block) {
        StorageContainerInfo info = terminals.bindingOf(block);
        if (info == null) {
            player.sendMessage(ChatColor.RED + "This block is not bound (try /knk storage reload after a web app change).");
            return;
        }
        terminals.api().unbind(info.id()).whenComplete((ignored, error) -> reply(player, () -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + "Not unbound: " + describe(error));
                return;
            }
            terminals.index().remove(info.id());
            if (info.mode() == StorageContainerInfo.Mode.DISPLAY) {
                var inventory = ContainerBlocks.inventory(block);
                if (inventory != null) {
                    inventory.clear();
                }
            }
            player.sendMessage(ChatColor.GREEN + "Unbound " + info.displayName() + " (container #" + info.id() + ").");
        }));
    }

    private void info(Player player, StorageTerminalModule terminals, Block block) {
        StorageContainerInfo known = terminals.bindingOf(block);
        terminals.api().byBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .whenComplete((found, error) -> reply(player, () -> {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + "Could not ask the API: " + describe(error));
                        return;
                    }
                    if (found.isEmpty()) {
                        player.sendMessage(ChatColor.GRAY + "Not bound." + (known != null
                                ? ChatColor.YELLOW + " (this server still had it; run /knk storage reload)" : ""));
                        return;
                    }
                    StorageContainerInfo info = found.get();
                    player.sendMessage(ChatColor.GRAY + "Container #" + info.id() + ": " + ChatColor.WHITE + info.mode().apiName()
                            + ChatColor.GRAY + " of " + ChatColor.WHITE + info.displayName() + ChatColor.GRAY + " (storage #"
                            + info.storageId() + ", " + info.containerType() + (info.isDouble() ? ", double" : "") + ", "
                            + info.slots() + " slots)" + (known == null ? ChatColor.YELLOW + " (not loaded here yet; run /knk storage reload)" : ""));
                }));
    }

    private void reply(CommandSender sender, String message) {
        reply(sender, () -> sender.sendMessage(message));
    }

    private void reply(CommandSender sender, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    static String describe(Throwable error) {
        int status = statusOf(error);
        return switch (status) {
            case 0 -> String.valueOf(rootMessage(error));
            case 400 -> "the API refused it (a system or transit storage, a double barrel, or an unknown storage)";
            case 403 -> "not allowed (403)";
            case 404 -> "not found";
            case 409 -> "that block is already bound";
            default -> "status " + status;
        };
    }

    private static int statusOf(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof net.knightsandkings.knk.core.exception.ApiException api) {
                return api.getStatusCode();
            }
            cause = cause.getCause();
        }
        return 0;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static Integer parse(String value) {
        try {
            int id = Integer.parseInt(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }
}
