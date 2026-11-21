package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import net.knightsandkings.knk.paper.production.shop.ShopDisplayModule;
import net.knightsandkings.knk.paper.production.shop.ShopService;

/**
 * {@code /knk shop} (node {@value #PERMISSION}). With {@code production.enabled: false} it answers that production is off.
 * <ol>
 *   <li>{@code /knk shop <shop id>} (PandiO/knk-workspace#687): opens a shop's menu for the sender, for testing until
 *       shopkeepers (P11, #249) open it.</li>
 *   <li>{@code /knk shop display add <shop id> [label]} (display stalls P12, #250): registers the block the sender looks at
 *       as a display spot of the shop, facing the sender.</li>
 *   <li>{@code /knk shop display remove [spot id]}: removes the spot (the one looked at when no id is given).</li>
 *   <li>{@code /knk shop display pin <spot id> <entry id|none>}: pins a catalogue entry of the spot's shop to the spot.</li>
 *   <li>{@code /knk shop display list <shop id>}: the shop's spots and what each shows.</li>
 *   <li>{@code /knk shop display reload}: re-reads the spots (after an edit in the web app).</li>
 * </ol>
 */
public final class ShopAdminCommand {

    public static final String PERMISSION = ProductionAdminCommand.PERMISSION;
    static final List<String> DISPLAY_ACTIONS = List.of("add", "remove", "pin", "list", "reload");

    private final Supplier<ShopService> shops;
    private final Supplier<ShopDisplayModule> displays;

    public ShopAdminCommand(Supplier<ShopService> shops) {
        this(shops, () -> null);
    }

