package net.knightsandkings.knk.core.visibility;

/**
 * The one rule for whether a viewer may see another player (Siege Survival link S3, PandiO/knk-workspace#41). The
 * reasons a player can be hidden are combined here in a fixed order, so a later reason never un-hides what an
 * earlier one hid:
 * <ol>
 *   <li><b>Ghost</b> (Survival DESIGN §3b, S20): a ghost is {@link Verdict#HIDDEN} from everyone who is not a living
 *       or ghost member of its team. This wins over everything: no permission and no mode reveals a ghost to an
 *       outsider. Teammates fall through to the next reasons (they see the ghost translucent, through vanilla
 *       invisibility and the team's see-friendly-invisibles, which is not this rule's business).</li>
 *   <li><b>Vanish</b> (owner/staff mode): {@link Verdict#HIDDEN_UNLESS_STAFF} - hidden now, shown once the viewer's
 *       staff/owner permission check resolves true. Exactly the pre-S3 {@code ModeService} behaviour.</li>
 *   <li>Otherwise {@link Verdict#VISIBLE}.</li>
 * </ol>
 * The spectator game mode, the invisibility enchantment ability and the siege team colours are not reasons here:
 * vanilla and their own packets handle them, and they never call {@code hidePlayer}.
 */
public final class PlayerVisibilityRules {

    private PlayerVisibilityRules() {
    }

    public enum Verdict {
        /** Show the target. */
        VISIBLE,
        /** Hide the target; nothing reveals it to this viewer. */
        HIDDEN,
        /** Hide the target now; show it if the viewer turns out to see vanished players. */
        HIDDEN_UNLESS_STAFF;

        /** Whether the target is hidden at first (before any permission check). */
        public boolean hiddenFirst() {
            return this != VISIBLE;
        }
    }

    /**
     * @param targetIsGhost     the target is a ghost
     * @param viewerOnGhostTeam the viewer is a living or ghost member of the target's ghost team (ignored otherwise)
     * @param targetVanished    the target is in owner/staff mode
     */
    public static Verdict decide(boolean targetIsGhost, boolean viewerOnGhostTeam, boolean targetVanished) {
        if (targetIsGhost && !viewerOnGhostTeam) {
            return Verdict.HIDDEN;
        }
        if (targetVanished) {
            return Verdict.HIDDEN_UNLESS_STAFF;
        }
        return Verdict.VISIBLE;
    }

    /** The viewer-independent part: a ghost is hidden from this viewer when the team doesn't include them. */
    public static Verdict decide(GhostTeam ghostTeam, java.util.UUID viewerId, boolean targetVanished) {
        return decide(ghostTeam != null, ghostTeam != null && ghostTeam.includes(viewerId), targetVanished);
    }
}
