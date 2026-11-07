package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.economy.KillBlow;
import net.knightsandkings.knk.core.survival.economy.KillRewardSettings;
import net.knightsandkings.knk.core.survival.economy.MatchEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /siege balance} and {@code /siege pay} over the match ledger (DESIGN §8: transfers, S20 ghosts). */
class SurvivalPlayerCommandsTest {

    private final SurvivalMatchRuntime runtime = mock(SurvivalMatchRuntime.class);
    private final SurvivalMatch match = mock(SurvivalMatch.class);
    private final SurvivalGhosts ghosts = mock(SurvivalGhosts.class);
    private final MatchEconomy economy = new MatchEconomy("m-1", KillRewardSettings.defaults());
    private final Map<String, Player> byName = new HashMap<>();
    private final Player alice = player("alice");
    private final Player bob = player("bob");
    private final SurvivalPlayerCommands commands = new SurvivalPlayerCommands(() -> runtime, byName::get);

    SurvivalPlayerCommandsTest() {
        when(match.economy()).thenReturn(economy);
        when(match.ghosts()).thenReturn(ghosts);
        when(runtime.matchOfPlayer(alice.getUniqueId())).thenReturn(Optional.of(match));
        when(runtime.matchOfPlayer(bob.getUniqueId())).thenReturn(Optional.of(match));
        economy.join(alice.getUniqueId());
        economy.join(bob.getUniqueId());
        economy.creditKill(1, new KillBlow(alice.getUniqueId(), true, false)); // 77
    }

    private Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        byName.put(name, p);
        return p;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void balanceShowsTheOwnBalanceAndKills() {
        String reply = plain(commands.balance(alice));
        assertTrue(reply.contains("Match balance: 77 coins (1 kills"), reply);
        when(ghosts.isGhost(alice.getUniqueId())).thenReturn(true);
        assertTrue(plain(commands.balance(alice)).contains("ghost"));
        Player outsider = player("carol");
        assertTrue(plain(commands.balance(outsider)).contains("not in a running survival match"));
    }

    @Test
    void payTransfersAtomicallyAndRefusesOverdraftsGhostsAndSelf() {
        assertTrue(plain(commands.pay(alice, "bob", "50")).contains("Paid 50 match coins to bob (balance 27)"));
        assertEquals(27, economy.balance(alice.getUniqueId()));
        assertEquals(50, economy.balance(bob.getUniqueId()));
        verify(bob).sendMessage(any(Component.class));
        assertTrue(plain(commands.pay(alice, "bob", "28")).contains("Not enough match coins: you have 27"));
        assertTrue(plain(commands.pay(alice, "alice", "1")).contains("pay yourself"));
        assertTrue(plain(commands.pay(alice, "bob", "0")).contains("positive"));
        assertTrue(plain(commands.pay(alice, "bob", "x")).contains("whole number"));
        assertTrue(plain(commands.pay(alice, "nobody", "1")).contains("No online player"));
        assertTrue(plain(commands.pay(alice, null, null)).contains("Usage"));
        economy.setGhost(bob.getUniqueId(), true);
        assertTrue(plain(commands.pay(alice, "bob", "1")).contains("bob is a ghost"));
        economy.setGhost(bob.getUniqueId(), false);
        economy.setGhost(alice.getUniqueId(), true);
        assertTrue(plain(commands.pay(alice, "bob", "1")).contains("Ghosts cannot transfer"));
        economy.setGhost(alice.getUniqueId(), false);
        Player carol = player("carol");
        assertTrue(plain(commands.pay(alice, "carol", "1")).contains("carol is not in your match"));
        economy.end();
        assertTrue(plain(commands.pay(alice, "bob", "1")).contains("over"));
    }
}
