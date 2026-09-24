package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;

/**
 * Unit tests for UsersDataAccess gateway.
 */
public class UsersDataAccessTest {
    
    private UsersDataAccess gateway;
    private UserCache cache;
    private UsersQueryApi usersQueryApi;
    private UsersCommandApi usersCommandApi;
    private UUID testUuid;
    private UserSummary testUser;
    
    @BeforeEach
    void setUp() {
        cache = new UserCache(Duration.ofMinutes(15));
        usersQueryApi = new StubUsersQueryApi();
        usersCommandApi = new StubUsersCommandApi();
        gateway = new UsersDataAccess(cache, usersQueryApi, usersCommandApi);
        
        testUuid = UUID.randomUUID();
        testUser = new UserSummary(42, "TestPlayer", testUuid, 250);
    }
    
    @Test
    void testGetByUuid_CacheHit() throws Exception {
        // Arrange: Pre-populate cache
        cache.put(testUser);
        
        // Act
        FetchResult<UserSummary> result = gateway.getByUuidAsync(testUuid).get();
        
        // Assert
        assertEquals(FetchStatus.HIT, result.status());
        assertTrue(result.isSuccess());
        assertEquals(testUser, result.value().orElse(null));
        assertEquals(DataSource.CACHE, result.source());
    }
    
    @Test
    void testGetByUuid_CacheMissThenApiFetch() throws Exception {
        // Arrange: StubApi returns testUser
        
        // Act
        FetchResult<UserSummary> result = gateway.getByUuidAsync(testUuid).get();
        
        // Assert
        assertEquals(FetchStatus.MISS_FETCHED, result.status());
        assertTrue(result.isSuccess());
        assertEquals(testUser, result.value().orElse(null));
        assertEquals(DataSource.API, result.source());
        
        // Verify cache was populated
        assertTrue(cache.getByUuid(testUuid).isPresent());
        assertEquals(testUser, cache.getByUuid(testUuid).get());
    }
    
    @Test
    void testGetByUuid_NotFound() throws Exception {
        // Arrange: API returns null for unknown UUID
        UUID unknownUuid = UUID.randomUUID();
        
        // Act
        FetchResult<UserSummary> result = gateway.getByUuidAsync(unknownUuid).get();
        
        // Assert
        assertEquals(FetchStatus.NOT_FOUND, result.status());
        assertFalse(result.isSuccess());
        assertTrue(result.value().isEmpty());
    }
    
    @Test
    void testGetByUsername_ApiCall() throws Exception {
        // Arrange: API returns user for username
        
        // Act
        FetchResult<UserSummary> result = gateway.getByUsernameAsync("TestPlayer").get();
        
        // Assert
        assertEquals(FetchStatus.MISS_FETCHED, result.status());
        assertTrue(result.isSuccess());
        assertEquals(testUser, result.value().orElse(null));
        
        // Verify cache was populated by UUID
        assertTrue(cache.getByUuid(testUuid).isPresent());
    }
    
    @Test
    void testGetByUsername_NotFound() throws Exception {
        // Arrange: API returns null for unknown username
        
        // Act
        FetchResult<UserSummary> result = gateway.getByUsernameAsync("UnknownUser").get();
        
        // Assert
        assertEquals(FetchStatus.NOT_FOUND, result.status());
        assertFalse(result.isSuccess());
    }
    
    @Test
    void testRefresh_UpdatesCache() throws Exception {
        // Arrange: Cache old user, API returns updated user
        UserSummary oldUser = new UserSummary(42, "TestPlayer", testUuid, 100);
        cache.put(oldUser);
        
        // Act
        FetchResult<UserSummary> result = gateway.refreshAsync(testUuid).get();
        
        // Assert
        assertEquals(FetchStatus.MISS_FETCHED, result.status());
        assertTrue(result.isSuccess());
        assertEquals(testUser, result.value().orElse(null)); // Should have updated coins
        
        // Verify cache was updated
        UserSummary cached = cache.getByUuid(testUuid).get();
        assertEquals(250, cached.coins()); // Updated coins
    }
    
    @Test
    void testInvalidate_RemovesFromCache() throws Exception {
        // Arrange: Cache user
        cache.put(testUser);
        assertTrue(cache.getByUuid(testUuid).isPresent());
        
        // Act
        gateway.invalidate(testUuid);
        
        // Assert
        assertFalse(cache.getByUuid(testUuid).isPresent());
    }
    
    @Test
    void testInvalidateAll_ClearsCache() throws Exception {
        // Arrange: Cache multiple users
        cache.put(testUser);
        UUID uuid2 = UUID.randomUUID();
        cache.put(new UserSummary(43, "Player2", uuid2, 300));
        assertEquals(2, cache.size());
        
        // Act
        gateway.invalidateAll();
        
        // Assert
        assertEquals(0, cache.size());
    }
    
    @Test
    void testGetOrCreate_ExistingUser() throws Exception {
        // Arrange: User exists in API
        
        // Act
        UserDetail seed = new UserDetail(0, "TestPlayer", testUuid, "test@example.com", 250, new Date());
        FetchResult<UserSummary> result = gateway.getOrCreateAsync(testUuid, true, seed).get();
        
        // Assert
        assertTrue(result.isSuccess());
        assertEquals(testUser, result.value().orElse(null));
    }
    
    @Test
    void testGetOrCreate_NotFoundAndCreateFalse() throws Exception {
        // Arrange: User doesn't exist, create=false
        UUID unknownUuid = UUID.randomUUID();
        
        // Act
        UserDetail seed = new UserDetail(0, "NewPlayer", unknownUuid, "new@example.com", 250, new Date());
        FetchResult<UserSummary> result = gateway.getOrCreateAsync(unknownUuid, false, seed).get();
        
        // Assert
        assertEquals(FetchStatus.NOT_FOUND, result.status());
        assertFalse(result.isSuccess());
    }
    
    /**
     * Stub implementation of UsersQueryApi for testing.
     * Returns testUser for testUuid and "TestPlayer" username, null otherwise.
     */
    private class StubUsersQueryApi implements UsersQueryApi {
        @Override
        public CompletableFuture<UserDetail> getById(int id) {
            if (id == 42) {
                return CompletableFuture.completedFuture(
                    new UserDetail(42, "TestPlayer", testUuid, "test@example.com", 250, new Date())
                );
            }
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<UserSummary> getByUuid(UUID uuid) {
            if (uuid.equals(testUuid)) {
                return CompletableFuture.completedFuture(testUser);
            }
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<UserSummary> getByUsername(String username) {
            if ("TestPlayer".equals(username)) {
                return CompletableFuture.completedFuture(testUser);
            }
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<net.knightsandkings.knk.core.domain.common.Page<net.knightsandkings.knk.core.domain.users.UserListItem>> search(
            net.knightsandkings.knk.core.domain.common.PagedQuery query
        ) {
            return CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * Stub implementation of UsersCommandApi for testing.
     */
    private class StubUsersCommandApi implements UsersCommandApi {
        @Override
        public CompletableFuture<Void> setCoinsById(int id, int coins) {
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<Void> setCoinsByUuid(UUID uuid, int coins) {
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<UserDetail> create(UserDetail user) {
            // Return the user that was passed in (simulating creation)
            return CompletableFuture.completedFuture(user);
        }

        @Override
        public CompletableFuture<Void> setGatePassThroughMethodById(int id, GatePassThroughMethod method) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> setPresenceById(int id, boolean isOnline) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
