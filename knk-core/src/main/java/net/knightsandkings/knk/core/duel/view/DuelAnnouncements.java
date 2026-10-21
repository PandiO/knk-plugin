package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.GearMode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The duel chat lines (arena duels DESIGN §9.1): who is told what, the spam limiter, and the text. Pure; the Paper
 * {@code DuelAnnouncer} adds the clickable {@code [Spectate] [Details]} buttons and sends the lines.
 * <ul>
 *   <li><b>Warmup start</b>: broadcast to everyone when the arena announces this kind of match and neither duelist
 *       had a warmup broadcast in the last {@code duels.announce.cooldown-seconds} ({@link Cooldown}).</li>
 *   <li><b>Result</b> and <b>draw</b>: broadcast under the same arena toggles (no cooldown; one per match). A held
 *       result says "pending review".</li>
 *   <li><b>Cancel</b>, abort and the death line: only the duelists and the players in the arena's spectator
 *       areas.</li>
 * </ul>
 * Templates use {@code &} colours and the placeholders {@code {arena} {challenger} {opponent} {gear} {stakes}
 * {winner} {loser} {time} {won} {reason}}.
 */
public final class DuelAnnouncements {

    public static final String DEFAULT_WARMUP =
            "&6⚔ Duel at &e{arena}&6: &e{challenger} &6vs &e{opponent} &7- {gear} - stakes: &f{stakes}";
    public static final String DEFAULT_RESULT = "&6⚔ &e{winner} &6defeated &e{loser} &6in &f{time}{won}&6.";
    public static final String DEFAULT_FORFEIT = "&6⚔ &e{winner} &6won by forfeit against &e{loser}{won}&6.";
    public static final String DEFAULT_DRAW = "&6⚔ &e{challenger} &6and &e{opponent} &6drew at &e{arena} &7({reason})&6.";
    public static final String DEFAULT_CANCEL = "&7The duel &f{challenger} &7vs &f{opponent} &7was cancelled: {reason}";
    public static final String DEFAULT_DEATH = "&c{loser} &7was defeated by &c{winner}&7.";
    public static final String PENDING_REVIEW = " &7(pending review)";

    /** The templates, each falling back to its default when blank. */
    public record Templates(String warmup, String result, String forfeit, String draw, String cancel, String death) {
        public Templates {
            warmup = orDefault(warmup, DEFAULT_WARMUP);
            result = orDefault(result, DEFAULT_RESULT);
            forfeit = orDefault(forfeit, DEFAULT_FORFEIT);
            draw = orDefault(draw, DEFAULT_DRAW);
            cancel = orDefault(cancel, DEFAULT_CANCEL);
            death = orDefault(death, DEFAULT_DEATH);
        }

