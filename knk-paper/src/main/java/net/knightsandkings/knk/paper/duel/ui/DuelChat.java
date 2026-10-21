package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeViolation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * The duel chat lines (arena duels DESIGN §6.4): offers with id-stamped buttons ({@code /duel accept <offerId>}, so
 * a stale button can't accept an old offer, V5), the 10 s expiry warning, and the {@code KIT_CHOICE} prompt. Drop
 * mode is spelled out in capitals. Pure Adventure components.
 */
public final class DuelChat {

    /** The warning goes out when this many seconds are left on a turn. */
    public static final int EXPIRY_WARNING_SECONDS = 10;

    static final NamedTextColor ACCENT = NamedTextColor.GOLD;
    static final NamedTextColor TEXT = NamedTextColor.YELLOW;
    static final NamedTextColor GOOD = NamedTextColor.GREEN;
    static final NamedTextColor BAD = NamedTextColor.RED;
    static final NamedTextColor MUTED = NamedTextColor.GRAY;

    private DuelChat() {
    }

    /**
     * One offer as its receiver sees it.
     *
     * @param authorStake what the author stakes ("Diamond Sword ★★★, 200 coins"), receiverStake what the receiver does
     */
    public record OfferText(long offerId, String authorName, boolean counter, String arenaName, int number, int maxOffers,
                            int seconds, GearMode gearMode, String kitName, String receiverStake, String authorStake,
                            boolean canCounter) {}

    /** "⚔ Pandi challenges you to a duel at the Arena (offer 1/3, 60 s) — own gear, …" + stakes + buttons. */
    public static Component offer(OfferText o) {
        String verb = o.counter() ? " counters with a new duel offer at " : " challenges you to a duel at ";
        String turn = o.number() >= o.maxOffers() ? "last offer" : "offer " + o.number() + "/" + o.maxOffers();
        return Component.text()
                .append(Component.text("⚔ ", ACCENT))
                .append(Component.text(o.authorName(), ACCENT, TextDecoration.BOLD))
                .append(Component.text(verb + o.arenaName() + " (" + turn + ", " + o.seconds() + " s) — ", TEXT))
                .append(gearMode(o.gearMode(), o.kitName()))
                .append(Component.newline())
                .append(Component.text("   You stake: ", MUTED)).append(Component.text(o.receiverStake(), NamedTextColor.WHITE))
                .append(Component.text("    " + o.authorName() + " stakes: ", MUTED))
                .append(Component.text(o.authorStake(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("   ")).append(buttons(o.offerId(), o.canCounter()))
                .build();
    }

    /** What the author sees once the offer went out. */
    public static Component sent(String receiverName, int number, int maxOffers, int seconds, GearMode mode, String kitName) {
        return Component.text()
                .append(Component.text("⚔ Duel offer " + number + "/" + maxOffers + " sent to " + receiverName
                        + "; they have " + seconds + " s to answer — ", TEXT))
                .append(gearMode(mode, kitName))
                .append(Component.text(" "))
                .append(button("[Cancel]", BAD, "/duel cancel", "Withdraw the duel offer"))
                .build();
    }

    /** "Pandi's duel offer expires in 10 s" with the same buttons. */
    public static Component expiryWarning(long offerId, String authorName, int seconds, boolean canCounter) {
        return Component.text()
                .append(Component.text("⚔ " + authorName + "'s duel offer expires in " + seconds + " s. ", BAD))
                .append(buttons(offerId, canCounter))
                .build();
    }

    /** [View] [Accept] [Counter] [Decline]; no Counter on the last allowed offer (D2). */
    public static Component buttons(long offerId, boolean canCounter) {
        TextComponent.Builder row = Component.text()
                .append(button("[View]", NamedTextColor.AQUA, "/duel view " + offerId, "Open the offer"))
                .append(Component.text(" "))
                .append(button("[Accept]", GOOD, "/duel accept " + offerId, "Accept this offer: the stakes are locked in"));
        if (canCounter) {
            row.append(Component.text(" "))
                    .append(button("[Counter]", ACCENT, "/duel counter " + offerId, "Answer with your own offer"));
        }
        return row.append(Component.text(" "))
                .append(button("[Decline]", BAD, "/duel decline " + offerId, "Turn the duel down"))
                .build();
    }

    /** The gear mode for chat; drop mode in red capitals so nobody accepts it by accident. */
    public static Component gearMode(GearMode mode, String kitName) {
        return switch (mode) {
            case OWN_GEAR_KEEP -> Component.text("own gear, inventory kept on death", GOOD);
            case OWN_GEAR_DROP -> Component.text("OWN GEAR, THE LOSER'S INVENTORY DROPS TO THE WINNER", BAD, TextDecoration.BOLD);
            case FIXED_KIT -> Component.text("kit " + (kitName == null ? "?" : kitName)
                    + " (your own gear is stored and given back)", NamedTextColor.AQUA);
            case KIT_CHOICE -> Component.text("each duelist picks a kit (your own gear is stored and given back)",
                    NamedTextColor.AQUA);
        };
    }

    /** The gear mode's meaning, for the editor's lore. */
    public static List<String> gearModeLore(GearMode mode) {
        return switch (mode) {
            case OWN_GEAR_KEEP -> List.of("Fight in your own gear.", "Nobody loses their inventory.");
            case OWN_GEAR_DROP -> List.of("Fight in your own gear.", "THE LOSER'S WHOLE INVENTORY", "GOES TO THE WINNER.");
            case FIXED_KIT -> List.of("Both fight with the kit below.", "Your own gear is stored and", "given back afterwards.");
            case KIT_CHOICE -> List.of("Each of you picks a kit in the", "warmup. Your own gear is stored", "and given back afterwards.");
        };
    }

    public static String gearModeName(GearMode mode) {
        return switch (mode) {
            case OWN_GEAR_KEEP -> "Own gear (keep)";
            case OWN_GEAR_DROP -> "OWN GEAR — DROP";
            case FIXED_KIT -> "Fixed kit";
            case KIT_CHOICE -> "Kit choice";
        };
    }

    /** "Diamond Sword ★★★, 200 coins", or "nothing". */
    public static String stake(long coins, List<String> items) {
        List<String> parts = new ArrayList<>(items);
        if (coins > 0) parts.add(coins(coins) + " coins");
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    public static String coins(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }

    /**
     * The {@code KIT_CHOICE} prompt in the warmup: one clickable button per eligible kit ({@code /duel kit <id>}), the
     * current pick marked, and the opponent's pick when they made one.
     */
    public static Component kitPrompt(List<Integer> kitIds, IntFunction<String> names, Integer current,
                                      String opponentName, Integer opponentPick) {
        TextComponent.Builder line = Component.text()
                .append(Component.text("⚔ Pick your kit: ", ACCENT));
        for (int kitId : kitIds) {
            boolean picked = current != null && current == kitId;
            line.append(button((picked ? "[✔ " : "[") + names.apply(kitId) + "]", picked ? GOOD : NamedTextColor.AQUA,
                    "/duel kit " + kitId, picked ? "Your kit" : "Fight with this kit")).append(Component.text(" "));
        }
        line.append(button("[Menu]", MUTED, "/duel kit", "Open the kit picker"));
        if (opponentPick != null) {
            line.append(Component.newline()).append(Component.text("   " + opponentName + " picked "
                    + names.apply(opponentPick) + ".", MUTED));
        }
        return line.build();
    }

    /** One player-facing line per violation (DESIGN §6.3: each failed check names the reason). */
    public static String violation(StakeViolation v, String authorName, String otherName, boolean authorIsChallenger) {
        String who = v.side() == null ? "The offer"
                : (v.side() == DuelSide.CHALLENGER) == authorIsChallenger ? "Your stake" : otherName + "'s stake";
        return switch (v.code()) {
            case GEAR_MODE_NOT_ALLOWED -> "This arena doesn't allow that gear mode.";
            case FIXED_KIT_MISSING -> "Pick the kit for a fixed-kit duel.";
            case FIXED_KIT_NOT_ELIGIBLE -> "That kit isn't one of this arena's kits.";
            case KIT_NOT_EXPECTED -> "A kit only goes with the fixed-kit mode.";
            case NO_ELIGIBLE_KITS -> "This arena has no kits for that gear mode.";
            case COINS_NOT_ALLOWED -> "This arena doesn't allow coin stakes.";
            case ITEMS_NOT_ALLOWED -> "This arena doesn't allow item stakes.";
            case NEGATIVE_COINS -> who + " can't be negative.";
            case COINS_OVER_MAX -> who + " is over the maximum of " + v.detail() + " coins.";
            case STAKE_RATIO -> who + " is too small: it must be worth at least " + v.detail() + " coins.";
            case TOO_MANY_STACKS -> who + " has more than " + v.detail() + " stacks.";
            case ITEM_BLOCKED -> who + " has an item that can't be staked (" + v.detail() + ").";
            case DUPLICATE_ITEM -> who + " lists the same item twice.";
            case ITEM_ADDED_BY_OTHER_SIDE -> "Only " + otherName + " can add items to their stake.";
        };
    }

    static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text(label, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, MUTED)));
    }
}
