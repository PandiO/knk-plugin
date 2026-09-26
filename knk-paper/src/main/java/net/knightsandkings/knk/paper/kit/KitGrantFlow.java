package net.knightsandkings.knk.paper.kit;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitPurchaseResult;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one code path for granting Kits (docs/specs/kits/DESIGN.md §7, CONTENT_PORT_PLAN.md CP2):
 * {@code /kit get|give|purchase} and the {@code kits.overview} menu's {@code kits.claim}/
 * {@code kits.purchase} actions all call this - neither calls the other. It does the
 * {@code knk.kit.*} node check ({@link KnkPermissible}), the server call (which owns every
 * per-kit rule: permission group/node, title, cooldown, cost - {@code KitService.ClaimKitAsync}),
 * {@link KitGrantPlacer} resolve + place, and the chat feedback.
 * <p>
 * Threading: may be called from the main thread; the API call and blueprint resolution run on
 * the data-access futures, and every Bukkit interaction (placement, messages) hops back through
 * {@code mainThread}. The returned future completes on the main thread, after the feedback was
 * sent, with {@code true} when the grant/purchase went through.
 */
public final class KitGrantFlow {

    public static final String GET_NODE = "knk.kit.get";
    public static final String GIVE_NODE = "knk.kit.give";
    public static final String PURCHASE_NODE = "knk.kit.purchase";

    private static final Pattern JSON_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Executor mainThread;
    private final KitsCommandApi kitsCommandApi;
    private final ItemBlueprintsDataAccess itemBlueprintsDataAccess;
    private final MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;
    private final KnkPermissible knkPermissible;
    private final UserCache userCache;

    public KitGrantFlow(
            Executor mainThread,
            KitsCommandApi kitsCommandApi,
            ItemBlueprintsDataAccess itemBlueprintsDataAccess,
            MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess,
            KnkPermissible knkPermissible,
            UserCache userCache
    ) {
        this.mainThread = mainThread;
        this.kitsCommandApi = kitsCommandApi;
        this.itemBlueprintsDataAccess = itemBlueprintsDataAccess;
        this.minecraftMaterialRefsDataAccess = minecraftMaterialRefsDataAccess;
        this.knkPermissible = knkPermissible;
        this.userCache = userCache;
    }

    /** Checks a {@code knk.kit.*} node through {@link KnkPermissible}; tells the player when denied. */
    public boolean requirePermission(Player player, String node) {
        if (knkPermissible.hasPermission(player, node)) {
            return true;
        }
        player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
        return false;
    }

    /**
     * The player's knk user id, or null when their account isn't cached yet. Reads the stale
     * entry on purpose (as {@code KnkPermissible}/{@code ModeService} do): a UUID's user id never
     * changes, and the short cache TTL would otherwise stop resolving it a minute after join.
     */
    public Integer resolveUserId(Player player) {
        return userCache.getStale(player.getUniqueId()).map(UserSummary::id).orElse(null);
    }

