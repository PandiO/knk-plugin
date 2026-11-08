package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.survival.wave.WaveRecord;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultInput.PlayerResult;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultPoster.Outcome;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultPoster.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §11: the result screen separates match currency from the permanent payout; leavers forfeit; no PvP lines. */
class SurvivalResultScreenTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String plain(List<Component> lines) {
        return lines.stream().map(SurvivalResultScreenTest::plain).collect(Collectors.joining("\n"));
    }

    private final PlayerResult me = new PlayerResult(UUID.randomUUID(), "alice", 101, true, null, OptionalInt.of(5), 3, 1, 1, 0.0, 224, 100, 124,
        new BigDecimal("93.0"), new BigDecimal("9.9"));
    private final PlayerResult leaver = new PlayerResult(UUID.randomUUID(), "bob", 102, false, OffsetDateTime.now(), OptionalInt.empty(), 1, 0, 0, 0.0,
        70, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);

    private SurvivalResultInput input(SurvivalEndReason reason) {
        return new SurvivalResultInput(1, "Cinix", 1, OptionalInt.of(7), null, OffsetDateTime.now(), OffsetDateTime.now(), reason,
            new WaveRecord(10, 9), 4, 1, List.of(me, leaver));
    }

    @Test
    void theAnnouncementNamesWavesAndReason() {
        String s = plain(SurvivalResultScreen.announcement(input(SurvivalEndReason.TEAM_WIPE)));
        assertTrue(s.contains("Cinix"), s);
        assertTrue(s.contains("the defenders were wiped out"), s);
        assertTrue(s.contains("Waves reached 10, completed 9"), s);
        assertTrue(s.contains("4 enemies slain"), s);
        assertFalse(s.contains("won the siege"), "no PvP winner line");
        String abort = plain(SurvivalResultScreen.announcement(input(SurvivalEndReason.ADMIN_ABORT)));
        assertTrue(abort.contains("stopped by an admin") && abort.contains("No permanent rewards"), abort);
    }

    @Test
    void memberLinesKeepMatchCurrencyAndPermanentPayoutApart() {
        String s = plain(SurvivalResultScreen.memberLines(input(SurvivalEndReason.TEAM_WIPE), me));
        assertTrue(s.contains("Your team was wiped out."), s);
        assertTrue(s.contains("Waves reached 10, completed 9"), s);
        assertTrue(s.contains("3 kills (1 headshot/critical)") && s.contains("1 death"), s);
        assertTrue(s.contains("Match coins: 124 left (earned 224, spent 100)"), s);
        assertTrue(s.contains("Permanent reward: +93 coins, +9 XP"), s);
        assertTrue(s.contains("93 / 9.9"), "the exact accrual is shown next to the floored payout: " + s);
        assertFalse(s.contains("capture") || s.contains("holding") || s.contains("win reward"), "no PvP reward lines: " + s);
    }

    @Test
    void leaversAndAbortsSayWhyThereIsNoPayout() {
        String left = plain(SurvivalResultScreen.payoutLine(input(SurvivalEndReason.TEAM_WIPE), leaver));
        assertTrue(left.contains("leavers forfeit"), left);
        String abort = plain(SurvivalResultScreen.payoutLine(input(SurvivalEndReason.SERVER_RESTART), me));
        assertTrue(abort.contains("none") && abort.contains("aborted") && abort.contains("server restart"), abort);
        String nobody = plain(SurvivalResultScreen.payoutLine(input(SurvivalEndReason.NO_PLAYERS_LEFT), me));
        assertTrue(nobody.contains("nothing to settle"), nobody);
    }

    @Test
    void theFollowUpReportsTheApisAnswer() {
        MatchResultDto stored = new MatchResultDto(42, 1, 1, 7, null, null, null, "TeamWipe", 10, 9, 4, 1, List.of(),
            new SettlementDto(true, 93, 9, "siege-survival:42", OffsetDateTime.now(), "p1", 1), null, "v1", "Plugin", OffsetDateTime.now());
        SurvivalResultInput in = input(SurvivalEndReason.TEAM_WIPE);
        String ok = plain(SurvivalResultScreen.followUp(in, me, new Outcome(State.POSTED, stored, null, null, 1, false)));
        assertTrue(ok.contains("Rewards credited: +93 coins, +9 XP"), ok);
        String leaverLine = plain(SurvivalResultScreen.followUp(in, leaver, new Outcome(State.POSTED, stored, null, null, 1, true)));
        assertTrue(leaverLine.contains("recorded") && leaverLine.contains("already stored"), leaverLine);
        String mismatch = plain(SurvivalResultScreen.followUp(in, me, new Outcome(State.FAILED, null, "SettlementMismatch", "x", 1, false)));
        assertTrue(mismatch.contains("could not be recorded") && mismatch.contains("SettlementMismatch") && mismatch.contains("server log"), mismatch);
        String transport = plain(SurvivalResultScreen.followUp(in, me, new Outcome(State.FAILED, null, SurvivalResultPoster.CODE_TRANSPORT, "x", 8, false)));
        assertTrue(transport.contains("retries at the next start"), transport);
        MatchResultDto unsettled = new MatchResultDto(42, 1, 1, 7, null, null, null, "AdminAbort", 10, 9, 4, 1, List.of(),
            new SettlementDto(false, 0, 0, null, null, null, null), null, "v1", "Abort", OffsetDateTime.now());
        String none = plain(SurvivalResultScreen.followUp(in, me, new Outcome(State.POSTED, unsettled, "MatchAborted", null, 1, true)));
        assertTrue(none.contains("settled no rewards") && none.contains("AdminAbort"), none);
    }
}
