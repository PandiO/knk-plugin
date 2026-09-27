package net.knightsandkings.knk.paper.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.UnaryOperator;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.siege.SiegeCommandFilter;

/** Private messages Phase F: the siege command filter resolves aliases through the command map. */
class SiegeCommandAliasesTest {

    private static final List<String> DEFAULT_ALLOWED = List.of("/siege", "/msg", "/r", "/staffchat", "/menu");

    private final CommandMap commandMap = mock(CommandMap.class);
    private UnaryOperator<String> resolver;

    private static Command command(String name) {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn(name);
        return command;
    }

    @BeforeEach
    void setUp() {
        Command msg = command("msg");
        for (String alias : List.of("msg", "message", "tell", "whisper", "w", "m", "pm")) {
            when(commandMap.getCommand(alias)).thenReturn(msg);
        }
        Command reply = command("reply");
        when(commandMap.getCommand("reply")).thenReturn(reply);
        when(commandMap.getCommand("r")).thenReturn(reply);
        Command teammsg = command("minecraft:teammsg"); // stubbed before, not inside, the next when(...)
        when(commandMap.getCommand("teammsg")).thenReturn(teammsg);
        resolver = SiegeCommandAliases.resolver(() -> commandMap);
    }

    @Test
    void labelsResolveToThePrimaryCommandName() {
        assertEquals("msg", resolver.apply("tell"));
        assertEquals("msg", resolver.apply("w"));
        assertEquals("reply", resolver.apply("r"));
        assertEquals("teammsg", resolver.apply("teammsg"), "a namespaced command name loses its namespace");
        assertEquals("spawn", resolver.apply("spawn"), "unknown labels come back unchanged");
    }

    @Test
    void privateMessagesWorkInAMatchUnderEverySpelling() {
        for (String spelling : List.of("/msg", "/tell", "/w", "/whisper", "/m", "/pm", "/message",
                "/minecraft:msg", "/minecraft:tell", "/minecraft:w", "/knightsandkings:msg", "/knightsandkings:tell")) {
            assertTrue(SiegeCommandFilter.isAllowed(spelling + " Bob hi", DEFAULT_ALLOWED, resolver), spelling);
        }
        assertTrue(SiegeCommandFilter.isAllowed("/reply hi", DEFAULT_ALLOWED, resolver));
        assertTrue(SiegeCommandFilter.isAllowed("/knightsandkings:r hi", DEFAULT_ALLOWED, resolver));
        assertFalse(SiegeCommandFilter.isAllowed("/teammsg hi", DEFAULT_ALLOWED, resolver));
        assertFalse(SiegeCommandFilter.isAllowed("/spawn", DEFAULT_ALLOWED, resolver));
    }

    @Test
    void withoutACommandMapNothingResolves() {
        UnaryOperator<String> none = SiegeCommandAliases.resolver(() -> null);
        assertEquals("tell", none.apply("tell"));
        assertFalse(SiegeCommandFilter.isAllowed("/tell Bob hi", DEFAULT_ALLOWED, none));
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob hi", DEFAULT_ALLOWED, none));
    }
}