    /** {@link #resolveUserId} that tells the player when it can't resolve. */
    public Integer requireUserId(Player player) {
        Integer userId = resolveUserId(player);
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
        }
        return userId;
    }

    /** {@code /kit get <name>} and the menu's {@code kits.claim}: the player claims a kit for themself. */
    public CompletableFuture<Boolean> claim(Player player, int userId, int kitId, String kitName) {
        if (!requirePermission(player, GET_NODE)) {
            return CompletableFuture.completedFuture(false);
        }
        return grantAndPlace(kitsCommandApi.claimAsync(userId, kitId), player, player, kitName, "claimed");
    }

    /** {@code /kit give <player> <name>}: staff grant, no cooldown/cost (server-side rule). */
    public CompletableFuture<Boolean> give(Player sender, Player recipient, int recipientUserId, int kitId, String kitName) {
        if (!requirePermission(sender, GIVE_NODE)) {
            return CompletableFuture.completedFuture(false);
        }
        return grantAndPlace(kitsCommandApi.giveAsync(recipientUserId, kitId), sender, recipient, kitName, "given");
    }

    /** {@code /kit purchase <name>} and the menu's {@code kits.purchase}: one-time premium purchase (gems). */
    public CompletableFuture<Boolean> purchase(Player player, int userId, int kitId, String kitName) {
        if (!requirePermission(player, PURCHASE_NODE)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        kitsCommandApi.purchaseAsync(userId, kitId).whenComplete((result, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                printError(player, ex);
                done.complete(false);
                return;
            }
            printPurchaseResult(player, kitName, result);
            done.complete(result != null);
        }));
        return done;
    }

    private CompletableFuture<Boolean> grantAndPlace(
            CompletableFuture<KnkKitClaimResult> grant,
            CommandSender sender,
            Player recipient,
            String kitName,
            String verb
    ) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        grant.thenCompose(claimResult -> {
                    if (claimResult == null) {
                        return CompletableFuture.completedFuture((java.util.List<KitGrantPlacer.ResolvedItem>) null);
                    }
                    return KitGrantPlacer.resolveAsync(claimResult, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess);
                })
                .whenComplete((resolvedItems, ex) -> mainThread.execute(() -> {
                    if (ex != null) {
                        printError(sender, ex);
                        done.complete(false);
                        return;
                    }
                    if (resolvedItems == null) {
                        sender.sendMessage(ChatColor.RED + "No kit named \"" + kitName + "\" found.");
                        done.complete(false);
                        return;
                    }
                    if (!recipient.isOnline()) {
                        done.complete(false);
                        return;
                    }
                    KitGrantPlacer.PlacementSummary summary = KitGrantPlacer.place(recipient, resolvedItems);
                    sender.sendMessage(ChatColor.GREEN + "Kit \"" + kitName + "\" " + verb + " to " +
                            ChatColor.AQUA + recipient.getName() + ChatColor.GREEN + ".");
                    if (summary.displaced() > 0 || summary.dropped() > 0) {
                        sender.sendMessage(ChatColor.GRAY + "  placed=" + summary.placed() + " merged=" + summary.merged() +
                                " displaced=" + summary.displaced() + " dropped=" + summary.dropped());
                    }
                    if (!recipient.equals(sender)) {
                        recipient.sendMessage(ChatColor.GREEN + "You received the \"" + kitName + "\" kit.");
                    }
                    done.complete(true);
                }));
        return done;
    }

    private void printPurchaseResult(CommandSender sender, String kitName, KnkKitPurchaseResult result) {
        if (result == null) {
            sender.sendMessage(ChatColor.RED + "No kit named \"" + kitName + "\" found.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Purchased \"" + kitName + "\" for " + ChatColor.GOLD +
                result.gemsPaid() + " gems" + ChatColor.GREEN + ". Use /kit get " + kitName + " to claim it.");
    }

    /**
     * Shows a failed call. The api-client wraps its {@link ApiException} in a
     * {@code RuntimeException("Failed to claim kit …")} inside the future's
     * {@code CompletionException}, so the whole cause chain is searched. A server-side denial
     * ({@code 409 {"code": "ClaimDenied", "message": …}} from {@code KitsController}) is shown as
     * its message; any other API error as status + body; anything else as its message.
     */
    public static void printError(CommandSender sender, Throwable ex) {
        Throwable outer = ex;
        while ((outer instanceof CompletionException || outer instanceof java.util.concurrent.ExecutionException)
                && outer.getCause() != null) {
            outer = outer.getCause();
        }
        ApiException apiEx = null;
        for (Throwable t = outer; t != null && apiEx == null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiException found) {
                apiEx = found;
            }
        }

        if (apiEx != null) {
            String denial = denialMessage(apiEx);
            if (denial != null) {
                sender.sendMessage(ChatColor.RED + denial);
            } else if (apiEx.getStatusCode() > 0) {
                sender.sendMessage(ChatColor.RED + "HTTP " + apiEx.getStatusCode());
                if (apiEx.getResponseBody() != null && !apiEx.getResponseBody().isEmpty()) {
                    sender.sendMessage(ChatColor.RED + apiEx.getResponseBody());
                }
            } else {
                sender.sendMessage(ChatColor.RED + apiEx.getMessage());
            }
        } else {
            sender.sendMessage(ChatColor.RED + (outer.getMessage() != null ? outer.getMessage() : outer.getClass().getSimpleName()));
        }
    }

    /** The {@code message} of a 409 denial body, or null when the error isn't one. */
    static String denialMessage(ApiException apiEx) {
        if (apiEx.getStatusCode() != 409 || apiEx.getResponseBody() == null) {
            return null;
        }
        Matcher matcher = JSON_MESSAGE.matcher(apiEx.getResponseBody());
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
