package net.knightsandkings.knk.paper.chat;

import net.knightsandkings.knk.core.domain.users.RewardMultiplier;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.TitleChangeResult;
import net.knightsandkings.knk.paper.chat.RewardMessageFormat.Currency;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardMessageFormatTest {
    private static final RewardMultiplier SERVER_NEUTRAL = new RewardMultiplier("global", 1.0, null, null, false, null, null);
    private static final RewardMultiplier PERSONAL_2 = new RewardMultiplier("personal", 2.0, null, null, false, null, null);
    private static final RewardMultiplier ROYAL = new RewardMultiplier("rank", 1.2, 20, "Royal", true, "&6&l", "&e");
    private static final RewardMultiplier DEFAULT_RANK = new RewardMultiplier("rank", 1.0, 1, "Default", false, null, null);

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static SalaryPayoutResult salary(int paid, double hoursCovered, double paidHours, int titleSalary, List<RewardMultiplier> multipliers) {
        return new SalaryPayoutResult(true, paid, hoursCovered, 1.0, 1.0, 1.0, paid, null, null, 5, titleSalary,
                paidHours, titleSalary * paidHours, multipliers);
    }

    @Test
    void lineShowsBaseEachNonNeutralMultiplierWithItsReasonAndTheTotal() {
        Component line = RewardMessageFormat.line("Salary (Serf)", Currency.COINS, 975,
                List.of(SERVER_NEUTRAL, PERSONAL_2, ROYAL, DEFAULT_RANK), 2340);

        assertEquals("Salary (Serf): 975 ×2 Personal ×1.2 Royal = +2,340 coins", plain(line));
    }

    @Test
    void lineWithoutMultipliersShowsJustTheTotal() {
        Component line = RewardMessageFormat.line("Salary (Serf)", Currency.COINS, 650, List.of(SERVER_NEUTRAL, DEFAULT_RANK), 650);

        assertEquals("Salary (Serf): +650 coins", plain(line));
    }

    @Test
    void rankNameTakesTheRanksChatStyleLikeChatDoes() {
        Component line = RewardMessageFormat.line("Salary", Currency.COINS, 100, List.of(ROYAL), 120);

        TextComponent royal = (TextComponent) line.children().stream()
                .filter(c -> c instanceof TextComponent t && t.content().equals("Royal"))
                .findFirst().orElseThrow();
        assertEquals(NamedTextColor.GOLD, royal.color());
        assertTrue(royal.hasDecoration(TextDecoration.BOLD));
    }

    @Test
    void hourlySalaryIsOneLine() {
        List<Component> lines = RewardMessageFormat.salary(salary(1568, 1.01, 1.005, 650, List.of(SERVER_NEUTRAL, PERSONAL_2, ROYAL)), "Serf");

        assertEquals(1, lines.size());
        assertEquals("Salary (Serf): 653 ×2 Personal ×1.2 Royal = +1,568 coins", plain(lines.get(0)));
    }

    @Test
    void gapSalaryExplainsHowTheHoursAwayWereCounted() {
        List<Component> lines = RewardMessageFormat.salary(salary(975, 2.0, 1.5, 650, List.of(SERVER_NEUTRAL)), "Serf");

        assertEquals(List.of("Salary (Serf): +975 coins", "  650 coins/h × 1.5h paid for 2.0h away"),
                lines.stream().map(RewardMessageFormatTest::plain).toList());
    }

    @Test
    void salaryFromAnOlderApiWithoutBreakdownShowsTheAmountPaid() {
        SalaryPayoutResult old = new SalaryPayoutResult(true, 975, 2.0, 1.0, 1.0, 1.0, 975, null, null, null, 0, 0, 0, null);

        assertEquals(List.of("Salary: +975 coins"),
                RewardMessageFormat.salary(old, null).stream().map(RewardMessageFormatTest::plain).toList());
    }

    @Test
    void titleBonusesGetOneLinePerCurrencyWithTheirOwnMultipliers() {
        TitleChangeResult change = new TitleChangeResult("promotion", 0, "Serf", 1, "Peasant", List.of(),
                32400, 6, 192, 13500, 3, 32,
                List.of(PERSONAL_2, ROYAL),
                List.of(new RewardMultiplier("personal", 1.0, null, null, false, null, null), new RewardMultiplier("rank", 2.0, 20, "Royal", true, "&6", null)),
                List.of(PERSONAL_2, new RewardMultiplier("rank", 3.0, 20, "Royal", true, "&6", null)));

        assertEquals(List.of(
                "Title bonus: 13,500 ×2 Personal ×1.2 Royal = +32,400 coins",
                "Title bonus: 3 ×2 Royal = +6 gems",
                "Title bonus: 32 ×2 Personal ×3 Royal = +192 XP"),
                RewardMessageFormat.titleBonuses(change).stream().map(RewardMessageFormatTest::plain).toList());
    }

    @Test
    void titleBonusesSkipCurrenciesThatPaidNothing() {
        TitleChangeResult change = new TitleChangeResult("promotion", 0, "Serf", 1, "Peasant", List.of(),
                13500, 0, 0, 13500, 0, 0, List.of(), List.of(), List.of());

        assertEquals(List.of("Title bonus: +13,500 coins"),
                RewardMessageFormat.titleBonuses(change).stream().map(RewardMessageFormatTest::plain).toList());
    }

    @Test
    void adminGrantNamesTheStaffMemberAndTheTypedReason() {
        assertEquals("Granted by Pandi: +500 coins – event prize",
                plain(RewardMessageFormat.adminChange("Pandi", Currency.COINS, 500, "event prize")));
    }

    @Test
    void adminRemovalIsNegativeAndLeavesOutAMissingReason() {
        assertEquals("Removed by Pandi: -1,200 XP",
                plain(RewardMessageFormat.adminChange("Pandi", Currency.XP, -1200, null)));
    }

    @Test
    void propertyNamesMapToCurrencies() {
        assertEquals(Currency.COINS, Currency.forProperty("coins"));
        assertEquals(Currency.GEMS, Currency.forProperty("gems"));
        assertEquals(Currency.XP, Currency.forProperty("xp"));
        assertEquals(null, Currency.forProperty("group"));
    }

    @Test
    void multipliersDropTrailingZeros() {
        assertEquals("2", RewardMessageFormat.formatMultiplier(2.0));
        assertEquals("1.2", RewardMessageFormat.formatMultiplier(1.2));
        assertEquals("1.15", RewardMessageFormat.formatMultiplier(1.15));
        assertEquals("0.5", RewardMessageFormat.formatMultiplier(0.5));
    }
}
