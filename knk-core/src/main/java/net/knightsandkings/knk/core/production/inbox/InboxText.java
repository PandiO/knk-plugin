package net.knightsandkings.knk.core.production.inbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of the reward inbox (PandiO/knk-workspace#684, production DESIGN § 4.6, D7): the delivery and loss messages,
 * the near-cap warning, expiry reminders and the join summary. Plain text; the plugin adds colours.
 */
public final class InboxText {

    public static final String COMMAND = "/inbox";

    private InboxText() {
    }

    /** "1 item", "12 items", "1,024 items". */
    public static String items(long units) {
        return String.format(Locale.ROOT, "%,d %s", units, units == 1 ? "item" : "items");
    }

    /** "120 of 2,304 (5%)", or "120" without a cap. */
    public static String fill(long used, long cap) {
        if (cap <= 0) {
            return String.format(Locale.ROOT, "%,d", used);
        }
        return String.format(Locale.ROOT, "%,d of %,d (%d%%)", used, cap, Math.round(100.0 * used / cap));
    }

    /** "in 7 days", "in 1 day", "in 5 hours", "within the hour", "now". */
    public static String expiresIn(Instant at, Instant now) {
        if (at == null) {
            return "later";
        }
        Duration left = Duration.between(now, at);
        if (left.isNegative() || left.isZero()) {
            return "now";
        }
        long days = left.toDays();
        if (days >= 1) {
            return "in " + days + (days == 1 ? " day" : " days");
        }
        long hours = left.toHours();
        if (hours >= 1) {
            return "in " + hours + (hours == 1 ? " hour" : " hours");
        }
        return "within the hour";
    }

    /**
     * After a delivery: where the items went. Empty when nothing reached the player's inbox and nothing was lost. The
     * loss line comes first: it is the one that matters.
     */
    public static List<String> delivered(long inboxedUnits, long lostUnits, Integer warningPercent) {
        List<String> lines = new ArrayList<>();
        if (lostUnits > 0) {
            lines.add("Your reward inbox is full: " + items(lostUnits) + (lostUnits == 1 ? " was" : " were") + " lost.");
        }
        if (inboxedUnits > 0) {
            lines.add(items(inboxedUnits) + " didn't fit and went to your reward inbox ("
                    + COMMAND + ").");
        }
        if (warningPercent != null) {
            lines.add(warning(warningPercent));
        }
        return lines;
    }

    /** The near-cap warning. */
    public static String warning(int percent) {
        return "Your reward inbox is " + percent + "% full. Claim items with " + COMMAND
                + " before new rewards are lost.";
    }

    /** An expiry reminder. */
    public static String reminder(long expiringUnits, Instant expiresAt, Instant now) {
        return items(expiringUnits) + " in your reward inbox expire " + expiresIn(expiresAt, now) + ". Claim "
                + (expiringUnits == 1 ? "it" : "them") + " with " + COMMAND + ".";
    }

    /** Items expired and are gone. */
    public static String expired(long lostUnits) {
        return items(lostUnits) + " in your reward inbox expired and " + (lostUnits == 1 ? "is" : "are") + " gone.";
    }

    /** What an {@code InboxUpdated} notice says; empty when there is nothing to tell. */
    public static List<String> notice(InboxNotice notice, Instant now) {
        if (notice == null) {
            return List.of();
        }
        if (notice.delivered()) {
            List<String> lines = new ArrayList<>();
            if (notice.lostUnits() > 0) {
                lines.add("Your reward inbox is full: " + items(notice.lostUnits())
                        + (notice.lostUnits() == 1 ? " was" : " were") + " lost.");
            }
            if (notice.inboxedUnits() > 0) {
                lines.add(items(notice.inboxedUnits()) + " arrived in your reward inbox (" + COMMAND + ").");
            }
            if (notice.warningPercent() != null) {
                lines.add(warning(notice.warningPercent()));
            }
            return lines;
        }
        if (notice.reminder() && notice.expiringUnits() > 0) {
            return List.of(reminder(notice.expiringUnits(), notice.expiresAt(), now));
        }
        if (notice.expired() && notice.lostUnits() > 0) {
            return List.of(expired(notice.lostUnits()));
        }
        return List.of();
    }

    /** On join: what waits in the inbox; empty for an empty inbox. */
    public static List<String> joinSummary(InboxView view, Instant now) {
        if (view == null || view.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("You have " + items(view.totalAmount()) + " in your reward inbox (" + COMMAND + ").");
        Instant next = view.nextExpiry();
        if (next != null && Duration.between(now, next).toDays() < 7) {
            lines.add("Some of them expire " + expiresIn(next, now) + ".");
        }
        if (view.warningPercent() != null) {
            lines.add(warning(view.warningPercent()));
        }
        return lines;
    }

    /** The lore line of an entry's expiry: "12 expire in 3 days". */
    public static String entryExpiry(InboxView.Entry entry, Instant now) {
        if (entry.nextExpiresAt() == null) {
            return null;
        }
        long amount = entry.nextExpiringAmount() > 0 ? entry.nextExpiringAmount() : entry.amount();
        return String.format(Locale.ROOT, "%,d %s %s", amount, amount == 1 ? "expires" : "expire",
                expiresIn(entry.nextExpiresAt(), now));
    }

    /** What a refused or short claim says, from the API's reject reasons. */
    public static String claimShort(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "the inbox has less than that";
        }
        String first = reasons.get(0);
        return switch (first) {
            case "NotEnoughStock" -> "the inbox has less than that";
            case "StorageLocked", "Locked" -> "your inbox is locked right now";
            default -> first;
        };
    }
}