    public ShopAdminCommand(Supplier<ShopService> shops, Supplier<ShopDisplayModule> displays) {
        this.shops = shops;
        this.displays = displays != null ? displays : () -> null;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "shop",
                "Open a shop's menu for testing, or register and remove its display stalls (production.enabled)",
                "/knk shop <shop id> | /knk shop display <add <shop id> [label]|remove [spot id]|pin <spot id> <entry id|none>|list <shop id>|reload>",
                PERMISSION,
                List.of("/knk shop 12", "/knk shop display add 12 Front shelf", "/knk shop display remove",
                        "/knk shop display pin 3 41", "/knk shop display list 12"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return "display".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("display") : List.of();
        }
        if (args.length == 2 && "display".equalsIgnoreCase(args[0])) {
            List<String> out = new ArrayList<>();
            for (String action : DISPLAY_ACTIONS) {
                if (action.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(action);
                }
            }
            return out;
        }
        if (args.length == 4 && "display".equalsIgnoreCase(args[0]) && "pin".equalsIgnoreCase(args[1])) {
            return "none".startsWith(args[3].toLowerCase(Locale.ROOT)) ? List.of("none") : List.of();
        }
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length >= 1 && "display".equalsIgnoreCase(args[0])) {
            return display(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        ShopService service = shops.get();
        if (service == null) {
            sender.sendMessage(ChatColor.RED + "Production is off on this server (production.enabled: false).");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open a shop.");
            return true;
        }
        Integer shopId = args.length == 1 ? parse(args[0]) : null;
        if (shopId == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
            return true;
        }
        player.sendMessage(ChatColor.GRAY + "Opening shop #" + shopId + "...");
        service.open(player, shopId);
        return true;
    }

    private boolean display(CommandSender sender, String[] args) {
        ShopDisplayModule module = displays.get();
        if (module == null) {
            sender.sendMessage(ChatColor.RED + "Production is off on this server (production.enabled: false).");
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> add(sender, module, args);
            case "remove" -> remove(sender, module, args);
            case "pin" -> pin(sender, module, args);
            case "list" -> list(sender, module, args);
            case "reload" -> {
                sender.sendMessage(ChatColor.GRAY + "Re-reading the display spots...");
                module.reload("command").whenComplete((count, error) -> sender.sendMessage(error == null
                        ? ChatColor.GREEN + "Display spots re-read: " + count + "."
                        : ChatColor.RED + "Could not read the display spots: " + ChatColor.WHITE + message(error)));
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /knk shop display <add <shop id> [label]|remove [spot id]"
                    + "|pin <spot id> <entry id|none>|list <shop id>|reload>");
        }
        return true;
    }

    private void add(CommandSender sender, ShopDisplayModule module, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can register a display spot (look at the block).");
            return;
        }
        Integer shopId = args.length >= 2 ? parse(args[1]) : null;
        if (shopId == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk shop display add <shop id> [label] (look at the shelf or stall block)");
            return;
        }
        String label = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
        module.register(player, shopId, label).whenComplete((spot, error) -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + "Not registered: " + ChatColor.WHITE + message(error));
            } else {
                player.sendMessage(ChatColor.GREEN + "Display spot " + spot.describe() + " registered for shop #" + spot.shopId()
                        + ", facing " + spot.facing().apiName().toLowerCase(Locale.ROOT) + ".");
            }
        });
    }

    private void remove(CommandSender sender, ShopDisplayModule module, String[] args) {
        Integer spotId = args.length >= 2 ? parse(args[1]) : null;
        if (spotId == null) {
            Optional<DisplaySpot> looked = sender instanceof Player player ? module.target(player) : Optional.empty();
            if (looked.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /knk shop display remove [spot id] (or look at the spot's block)");
                return;
            }
            spotId = looked.get().id();
        }
        int id = spotId;
        module.remove(id).whenComplete((spot, error) -> sender.sendMessage(error != null
                ? ChatColor.RED + "Not removed: " + ChatColor.WHITE + message(error)
                : ChatColor.GREEN + "Display spot " + (spot == null ? "#" + id : spot.describe()) + " removed."));
    }

    private void pin(CommandSender sender, ShopDisplayModule module, String[] args) {
        Integer spotId = args.length >= 3 ? parse(args[1]) : null;
        boolean clear = args.length >= 3 && "none".equalsIgnoreCase(args[2]);
        Integer entryId = args.length >= 3 && !clear ? parse(args[2]) : null;
        if (spotId == null || (!clear && entryId == null)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk shop display pin <spot id> <catalogue entry id|none>");
            return;
        }
        module.pin(spotId, clear ? null : entryId).whenComplete((spot, error) -> sender.sendMessage(error != null
                ? ChatColor.RED + "Not pinned: " + ChatColor.WHITE + message(error)
                : ChatColor.GREEN + "Display spot " + spot.describe() + (spot.pinnedCatalogueEntryId() == null
                        ? " is filled by the shop again." : " now shows catalogue entry " + spot.pinnedCatalogueEntryId() + ".")));
    }

    private void list(CommandSender sender, ShopDisplayModule module, String[] args) {
        Integer shopId = args.length >= 2 ? parse(args[1]) : null;
        if (shopId == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk shop display list <shop id>");
            return;
        }
        List<String> lines = module.describe(shopId);
        if (lines.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Shop #" + shopId + " has no display spots (last read: " + module.lastRefresh() + ").");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Display spots of shop #" + shopId + ":");
        lines.forEach(line -> sender.sendMessage(ChatColor.GRAY + "  " + line));
    }

    /** The API's error text for a refusal (409/400 body), else the failure. */
    static String message(Throwable error) {
        Throwable t = error;
        while (t != null && t.getCause() != null && (t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException)) {
            t = t.getCause();
        }
        if (t instanceof net.knightsandkings.knk.core.exception.ApiException api && api.getResponseBody() != null) {
            String body = api.getResponseBody();
            int at = body.indexOf("\"error\"");
            if (at >= 0) {
                int start = body.indexOf('"', body.indexOf(':', at) + 1);
                int end = start < 0 ? -1 : body.indexOf('"', start + 1);
                if (start >= 0 && end > start) {
                    return body.substring(start + 1, end);
                }
            }
            return "HTTP " + api.getStatusCode();
        }
        return t == null || t.getMessage() == null ? "no answer" : t.getMessage();
    }

    static Integer parse(String value) {
        try {
            int id = Integer.parseInt(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
