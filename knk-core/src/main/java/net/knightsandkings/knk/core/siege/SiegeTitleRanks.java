package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.users.KnkTitleBracket;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Title-bracket lookups for joining and the team split (DESIGN §6.2, §6.4; Phase 4 decision 13).
 * The bracket list may be empty (not fetched yet, or the API is down); every method then falls
 * back to raw experience so the siege keeps running.
 */
public final class SiegeTitleRanks {

    private final List<KnkTitleBracket> brackets;

    public SiegeTitleRanks(List<KnkTitleBracket> brackets) {
        this.brackets = brackets == null ? List.of() : brackets.stream()
                .sorted(Comparator.comparingInt(KnkTitleBracket::minExperience).thenComparingInt(KnkTitleBracket::id))
                .toList();
    }

    public static SiegeTitleRanks empty() {
        return new SiegeTitleRanks(List.of());
    }

    public boolean isEmpty() {
        return brackets.isEmpty();
    }

    /**
     * The snake-draft rank: the player's bracket MinExperience, so everyone in one bracket ranks
     * equal and is shuffled. Resolved from the bracket id when known, else from the experience;
     * with no bracket list at all, the raw experience.
     */
    public int rank(Integer titleBracketId, int experiencePoints) {
        if (brackets.isEmpty()) return Math.max(0, experiencePoints);
        if (titleBracketId != null) {
            Optional<KnkTitleBracket> byId = brackets.stream().filter(b -> b.id() == titleBracketId).findFirst();
            if (byId.isPresent()) return byId.get().minExperience();
        }
        return bracketFor(experiencePoints).map(KnkTitleBracket::minExperience).orElse(0);
    }

    /** The highest bracket the experience reaches. */
    public Optional<KnkTitleBracket> bracketFor(int experiencePoints) {
        KnkTitleBracket found = null;
        for (KnkTitleBracket b : brackets) {
            if (b.minExperience() <= experiencePoints) found = b;
        }
        return Optional.ofNullable(found);
    }

    /**
     * Text for a minimum-title requirement: the bracket name whose MinExperience equals
     * {@code minExperience} (runtime-config sends only the experience), else "{@code N} XP".
     */
    public String requirementLabel(int minExperience) {
        return brackets.stream()
                .filter(b -> b.minExperience() == minExperience)
                .map(KnkTitleBracket::name)
                .filter(n -> n != null && !n.isBlank())
                .map(n -> SiegeDisplayText.clean(n, n))
                .findFirst()
                .orElse(minExperience + " XP");
    }
}
