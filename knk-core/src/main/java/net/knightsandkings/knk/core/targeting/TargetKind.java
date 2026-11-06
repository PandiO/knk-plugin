package net.knightsandkings.knk.core.targeting;

/**
 * What an NPC can go for (Siege Survival DESIGN §4; S68 keeps a place for a fourth kind). The ids are the
 * {@code TargetTypes} strings of the NPC platform's combat profiles.
 */
public enum TargetKind {
    PLAYER("player"),
    GATE("gate"),
    OBJECTIVE("objective"),
    /** Reserved (DESIGN S68: distractors are out of v1); no consumer produces one yet. */
    DISTRACTOR("distractor");

    private final String profileTargetType;

    TargetKind(String profileTargetType) {
        this.profileTargetType = profileTargetType;
    }

    /** The target type string of the combat profiles ({@code TargetTypes}). */
    public String profileTargetType() {
        return profileTargetType;
    }
}
