package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.paper.currency.CurrencySettings;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/** KNG-21 Phase 4: /knk currency argument parsing (the service does the checks and calls). */
class CurrencyAdminCommandTest {

    private final PlayerCurrencyService service = mock(PlayerCurrencyService.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final CurrencyAdminCommand command = new CurrencyAdminCommand(service);

    CurrencyAdminCommandTest() {
        when(service.settings()).thenReturn(CurrencySettings.defaults());
    }

    @Test
    void reverse_joinsTheReason_andTakesThePartialFlagAnywhere() {
        command.execute(sender, new String[] {"reverse", "01M3ABC", "granted", "--partial", "twice", "by", "a", "bug"});
        verify(service).staffReverse(sender, "01M3ABC", "granted twice by a bug", true);
    }

    @Test
    void history_takesCurrencyAndPageInAnyOrder() {
        command.execute(sender, new String[] {"history", "Steve", "3", "gems"});
        verify(service).staffHistory(sender, "Steve", BalanceCurrency.GEMS, 3);
    }

    @Test
    void lockAndUnlock() {
        command.execute(sender, new String[] {"lock", "Steve", "suspected", "alt"});
        command.execute(sender, new String[] {"unlock", "Steve"});
        verify(service).staffLock(sender, "Steve", "suspected alt");
        verify(service).staffUnlock(sender, "Steve");
    }

    @Test
    void missingArguments_showTheUsage() {
        command.execute(sender, new String[] {"reverse", "01M3ABC"});
        command.execute(sender, new String[] {"frobnicate"});
        verify(service, never()).staffReverse(any(), anyString(), anyString(), anyBoolean());
        verify(sender, org.mockito.Mockito.times(2)).sendMessage(CurrencySettings.defaults().message("currency-admin-usage"));
    }

    @Test
    void completesTheActions() {
        assertEquals(List.of("history"), command.complete(sender, new String[] {"hi"}));
        assertEquals(List.of("--partial"), command.complete(sender, new String[] {"reverse", "01M3", "--"}));
    }
}
