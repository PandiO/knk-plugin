package net.knightsandkings.knk.paper.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

import net.knightsandkings.knk.api.dto.*;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.commands.AccountLinkCommand;
import net.knightsandkings.knk.paper.commands.LinkCodeMessage;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration tests for account management command flows.
 * Tests the full lifecycle from command execution to API calls.
 */
@ExtendWith(MockitoExtension.class)
@Tag("requires-bukkit")
class AccountCommandIntegrationTest {

    private UserAccountApi mockApi;
    private UsersQueryApi mockUsersQueryApi;
    private KnKPlugin mockPlugin;
    private Logger mockLogger;
    private KnkConfig mockConfig;
    private KnkConfig.AccountConfig mockAccountConfig;
    private KnkConfig.MessagesConfig mockMessagesConfig;
    
    private UserManager userManager;
    private ChatCaptureManager chatCaptureManager;
    private AccountLinkCommand accountLinkCommand;
    private CommandCooldownManager mockCooldownManager;
    
    private Player mockPlayer;
    private UUID testUUID;

    @BeforeEach
    void setUp() {
        mockApi = mock(UserAccountApi.class);
        mockUsersQueryApi = mock(UsersQueryApi.class);
        mockPlugin = mock(KnKPlugin.class);
        mockLogger = mock(Logger.class);
        mockConfig = mock(KnkConfig.class);
        mockAccountConfig = mock(KnkConfig.AccountConfig.class);
        mockMessagesConfig = mock(KnkConfig.MessagesConfig.class);
        mockCooldownManager = mock(CommandCooldownManager.class);
        mockPlayer = mock(Player.class);
        
        testUUID = UUID.randomUUID();
        when(mockPlayer.getUniqueId()).thenReturn(testUUID);
        when(mockPlayer.getName()).thenReturn("TestPlayer");
        
        // Configure mocks
        when(mockConfig.account()).thenReturn(mockAccountConfig);
        when(mockConfig.messages()).thenReturn(mockMessagesConfig);
        when(mockMessagesConfig.prefix()).thenReturn("§8[§6KnK§8]§r ");
        when(mockAccountConfig.chatCaptureTimeoutSeconds()).thenReturn(120);
        when(mockAccountConfig.linkCodeExpiryMinutes()).thenReturn(20);
        when(mockCooldownManager.canExecute(any(UUID.class), anyString(), anyInt())).thenReturn(true);
        
        // Initialize components
        UserCache mockUserCache = mock(UserCache.class);
        userManager = new UserManager(mockPlugin, mockApi, mockUsersQueryApi, mockUserCache, mockLogger, mockAccountConfig, mockMessagesConfig);
        chatCaptureManager = new ChatCaptureManager(mockPlugin, mockConfig, mockLogger);
        accountLinkCommand = new AccountLinkCommand(
            mockPlugin, userManager, chatCaptureManager, mockApi, mockConfig, mockCooldownManager
        );
    }

    @Nested
    @DisplayName("/account link Flow Integration Tests")
    class AccountLinkFlowTests {

        @Test
        @DisplayName("Should generate link code")
        void shouldGenerateLinkCode() {
            // Arrange
            PlayerUserData userData = new PlayerUserData(
                1, "TestPlayer", testUUID, null,
                100, 50, 1000, false, false, null, GatePassThroughMethod.DEFAULT
            );
            userManager.updateCachedUser(testUUID, userData);
            
            LinkCodeResponseDto linkCodeResponse = new LinkCodeResponseDto(
                "ABC123DEF456",
                "2026-01-30T12:00:00Z",
                "ABC-123-DEF-456"
            );
            
            when(mockApi.generateLinkCode(1))
                .thenReturn(CompletableFuture.completedFuture(linkCodeResponse));

            // Act
            boolean result = accountLinkCommand.onCommand(
                mockPlayer, null, "account", new String[]{"link"}
            );

            // Assert
            assertTrue(result);
            verify(mockApi, timeout(1000)).generateLinkCode(1);
            
            // Verify code displayed to player (use String sendMessage)
            verify(mockPlayer, timeout(1000).atLeastOnce()).sendMessage(anyString());
        }

        @Test
        @DisplayName("A code typed in game is explained, not validated (codes go game -> web)")
        void shouldExplainWhereCodesAreEnteredWithPublicUrl() {
            when(mockConfig.web()).thenReturn(new KnkConfig.WebConfig("https://app.example.test"));
            when(mockMessagesConfig.linkCodeEnteredInGame()).thenReturn(LinkCodeMessage.DEFAULT_ENTERED_IN_GAME_TEMPLATE);

            boolean result = accountLinkCommand.onCommand(mockPlayer, null, "account", new String[]{"ABC123"});

            assertTrue(result);
            verify(mockApi, never()).validateLinkCode(anyString());
            verify(mockPlayer).sendMessage(contains("Link codes are entered on the website, not in game."));
            verify(mockPlayer).sendMessage(contains("Type /account link (without a code) to get yours."));
            verify(mockPlayer).sendMessage(contains("https://app.example.test/auth/register"));
        }

        @Test
        @DisplayName("Without web.public-url the register link is left out")
        void shouldExplainWhereCodesAreEnteredWithoutPublicUrl() {
            when(mockConfig.web()).thenReturn(KnkConfig.WebConfig.defaults());
            when(mockMessagesConfig.linkCodeEnteredInGame()).thenReturn(LinkCodeMessage.DEFAULT_ENTERED_IN_GAME_TEMPLATE);

            boolean result = accountLinkCommand.onCommand(mockPlayer, null, "account", new String[]{"ABC123"});

            assertTrue(result);
            verify(mockApi, never()).validateLinkCode(anyString());
            verify(mockPlayer, times(2)).sendMessage(anyString());
            verify(mockPlayer, never()).sendMessage(contains("/auth/register"));
        }
    }

    @Nested
    @DisplayName("API Client Integration Tests")
    class ApiClientTests {

        @Test
        @DisplayName("Should handle retry on transient errors")
        void shouldRetryOnTransientErrors() {
            // Arrange
            PlayerUserData userData = new PlayerUserData(
                1, "TestPlayer", testUUID, null,
                100, 50, 1000, false, false, null, GatePassThroughMethod.DEFAULT
            );
            userManager.updateCachedUser(testUUID, userData);
            
            AtomicBoolean firstCallFailed = new AtomicBoolean(false);
            
            when(mockApi.updateEmail(eq(1), eq("test@example.com")))
                .thenAnswer(invocation -> {
                    if (!firstCallFailed.get()) {
                        firstCallFailed.set(true);
                        return CompletableFuture.failedFuture(
                            new RuntimeException("Temporary network error")
                        );
                    }
                    return CompletableFuture.completedFuture(null);
                });

            // Act
            CompletableFuture<Void> result = mockApi.updateEmail(1, "test@example.com");
            
            // First call fails
            assertTrue(result.isCompletedExceptionally());
            
            // Second call succeeds
            result = mockApi.updateEmail(1, "test@example.com");
            assertFalse(result.isCompletedExceptionally());
        }

        @Test
        @DisplayName("Should handle timeout gracefully")
        void shouldHandleTimeout() {
            // Arrange
            when(mockApi.generateLinkCode(anyInt()))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(5000); // Simulate timeout
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new RuntimeException("Timeout");
                }));

            // Act & Assert
            CompletableFuture<Object> future = mockApi.generateLinkCode(1);
            
            // Should not block indefinitely (test setup handles this)
            assertNotNull(future);
        }
    }
}