        public static Templates defaults() {
            return new Templates(null, null, null, null, null, null);
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** What the API did with a decisive result, as far as the result line is concerned. */
    public enum Payout {
        /** Paid (or a friendly duel, which pays nothing). */
        PAID,
        /** Held for staff review (tier 2): "pending review". */
        HELD,
        /** The API couldn't be reached yet; the payout follows. */
        PENDING,
        /** Nothing moved (the API refunded the stakes): the line names no winnings. */
        NONE
    }

    private DuelAnnouncements() {
    }

    /**
     * The arena's toggles (DESIGN §4.2 Announcements): {@code AnnounceMatches} turns the broadcasts off for the
     * arena; {@code AnnounceFriendly} also covers duels without stakes.
     */
    public static boolean arenaAnnounces(boolean announceMatches, boolean announceFriendly, boolean friendly) {
        return announceMatches && (!friendly || announceFriendly);
    }

    public static String warmup(Templates t, DuelMatchSnapshot m) {
        return fill(t.warmup(), m, Map.of(
                "{gear}", gearOf(m),
                "{stakes}", stakesOf(m)));
    }

    /** The decisive result; null for a match without a winner. */
    public static String result(Templates t, DuelMatchSnapshot m, Payout payout) {
        if (m.winner() == null) return null;
        String template = m.endReason() == DuelEndReason.FORFEIT ? t.forfeit() : t.result();
        return fill(template, m, Map.of(
                "{winner}", m.side(m.winner()).name(),
                "{loser}", m.side(m.winner().other()).name(),
                "{time}", DuelViewText.clock(m.fightSeconds()),
                "{won}", won(m, payout)));
    }

    /** A completed match without a winner (time limit, agreed, simultaneous deaths). */
    public static String draw(Templates t, DuelMatchSnapshot m) {
        return fill(t.draw(), m, Map.of("{reason}", reasonOf(m.endReason())));
    }

    /** A cancelled or aborted match (local only). */
    public static String cancel(Templates t, DuelMatchSnapshot m) {
        return fill(t.cancel(), m, Map.of("{reason}", reasonOf(m.endReason())));
    }

    /** The death line "to the arena and spectators" (DESIGN §8.6); null for a match without a winner. */
    public static String death(Templates t, DuelMatchSnapshot m) {
        if (m.winner() == null) return null;
        return fill(t.death(), m, Map.of(
                "{winner}", m.side(m.winner()).name(),
                "{loser}", m.side(m.winner().other()).name()));
    }

    /** " and won 665 coins + 2 items", " and won the loser's inventory", "" for a friendly duel; plus the hold. */
    static String won(DuelMatchSnapshot m, Payout payout) {
        if (m.friendly() || payout == Payout.NONE) return "";
        StringBuilder out = new StringBuilder();
        long coins = m.winnings();
        int items = m.potItems();
        boolean spoils = m.gearMode() == GearMode.OWN_GEAR_DROP;
        if (coins > 0 || items > 0) {
            out.append(" and won &f").append(DuelViewText.stakes(coins, items));
            if (spoils) out.append(" &6+ the loser's inventory");
        } else if (spoils) {
            out.append(" and won the loser's inventory");
        }
        if (payout == Payout.HELD) {
            out.append(PENDING_REVIEW);
        } else if (payout == Payout.PENDING) {
            out.append(" &7(payout follows)");
        }
        return out.toString();
    }

    static String gearOf(DuelMatchSnapshot m) {
        String kit = m.gearMode() == GearMode.FIXED_KIT ? m.challenger().kitName() : null;
        return DuelViewText.gearMode(m.gearMode(), kit);
    }

    static String stakesOf(DuelMatchSnapshot m) {
        return DuelViewText.stakes(m.potCoins(), m.potItems());
    }

    static String reasonOf(DuelEndReason reason) {
        if (reason == null) return "it ended";
        return switch (reason) {
            case TIME_LIMIT -> "time limit";
            case AGREED_DRAW -> "agreed draw";
            case SIMULTANEOUS_DEATH -> "both fell at once";
            case TELEPORT_FAILED -> "a teleport didn't happen";
            case PLAYER_LEFT_LOBBY -> "a duelist left";
            case NO_BATTLEGROUND -> "no battleground came free in time";
            case ADMIN_CANCELLED -> "staff cancelled it";
            case SERVER_RESTART -> "a server restart";
            case API_OUTAGE -> "the server's services can't be reached";
            case WIN, FORFEIT, LOCK_REFUSED -> "it ended";
        };
    }

    private static String fill(String template, DuelMatchSnapshot m, Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        values.put("{arena}", m.arenaName());
        values.put("{challenger}", m.challenger().name());
        values.put("{opponent}", m.opponent().name());
        String text = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    /**
     * The spam limiter (DESIGN §9.1): one warmup broadcast per player per cooldown. A match is broadcast only when
     * neither duelist is still on cooldown; then both are. Ticks are server ticks (20 a second). Main thread only.
     */
    public static final class Cooldown {

        private static final int MAX_REMEMBERED = 1024;

        private final Map<UUID, Long> lastBroadcastTick = new LinkedHashMap<>();

        /**
         * True (and both stamped) when neither player had a warmup broadcast in the last {@code cooldownSeconds};
         * false (nothing stamped) otherwise. A cooldown of 0 never limits.
         */
        public boolean tryBroadcast(UUID first, UUID second, long nowTick, int cooldownSeconds) {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            long window = Math.max(0, cooldownSeconds) * 20L;
            if (window > 0 && (onCooldown(first, nowTick, window) || onCooldown(second, nowTick, window))) {
                return false;
            }
            stamp(first, nowTick);
            stamp(second, nowTick);
            prune(nowTick, window);
            return true;
        }

        /** Whether {@code player} is still on cooldown. */
        public boolean isOnCooldown(UUID player, long nowTick, int cooldownSeconds) {
            return onCooldown(player, nowTick, Math.max(0, cooldownSeconds) * 20L);
        }

        private boolean onCooldown(UUID player, long nowTick, long window) {
            Long last = lastBroadcastTick.get(player);
            return last != null && nowTick - last < window;
        }

        private void stamp(UUID player, long nowTick) {
            lastBroadcastTick.remove(player);
            lastBroadcastTick.put(player, nowTick);
        }

        /** Forgets players whose cooldown is over, and the oldest entries beyond {@value #MAX_REMEMBERED}. */
        private void prune(long nowTick, long window) {
            lastBroadcastTick.values().removeIf(last -> nowTick - last >= window);
            Iterator<UUID> oldest = lastBroadcastTick.keySet().iterator();
            while (lastBroadcastTick.size() > MAX_REMEMBERED && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }
}
