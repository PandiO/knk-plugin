package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import net.knightsandkings.knk.api.dto.CreateUserRequestDto;
import net.knightsandkings.knk.api.dto.DuplicateCheckResponseDto;
import net.knightsandkings.knk.api.dto.UserResponseDto;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for UserManager.
 * Tests user account lifecycle management, caching, and duplicate detection.
 */
@ExtendWith(MockitoExtension.class)
class UserManagerTest {

    private UserManager userManager;
    private KnKPlugin mockPlugin;
    private UserAccountApi mockApi;
    private UsersQueryApi mockUsersQueryApi;
    private Logger mockLogger;
    private KnkConfig.AccountConfig mockAccountConfig;
    private KnkConfig.MessagesConfig mockMessagesConfig;
    private Player mockPlayer;
    private UUID testUUID;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(KnKPlugin.class);
        mockApi = mock(UserAccountApi.class);
        mockUsersQueryApi = mock(UsersQueryApi.class);
        mockLogger = mock(Logger.class);
        mockAccountConfig = mock(KnkConfig.AccountConfig.class);
        mockMessagesConfig = mock(KnkConfig.MessagesConfig.class);
        mockPlayer = mock(Player.class);
        
        testUUID = UUID.randomUUID();
        // Shared fixture: not every test reaches every stub, so they are lenient (PandiO/knk-workspace#499).
        lenient().when(mockPlayer.getUniqueId()).thenReturn(testUUID);
        lenient().when(mockPlayer.getName()).thenReturn("TestPlayer");
        
