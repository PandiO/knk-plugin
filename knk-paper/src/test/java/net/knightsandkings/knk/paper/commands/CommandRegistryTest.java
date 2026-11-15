package net.knightsandkings.knk.paper.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandRegistryTest {
    private CommandRegistry registry;
    private SubcommandExecutor mockExecutor;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        mockExecutor = mock(SubcommandExecutor.class);
    }

    @Test
    void testRegisterAndGet() {
        CommandMetadata meta = new CommandMetadata("test", "Test command", "/knk test", "knk.admin");
        registry.register(meta, mockExecutor);

        var result = registry.get("test");
        assertTrue(result.isPresent());
        assertEquals("test", result.get().metadata().name());
        assertEquals(mockExecutor, result.get().executor());
    }

    @Test
    void testCaseInsensitive() {
        CommandMetadata meta = new CommandMetadata("Test", "Test command", "/knk test", "knk.admin");
        registry.register(meta, mockExecutor);

        assertTrue(registry.get("test").isPresent());
        assertTrue(registry.get("TEST").isPresent());
        assertTrue(registry.get("TeSt").isPresent());
    }

    @Test
    void testAliases() {
        CommandMetadata meta = new CommandMetadata("primary", "Primary command", "/knk primary", "knk.admin");
        registry.register(meta, mockExecutor, "alias1", "alias2");

        assertTrue(registry.get("primary").isPresent());
        assertTrue(registry.get("alias1").isPresent());
        assertTrue(registry.get("alias2").isPresent());
        
        // All should point to same command
        var primary = registry.get("primary").get();
        var viaAlias = registry.get("alias1").get();
        assertEquals(primary, viaAlias);
    }

    @Test
    void testListAvailableFiltersPermissions() {
        // Listings filter players only; the console and other non-player senders hold every node
        // (CommandPermissions.has). PandiO/knk-workspace#499 changed the sender from a plain CommandSender.
        Player sender = mock(Player.class);
        when(sender.hasPermission("knk.admin")).thenReturn(true);
        when(sender.hasPermission("knk.special")).thenReturn(false);

        registry.register(new CommandMetadata("cmd1", "Desc1", "/knk cmd1", "knk.admin"), mockExecutor);
        registry.register(new CommandMetadata("cmd2", "Desc2", "/knk cmd2", "knk.special"), mockExecutor);
        registry.register(new CommandMetadata("cmd3", "Desc3", "/knk cmd3", null), mockExecutor);

        List<CommandRegistry.RegisteredCommand> available = registry.listAvailable(sender);
        
        assertEquals(2, available.size());
        assertTrue(available.stream().anyMatch(c -> c.metadata().name().equals("cmd1")));
        assertTrue(available.stream().anyMatch(c -> c.metadata().name().equals("cmd3")));
        assertFalse(available.stream().anyMatch(c -> c.metadata().name().equals("cmd2")));
    }

    @Test
    void testListAllIgnoresPermissions() {
        registry.register(new CommandMetadata("cmd1", "Desc1", "/knk cmd1", "knk.admin"), mockExecutor);
        registry.register(new CommandMetadata("cmd2", "Desc2", "/knk cmd2", "knk.special"), mockExecutor);

        List<CommandRegistry.RegisteredCommand> all = registry.listAll();
        assertEquals(2, all.size());
    }

    @Test
    void testGetNonExistent() {
        var result = registry.get("nonexistent");
        assertFalse(result.isPresent());
    }
}
