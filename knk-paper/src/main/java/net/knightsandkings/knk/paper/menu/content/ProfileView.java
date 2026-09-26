package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code profile} variable root (CONTENT_PORT_PLAN.md CP3): the viewer's balances, title,
 * title progress and premium tier, from a fresh {@code UsersQueryApi} read (see
 * {@link ProfileMenuFeature}). Public zero-arg getters for {@code $profile.…$} chains; getters
 * that return null drop their lore line (E8).
 */
public final class ProfileView {

    private static final int PROGRESS_BAR_WIDTH = 20;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final UserSummary user;
    private final List<TitleBracket> brackets;
    private final TitleProgress progress;

    ProfileView(UserSummary user, List<TitleBracket> brackets) {
        this.user = user;
        this.brackets = List.copyOf(brackets);
        this.progress = user != null ? TitleProgress.of(this.brackets, user.titleBracketId(), user.experiencePoints()) : null;
    }

    /** For callers outside the menu, e.g. {@code /user statistics} (KNG-9), so both show the same numbers. */
    public static ProfileView of(UserSummary user, List<TitleBracket> brackets) {
        return new ProfileView(user, brackets);
    }

    /** Shown while the viewer's account isn't loaded. */
    static ProfileView unavailable() {
        return new ProfileView(null, List.of());
    }

    public boolean isLoaded() {
        return user != null;
    }

    public int getCoins() {
        return user != null ? user.coins() : 0;
    }

    public int getGems() {
        return user != null ? user.gems() : 0;
    }

    public int getExperience() {
        return user != null ? user.experiencePoints() : 0;
    }

    public String getGender() {
        return user != null ? user.gender() : null;
    }

    /** The title for the viewer's gender (bracket list), else the server-resolved name, else "None". */
    public String getTitleName() {
        if (user == null) {
            return "Unknown";
        }
        if (progress != null && progress.current() != null) {
            return progress.current().nameFor(user.gender());
        }
        return user.titleName() != null ? user.titleName() : "None";
    }

    public String getTitleLine() {
        return user != null ? "&7Title: &f" + getTitleName() : "&cYour account isn't loaded yet";
    }

    /** "&7Premium tier: &6Noble &7(until 2026-10-01)", or null without a premium tier. */
    public String getPremiumLine() {
        if (user == null || user.premiumTierName() == null) {
            return null;
        }
        String line = "&7Premium tier: &6" + user.premiumTierName();
        if (user.premiumTierExpiresAt() != null) {
            line += " &7(until " + DATE.format(user.premiumTierExpiresAt()) + ")";
        }
        return line;
    }

    /** "&7Prestige XP: &f+N", or null while it is 0. */
    public String getPrestigeLine() {
        return user != null && user.prestigeExperience() > 0 ? "&7Prestige XP: &f+" + user.prestigeExperience() : null;
    }

    /** "Next: X — N XP to go" or "Highest title reached" (plan CP3 slot 2). */
    public List<String> getProgressLines() {
        List<String> lines = new ArrayList<>();
        if (user == null || progress == null) {
            return lines;
        }
        lines.add("&7Current: &f" + getTitleName());
        if (progress.isHighest()) {
            lines.add("&aHighest title reached");
        } else if (progress.next() != null) {
            lines.add("&7Next: &f" + progress.next().nameFor(user.gender()) + " &7- &f" + progress.experienceToNext() + " XP &7to go");
        }
        return lines;
    }

    /**
     * Menu follow-up 2026-09-26: a progress bar towards the next title,
     * "&a||||||||&7|||||||||||| &f40%", or null at the highest title / without progress.
     */
    public String getProgressBar() {
        if (user == null || progress == null || progress.next() == null) {
            return null;
        }
        int from = progress.current() != null ? progress.current().minExperience() : 0;
        int span = progress.next().minExperience() - from;
        int percent = span <= 0 ? 100 : (int) Math.max(0, Math.min(100, (user.experiencePoints() - from) * 100L / span));
        int filled = percent * PROGRESS_BAR_WIDTH / 100;
        return "&a" + "|".repeat(filled) + "&7" + "|".repeat(PROGRESS_BAR_WIDTH - filled) + " &f" + percent + "%";
    }

    /** "&7Title rank: &f3&7/&f19", or null while the brackets aren't known. */
    public String getTitleRankLine() {
        if (user == null || progress == null || progress.current() == null || brackets.isEmpty()) {
            return null;
        }
        return "&7Title rank: &f" + (progress.currentIndex(brackets) + 1) + "&7/&f" + brackets.size();
    }

    /**
     * Menu follow-up 2026-09-26: the hub's centre head and the profile head - title, rank on the
     * ladder, progress to the next title, balances and premium tier at a glance.
     */
    public List<String> getQuickStatsLines() {
        List<String> lines = new ArrayList<>();
        if (user == null) {
            lines.add("&cYour account isn't loaded yet");
            return lines;
        }
        lines.add(getTitleLine());
        addIfPresent(lines, getTitleRankLine());
        if (progress != null && progress.next() != null) {
            lines.add("&7Next: &f" + progress.next().nameFor(user.gender()) + " &7(&f" + progress.experienceToNext() + " XP&7)");
            addIfPresent(lines, getProgressBar());
        } else if (progress != null && progress.isHighest()) {
            lines.add("&aHighest title reached");
        }
        lines.add("");
        lines.add("&7Coins: &6" + user.coins());
        lines.add("&7Gems: &b" + user.gems());
        lines.add("&7Experience: &f" + user.experiencePoints());
        addIfPresent(lines, getPrestigeLine());
        addIfPresent(lines, getPremiumLine());
        return lines;
    }

    private static void addIfPresent(List<String> lines, String line) {
        if (line != null) {
            lines.add(line);
        }
    }

    public boolean isHighestTitle() {
        return progress != null && progress.isHighest();
    }

    public int getTitleCount() {
        return brackets.size();
    }
}
