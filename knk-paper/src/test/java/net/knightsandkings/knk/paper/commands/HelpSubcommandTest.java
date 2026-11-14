package net.knightsandkings.knk.paper.commands;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HelpSubcommandTest {
    private CommandRegistry registry;
    private HelpSubcommand helpSubcommand;
    private CommandSender mockSender;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        helpSubcommand = new HelpSubcommand(registry);
        mockSender = mock(CommandSender.class);
        sentMessages = new ArrayList<>();
        
        doAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return null;
        }).when(mockSender).sendMessage(anyString());
        
        when(mockSender.hasPermission(anyString())).thenReturn(true);
    }

    @Test
    void testShowCommandList() {
        registry.register(new CommandMetadata("cmd1", "First command", "/knk cmd1", "knk.admin"), 
                (s, a) -> true);
        registry.register(new CommandMetadata("cmd2", "Second command", "/knk cmd2", "knk.admin"), 
                (s, a) -> true);

        helpSubcommand.execute(mockSender, new String[0]);

        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("KnK Commands")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("cmd1") && m.contains("First command")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("cmd2") && m.contains("Second command")));
    }

    @Test
    void testShowCommandDetail() {
        registry.register(
                new CommandMetadata("test", "Test description", "/knk test <arg>", "knk.admin",
                        List.of("/knk test foo", "/knk test bar")),
                (s, a) -> true);

        helpSubcommand.execute(mockSender, new String[]{"test"});

        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("test")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Test description")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("/knk test <arg>")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("/knk test foo")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("knk.admin")));
    }

    @Test
    void testUnknownCommand() {
        helpSubcommand.execute(mockSender, new String[]{"unknown"});

        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Unknown command")));
    }

    @Test
    void testEmptyRegistry() {
        helpSubcommand.execute(mockSender, new String[0]);

        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("No commands available")));
    }
}
