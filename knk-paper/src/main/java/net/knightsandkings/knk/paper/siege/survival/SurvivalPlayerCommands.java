package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.economy.LedgerResult;
import net.knightsandkings.knk.core.survival.kits.KitSelection;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.survival.kits.SurvivalKitPicker;
import net.knightsandkings.knk.paper.siege.survival.purchase.PurchaseShop;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The player-side survival subcommands of {@code /siege} (link S7b; DESIGN §8, §11): {@code balance} (the own match
 * balance), {@code pay <player> <amount>} (a teammate transfer, same live match, atomic, idempotent), {@code kit [name]}
 * (the kit picker / a pick by name before the match starts) and {@code shop} (the purchase menu of the point in range).
 * The runtime is read lazily because {@code /siege} is registered before it exists.
 */
public final class SurvivalPlayerCommands {

    public static final List<String> SUBCOMMANDS = List.of("balance", "pay", "kit", "shop");

    private final Supplier<SurvivalMatchRuntime> runtime;
    private final java.util.function.Function<String, Player> playerByName;

    public SurvivalPlayerCommands(Supplier<SurvivalMatchRuntime> runtime) {
        this(runtime, Bukkit::getPlayerExact);
    }

    SurvivalPlayerCommands(Supplier<SurvivalMatchRuntime> runtime, java.util.function.Function<String, Player> playerByName) {
        this.runtime = runtime;
        this.playerByName = playerByName;
    }

    public Component balance(Player player) {
        Optional<SurvivalMatch> match = matchOf(player);
        if (match.isEmpty()) return SiegeMessages.bad("You are not in a running survival match.");
        SurvivalMatch m = match.get();
        long balance = m.economy().balance(player.getUniqueId());
        return SiegeMessages.info("Match balance: " + balance + " coins (" + m.economy().kills(player.getUniqueId()) + " kills, "
            + m.economy().settings().matchCurrencyPerKill() + " per kill, ×" + m.economy().settings().bonusMultiplier() + " on a headshot/critical)"
            + (m.ghosts().isGhost(player.getUniqueId()) ? " — you are a ghost: no buying, rolling or transfers until you respawn." : "."));
    }

    public Component pay(Player player, String targetName, String amountText) {
        Optional<SurvivalMatch> match = matchOf(player);
        if (match.isEmpty()) return SiegeMessages.bad("You are not in a running survival match.");
        if (targetName == null || amountText == null) return SiegeMessages.bad("Usage: /siege pay <player> <amount>");
        long amount;
        try {
            amount = Long.parseLong(amountText.trim());
        } catch (NumberFormatException e) {
            return SiegeMessages.bad("The amount must be a whole number of match coins.");
        }
        Player target = playerByName.apply(targetName);
        if (target == null) return SiegeMessages.bad("No online player named '" + targetName + "'.");
        SurvivalMatch m = match.get();
        LedgerResult result = m.economy().transfer("pay-" + UUID.randomUUID(), player.getUniqueId(), target.getUniqueId(), amount);
        if (result instanceof LedgerResult.Rejected rejected) {
            return SiegeMessages.bad(switch (rejected.reason()) {
                case AMOUNT_NOT_POSITIVE -> "The amount must be positive.";
                case RECIPIENT_NOT_IN_MATCH -> target.getName() + " is not in your match.";
                case SELF_TRANSFER -> "You cannot pay yourself.";
                case PAYER_IS_GHOST -> "Ghosts cannot transfer coins.";
                case RECIPIENT_IS_GHOST -> target.getName() + " is a ghost and cannot receive coins now.";
                case INSUFFICIENT_BALANCE -> "Not enough match coins: you have " + m.economy().balance(player.getUniqueId()) + ".";
                case MATCH_ENDED -> "The match is over.";
                default -> "Transfer refused: " + rejected.reason();
            });
        }
        long after = ((LedgerResult.Accepted) result).balanceAfter();
        target.sendMessage(SiegeMessages.good(player.getName() + " paid you " + amount + " match coins (balance " + m.economy().balance(target.getUniqueId()) + ")."));
        return SiegeMessages.good("Paid " + amount + " match coins to " + target.getName() + " (balance " + after + ").");
    }

    public Component kit(Player player, String name) {
        SurvivalMatchRuntime rt = runtime.get();
        if (rt == null) return SiegeMessages.bad("Siege Survival is not running.");
        Optional<SiegeLobbyRuntime> lobby = rt.lobbyOfMember(player.getUniqueId());
        if (lobby.isEmpty()) return SiegeMessages.bad("You are not in a siege lobby.");
        Optional<SurvivalKitPicker> picker = rt.kitPicker();
        if (picker.isEmpty()) return SiegeMessages.bad("Kits are not available.");
        Optional<KitSelection> selection = picker.get().selection(lobby.get().id());
        if (selection.isEmpty()) return SiegeMessages.bad("This lobby's scenario does not use kits (or the match is not at the hub yet).");
        if (name == null || name.isBlank()) {
            Component problem = picker.get().open(player, lobby.get().id());
            return problem != null ? problem : SiegeMessages.info("Kits: " + String.join(", ", SurvivalKitPicker.names(selection.get()))
                + ". Pick in the menu or with /siege kit <name>.");
        }
        return picker.get().pickByName(player.getUniqueId(), lobby.get().id(), name);
    }

    public Component shop(Player player) {
        Optional<SurvivalMatch> match = matchOf(player);
        if (match.isEmpty()) return SiegeMessages.bad("You are not in a running survival match.");
        SurvivalMatchRuntime rt = runtime.get();
        Optional<PurchaseShop> shop = rt == null ? Optional.empty() : rt.shopOf(match.get());
        if (shop.isEmpty()) return SiegeMessages.bad("This match has no purchase points.");
        var point = shop.get().pointNear(player);
        if (point.isEmpty()) return SiegeMessages.bad("No purchase point within reach; walk up to one.");
        Component problem = shop.get().open(player, point.get());
        return problem;
    }

    private Optional<SurvivalMatch> matchOf(Player player) {
        SurvivalMatchRuntime rt = runtime.get();
        return rt == null ? Optional.empty() : rt.matchOfPlayer(player.getUniqueId());
    }
}