        UserCache mockUserCache = mock(UserCache.class);
        userManager = new UserManager(
            mockPlugin,
            mockApi,
            mockUsersQueryApi,
            mockUserCache,
            mockLogger,
            mockAccountConfig,
            mockMessagesConfig
        );
    }

    @Nested
    @DisplayName("Player Join Handling Tests")
    class PlayerJoinTests {

        @Test
        @DisplayName("Should create minimal user for new player (no duplicate)")
        void shouldCreateMinimalUserForNewPlayer() {
            // Arrange
            DuplicateCheckResponseDto noDuplicate = new DuplicateCheckResponseDto(
                false, null, null, null
            );
            
            UserResponseDto newUser = new UserResponseDto(
                1,
                "TestPlayer",
                testUUID.toString(),
                null, // no email
                100, // coins
                50,  // gems
                1000, // xp
                false,
                "MINECRAFT"
            );
            
            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.completedFuture(noDuplicate));
            when(mockApi.createUser(any(CreateUserRequestDto.class)))
                .thenReturn(CompletableFuture.completedFuture(newUser));

            // Act
            PlayerUserData result = userManager.onPlayerJoin(mockPlayer);

            // Assert
            assertNotNull(result);
            assertEquals(1, result.userId());
            assertEquals("TestPlayer", result.username());
            assertEquals(testUUID, result.uuid());
            assertNull(result.email());
            assertEquals(100, result.coins());
            assertEquals(50, result.gems());
            assertEquals(1000, result.experiencePoints());
            assertFalse(result.hasEmailLinked());
            assertFalse(result.hasDuplicateAccount());
            
            // Verify cached
            assertEquals(result, userManager.getCachedUser(testUUID));
        }

        /** PandiO/knk-workspace#486: the join's sync tells the first-join decision it created the account. */
        @Test
        @DisplayName("A created account is reported to the account-created listener; a found one is not")
        void aCreatedAccount_IsReportedForTheFirstJoinDecision() {
            java.util.List<UUID> created = new java.util.ArrayList<>();
            userManager.setAccountCreatedListener(created::add);
            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.completedFuture(new DuplicateCheckResponseDto(false, null, null, null)));
            when(mockApi.createUser(any(CreateUserRequestDto.class))).thenReturn(CompletableFuture.completedFuture(
                new UserResponseDto(11, "TestPlayer", testUUID.toString(), null, 0, 0, 0, false, "MINECRAFT")));

            userManager.onPlayerJoin(mockPlayer);

            assertEquals(java.util.List.of(testUUID), created);
        }

        @Test
        @DisplayName("Should handle duplicate account detection")
        void shouldHandleDuplicateAccount() {
            // Arrange
            UserResponseDto primaryUser = new UserResponseDto(
                1, "TestPlayer", testUUID.toString(), "primary@example.com",
                100, 50, 1000, true, "WEB_APP"
            );
            
            UserResponseDto conflictingUser = new UserResponseDto(
                2, "TestPlayer", testUUID.toString(), null,
                200, 75, 2000, false, "MINECRAFT"
            );
            
            DuplicateCheckResponseDto duplicateFound = new DuplicateCheckResponseDto(
                true, conflictingUser, primaryUser, "Duplicate accounts detected"
            );
            
            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.completedFuture(duplicateFound));

            // Act
            PlayerUserData result = userManager.onPlayerJoin(mockPlayer);

            // Assert
            assertNotNull(result);
            assertTrue(result.hasDuplicateAccount());
            assertEquals(1, result.userId()); // Primary user ID
            assertEquals(2, result.conflictingUserId()); // Conflicting user ID
            
            // Verify cached
            assertEquals(result, userManager.getCachedUser(testUUID));
        }

        @Test
        @DisplayName("Should handle existing user (no duplicate, user exists)")
        void shouldHandleExistingUser() {
            // Arrange: no duplicate, and the API already knows this UUID. UserManager looks the user up by UUID
            // (UsersQueryApi) and uses it without creating one; the duplicate check's primaryUser is not read
            // (PandiO/knk-workspace#499 updated this test from that older flow).
            DuplicateCheckResponseDto noDuplicate = new DuplicateCheckResponseDto(
                false, null, null, null
            );
            UserSummary existingUser = new UserSummary(
                1, "TestPlayer", testUUID, "test@example.com",
                500, 250, 5000, true, false, GatePassThroughMethod.DEFAULT
            );

            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.completedFuture(noDuplicate));
            when(mockUsersQueryApi.getByUuid(testUUID))
                .thenReturn(CompletableFuture.completedFuture(existingUser));

            // Act
            PlayerUserData result = userManager.onPlayerJoin(mockPlayer);

            // Assert
            assertNotNull(result);
            assertEquals(1, result.userId());
            assertEquals("TestPlayer", result.username());
            assertEquals("test@example.com", result.email());
            assertEquals(500, result.coins());
            assertEquals(250, result.gems());
            assertEquals(5000, result.experiencePoints());
            assertTrue(result.hasEmailLinked());
            assertFalse(result.hasDuplicateAccount());
            verify(mockApi, never()).createUser(any(CreateUserRequestDto.class));
        }

        @Test
        @DisplayName("Should handle API errors gracefully")
        void shouldHandleApiErrors() {
            // Arrange
            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.failedFuture(
                    new RuntimeException("Network error")
                ));

            // Act
            PlayerUserData result = userManager.onPlayerJoin(mockPlayer);

            // Assert - Should create fallback minimal entry
            assertNotNull(result);
            assertEquals("TestPlayer", result.username());
            assertEquals(testUUID, result.uuid());
            
            // Verify error was logged
            verify(mockLogger, atLeastOnce()).severe(anyString());
        }

        @Test
        @DisplayName("Should handle null email in user response")
        void shouldHandleNullEmail() {
            // Arrange
            DuplicateCheckResponseDto noDuplicate = new DuplicateCheckResponseDto(
                false, null, null, null
            );
            
            UserResponseDto userWithNullEmail = new UserResponseDto(
                1, "TestPlayer", testUUID.toString(), null,
                100, 50, 1000, false, "MINECRAFT"
            );
            
            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.completedFuture(noDuplicate));
            when(mockApi.createUser(any(CreateUserRequestDto.class)))
                .thenReturn(CompletableFuture.completedFuture(userWithNullEmail));

            // Act
            PlayerUserData result = userManager.onPlayerJoin(mockPlayer);

            // Assert
            assertNull(result.email());
            assertFalse(result.hasEmailLinked());
        }

        @Test
        @DisplayName("Should handle blank email in user response")
        void shouldHandleBlankEmail() {
            // Arrange
            DuplicateCheckResponseDto noDuplicate = new DuplicateCheckResponseDto(
                false, null, null, null
            );
            
            UserResponseDto userWithBlankEmail = new UserResponseDto(
                1, "TestPlayer", testUUID.toString(), "   ",
                100, 50, 1000, false, "MINECRAFT"
            );
            
            when(mockApi.checkDuplicate(testUUID.toString(), "TestPlayer"))
                .thenReturn(CompletableFuture.completedFuture(noDuplicate));
            when(mockApi.createUser(any(CreateUserRequestDto.class)))
                .thenReturn(CompletableFuture.completedFuture(userWithBlankEmail));

            // Act
            PlayerUserData result = userManager.onPlayerJoin(mockPlayer);

            // Assert
            assertFalse(result.hasEmailLinked());
        }
    }

    @Nested
    @DisplayName("Cache Management Tests")
    class CacheManagementTests {

        @Test
        @DisplayName("Should return null for uncached player")
        void shouldReturnNullForUncachedPlayer() {
            // Act
            PlayerUserData result = userManager.getCachedUser(testUUID);

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("Should cache player data on join")
        void shouldCachePlayerDataOnJoin() {
            // Arrange
            mockSuccessfulJoin();

            // Act
            userManager.onPlayerJoin(mockPlayer);
            PlayerUserData cached = userManager.getCachedUser(testUUID);

            // Assert
            assertNotNull(cached);
            assertEquals("TestPlayer", cached.username());
        }

        @Test
        @DisplayName("Should update cached player data")
        void shouldUpdateCachedPlayerData() {
            // Arrange
            mockSuccessfulJoin();
            userManager.onPlayerJoin(mockPlayer);
            
            PlayerUserData original = userManager.getCachedUser(testUUID);
            PlayerUserData updated = new PlayerUserData(
                original.userId(),
                original.username(),
                original.uuid(),
                "new-email@example.com",
                original.coins(),
                original.gems(),
                original.experiencePoints(),
                true, // email now linked
                false,
                null,
                GatePassThroughMethod.DEFAULT
            );

            // Act
            userManager.updateCachedUser(testUUID, updated);
            PlayerUserData result = userManager.getCachedUser(testUUID);

            // Assert
            assertEquals("new-email@example.com", result.email());
            assertTrue(result.hasEmailLinked());
        }

        @Test
        @DisplayName("Should clear cached player data")
        void shouldClearCachedPlayerData() {
            // Arrange
            mockSuccessfulJoin();
            userManager.onPlayerJoin(mockPlayer);
            assertNotNull(userManager.getCachedUser(testUUID));

            // Act
            userManager.clearCachedUser(testUUID);

            // Assert
            assertNull(userManager.getCachedUser(testUUID));
        }

        @Test
        @DisplayName("Should handle clearing non-existent cache entry")
        void shouldHandleClearingNonExistentEntry() {
            // Act & Assert - Should not throw exception
            assertDoesNotThrow(() -> userManager.clearCachedUser(testUUID));
        }

        @Test
        @DisplayName("Should maintain separate cache entries for different players")
        void shouldMaintainSeparateCacheEntries() {
            // Arrange
            Player player1 = mock(Player.class);
            UUID uuid1 = UUID.randomUUID();
            when(player1.getUniqueId()).thenReturn(uuid1);
            when(player1.getName()).thenReturn("Player1");
            
            Player player2 = mock(Player.class);
            UUID uuid2 = UUID.randomUUID();
            when(player2.getUniqueId()).thenReturn(uuid2);
            when(player2.getName()).thenReturn("Player2");
            
            mockSuccessfulJoinForPlayer(player1, uuid1, "Player1");
            mockSuccessfulJoinForPlayer(player2, uuid2, "Player2");

            // Act
            userManager.onPlayerJoin(player1);
            userManager.onPlayerJoin(player2);

            // Assert
            PlayerUserData data1 = userManager.getCachedUser(uuid1);
            PlayerUserData data2 = userManager.getCachedUser(uuid2);
            
            assertNotNull(data1);
            assertNotNull(data2);
            assertEquals("Player1", data1.username());
            assertEquals("Player2", data2.username());
            assertNotEquals(data1.userId(), data2.userId());
        }
    }

    @Nested
    @DisplayName("Configuration Access Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should provide access to account config")
        void shouldProvideAccountConfig() {
            // Act
            KnkConfig.AccountConfig config = userManager.getAccountConfig();

            // Assert
            assertNotNull(config);
            assertEquals(mockAccountConfig, config);
        }

        @Test
        @DisplayName("Should provide access to messages config")
        void shouldProvideMessagesConfig() {
            // Act
            KnkConfig.MessagesConfig config = userManager.getMessagesConfig();

            // Assert
            assertNotNull(config);
            assertEquals(mockMessagesConfig, config);
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should handle concurrent cache updates")
        void shouldHandleConcurrentCacheUpdates() throws InterruptedException {
            // Arrange
            mockSuccessfulJoin();
            userManager.onPlayerJoin(mockPlayer);
            
            PlayerUserData original = userManager.getCachedUser(testUUID);
            
            // Act - Simulate concurrent updates
            Thread thread1 = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    PlayerUserData updated = new PlayerUserData(
                        original.userId(),
                        original.username(),
                        original.uuid(),
                        "thread1@example.com",
                        i, i, i,
                        true, false, null, GatePassThroughMethod.DEFAULT
                    );
                    userManager.updateCachedUser(testUUID, updated);
                }
            });
            
            Thread thread2 = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    PlayerUserData updated = new PlayerUserData(
                        original.userId(),
                        original.username(),
                        original.uuid(),
                        "thread2@example.com",
                        i * 2, i * 2, i * 2,
                        true, false, null, GatePassThroughMethod.DEFAULT
                    );
                    userManager.updateCachedUser(testUUID, updated);
                }
            });
            
            thread1.start();
            thread2.start();
            thread1.join();
            thread2.join();

            // Assert - Should not crash and should have some valid data
            PlayerUserData result = userManager.getCachedUser(testUUID);
            assertNotNull(result);
            assertTrue(result.email().equals("thread1@example.com") || 
                      result.email().equals("thread2@example.com"));
        }
    }

    // Helper methods
    
    private void mockSuccessfulJoin() {
        mockSuccessfulJoinForPlayer(mockPlayer, testUUID, "TestPlayer");
    }
    
    private void mockSuccessfulJoinForPlayer(Player player, UUID uuid, String username) {
        DuplicateCheckResponseDto noDuplicate = new DuplicateCheckResponseDto(
            false, null, null, null
        );
        
        UserResponseDto newUser = new UserResponseDto(
            (int) (uuid.getMostSignificantBits() & 0xFFFF), // Generate unique ID from UUID
            username,
            uuid.toString(),
            null,
            100, 50, 1000,
            false,
            "MINECRAFT"
        );
        
        when(mockApi.checkDuplicate(uuid.toString(), username))
            .thenReturn(CompletableFuture.completedFuture(noDuplicate));
        // Answer per request: a second player's stub must not replace the first one's (PandiO/knk-workspace#499).
        lenient().when(mockApi.createUser(argThat((Object request) -> request instanceof CreateUserRequestDto dto && username.equals(dto.username()))))
            .thenReturn(CompletableFuture.completedFuture(newUser));
    }
}
