package net.knightsandkings.knk.core.production.harvest;

import java.util.UUID;

/**
 * The shared requirement check (PRODUCTION_KINDS § 6.1 {@code IRequirementEvaluator}, PandiO/knk-workspace#134): whether a
 * player meets a rule's condition. Now a permission node and a lowest title bracket; skills, professions and knowledge
 * unlocks plug in here later. Answers from caches on the main thread, so a break is never held up by a call.
 */
@FunctionalInterface
public interface RequirementEvaluator {

    /** A rule's condition; null fields are not required. */
    record Requirement(String permission, Integer minTitleBracketId) {

        public static final Requirement NONE = new Requirement(null, null);

        public boolean empty() {
            return permission == null && minTitleBracketId == null;
        }
    }

    /** Null when {@code player} meets {@code requirement}, else the reason shown to them. */
    String unmet(UUID player, Requirement requirement);

    RequirementEvaluator ALWAYS = (player, requirement) -> null;
}
