package net.knightsandkings.knk.paper.commands;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.paper.currency.CurrencySettings;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/** KNG-21 Phase 3: /pay only parses - PlayerCurrencyService does the work. */
class PayCommandTest {

    private final PlayerCurrencyService service = mock(PlayerCurrencyService.class);
    private final Player player = mock(Player.class);
    private final PayCommand command = new PayCommand(service);

    PayCommandTest() {
        when(service.settings()).thenReturn(CurrencySettings.defaults());
    }

    private void run(String... args) {
        command.onCommand(player, null, "pay", args);
    }

    @Test
    void paysCoinsByDefault_andGemsWhenAsked() {
        run("Bob", "100");
        run("Bob", "5", "gems");

        verify(service).pay(player, "Bob", "100", BalanceCurrency.COINS);
        verify(service).pay(player, "Bob", "5", BalanceCurrency.GEMS);
    }

    @Test
    void confirmAndCancel_takeAnOptionalId() {
        run("confirm");
        run("cancel", "01M3FHX1ZDRTAB76K01ZYK0PCZ");

        verify(service).confirm(player, null);
        verify(service).cancel(player, "01M3FHX1ZDRTAB76K01ZYK0PCZ");
    }

    @Test
    void aPlayerNamedConfirm_canStillBePaid() {
        run("confirm", "100");

        verify(service).pay(player, "confirm", "100", BalanceCurrency.COINS);
        verify(service, never()).confirm(any(), any());
    }

    @Test
    void xpAndOldArgumentOrder_showUsage() {
        run("Bob", "100", "xp");
        run("coins", "Bob", "100", "extra");

        verify(service, never()).pay(any(), anyString(), anyString(), any());
    }

    @Test
    void theConsoleCantPay() {
        CommandSender console = mock(CommandSender.class);
        command.onCommand(console, null, "pay", new String[] {"Bob", "100"});

        verify(service, never()).pay(any(), anyString(), anyString(), any());
    }
}
