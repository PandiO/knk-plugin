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

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final UserSummary user;
    private final List<TitleBracket> brackets;
    private final TitleProgress progress;

    ProfileView(UserSummary user, List<TitleBracket> brackets) {
        this.user = user;
        this.brackets = List.copyOf(brackets);
        this.progress = user != null ? TitleProgress.of(this.brackets, user.titleBracketId(), user.experiencePoints()) : null;
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

    public boolean isHighestTitle() {
        return progress != null && progress.isHighest();
    }

    public int getTitleCount() {
        return brackets.size();
    }
}
