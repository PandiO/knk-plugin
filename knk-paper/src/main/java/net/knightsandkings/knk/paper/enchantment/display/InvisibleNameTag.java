package net.knightsandkings.knk.paper.enchantment.display;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the invisibility ability's invoker's name tag hidden from other players while they are invisible
 * (PandiO/knk-workspace#169, round 2).
 * <p>
 * <b>What vanilla does:</b> the client hides an invisible player's name tag (and body) from every viewer, except a
 * spectator and a viewer on the <i>same team</i> of the viewer's own board when that team "can see friendly
 * invisibles" (they see a see-through body with the name tag). A new team has that option on, and the shared rank
 * board ({@code ScoreboardUtil}: {@code default}, {@code owner}, {@code staff}, {@code tier_<id>}) never turned it
 * off, so everyone of the same rank saw an invisible player's name tag. The siege boards turn it on only for the
 * viewer's own siege team, on purpose: teammates see their invisible teammate, the other teams don't.
 * <p>
 * <b>How:</b> the rank teams are colour groups, not allies, so {@code ScoreboardUtil} now registers them with the
 * option off, and when the ability is used this class turns it off on the invoker's team of the shared board too (a
 * team registered elsewhere on that board, such as a siege-tag twin team, gets it here). Nobody is moved between
 * teams and no player's board changes, so the one-team-per-board rule and {@code ViewerScoreboards} are untouched.
 * Vanilla then hides the tag exactly as long as the invisibility lasts and shows it again when it ends, also by milk
 * or death; nothing is changed temporarily, so quit or a plugin disable need no clean-up. Siege teammates still see
 * the invoker (the siege board's choice). Main thread.
 */
public final class InvisibleNameTag {

    private static final Logger LOGGER = Logger.getLogger(InvisibleNameTag.class.getName());

    private final Supplier<Scoreboard> sharedBoard;

    /** @param sharedBoard the shared rank board ({@code ScoreboardUtil::getScoreboard}) */
    public InvisibleNameTag(Supplier<Scoreboard> sharedBoard) {
        this.sharedBoard = sharedBoard;
    }

    /** The invoker turned invisible: same-rank players on the shared board must not see their name tag. */
    public void invisible(Player invoker) {
        if (invoker == null || sharedBoard == null) {
            return;
        }
        try {
            Scoreboard board = sharedBoard.get();
            Team team = board == null ? null : board.getEntryTeam(invoker.getName());
            if (team != null && team.canSeeFriendlyInvisibles()) {
                team.setCanSeeFriendlyInvisibles(false);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not hide the name tag of invisible " + invoker.getName(), e);
        }
    }
}
