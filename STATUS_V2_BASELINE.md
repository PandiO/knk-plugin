# V2 Baseline Status — knk-plugin

**Date**: 2025-12-14  
**Build**: ✅ SUCCESS  
**Status**: Minimal baseline setup completed; ready for vertical slice implementation.

---

## ✅ What is Present

### Project Structure
- ✅ Root project: `knk-plugin` (Gradle 8.10, Java 21)
- ✅ Module: `knk-core` (domain + services + ports)
- ✅ Module: `knk-api-client` (HTTP client + DTOs + mappers)
- ✅ Module: `knk-paper` (Paper plugin adapter)

### Build Configuration

#### Root (`build.gradle.kts`)
- ✅ Java toolchain: JDK 21
- ✅ Repositories: mavenCentral, papermc.io
- ✅ UTF-8 encoding
- ✅ Subproject configuration applied

#### knk-core (`knk-core/build.gradle.kts`)
- ✅ JUnit 5 for testing
- ✅ No external dependencies (pure Java domain)
- ✅ Package structure: `net.knightsandkings.knk.core.*`
- ✅ Source directories: `src/main/java`, `src/test/java`
- ✅ Package-info documentation

#### knk-api-client (`knk-api-client/build.gradle.kts`)
- ✅ Dependency: `knk-core`
- ✅ OkHttp 4.12.0 (HTTP client)
- ✅ Jackson 2.17.2 (JSON serialization)
- ✅ JUnit 5 for testing
- ✅ Package structure: `net.knightsandkings.knk.api.*`
- ✅ Source directories: `src/main/java`, `src/test/java`
- ✅ Package-info documentation

#### knk-paper (`knk-paper/build.gradle.kts`)
- ✅ Dependency: `knk-core`, `knk-api-client`
- ✅ Paper API 1.21.10-R0.1-SNAPSHOT (compileOnly)
- ✅ Shadow plugin 8.1.1 (uber JAR)
- ✅ `deployToDevServer` task (auto-deploy to dev server)
- ✅ `dev` convenience task
- ✅ Package structure: `net.knightsandkings.knk.paper.*`

### Plugin Configuration

#### plugin.yml
```yaml
name: KnightsAndKings
version: 0.1.0
main: net.knightsandkings.knk.paper.KnKPlugin
api-version: "1.21"
```
- ✅ Main class: `net.knightsandkings.knk.paper.KnKPlugin` ✅ CORRECT
- ✅ Paper API version: 1.21
- ✅ Plugin name: KnightsAndKings

### Plugin Code

#### KnKPlugin.java
```java
public class KnKPlugin extends JavaPlugin {
    @Override
    public void onEnable() { ... }
    
    @Override
    public void onDisable() { ... }
}
```
- ✅ Extends JavaPlugin
- ✅ onEnable/onDisable stubs
- ✅ Located at: `knk-paper/src/main/java/net/knightsandkings/knk/paper/KnKPlugin.java`

### Build Artifacts
- ✅ Uber JAR: `knk-paper/build/libs/knk-paper-0.1.0-SNAPSHOT.jar`
- ✅ Shaded dependencies: OkHttp, Jackson, knk-core, knk-api-client
- ✅ Auto-deploy to dev server: `DEV_SERVER_1.21.10/plugins/`

### Build Commands
- ✅ `./gradlew :knk-paper:build` — builds paper plugin (includes shadowJar + deploy)
- ✅ `./gradlew :knk-core:build` — builds core module
- ✅ `./gradlew :knk-api-client:build` — builds API client module
- ✅ `./gradlew build` — builds all modules
- ✅ `./gradlew :knk-paper:dev` — quick dev build + deploy

---

## 📋 TODO for Vertical Slice (Step 1)

### Step 1 Target: Status Command (User Lookup)
**Objective**: Prove end-to-end async architecture: Paper command → service → port → HTTP client → response.

### 1. knk-core — Domain & Ports

#### 1.1 Domain Model
- [ ] Create `net.knightsandkings.knk.core.domain.user.User` (Java 21 record)
  - Fields: `UUID id`, `String name`, `String email`, `long createdAtMs`
  - Validation in compact constructor

#### 1.2 Port Interface
- [ ] Create `net.knightsandkings.knk.core.ports.api.UserPort` (interface)
  - Method: `CompletableFuture<User> getUser(UUID uuid)`
  - Return type: `CompletableFuture` for async

#### 1.3 Service
- [ ] Create `net.knightsandkings.knk.core.services.user.UserService` (class)
  - Constructor: inject `UserPort`
  - Method: `CompletableFuture<User> getUser(UUID uuid)` → delegates to port

#### 1.4 Exception
- [ ] Create `net.knightsandkings.knk.core.exception.UserNotFoundException` (extends RuntimeException)

### 2. knk-api-client — HTTP Client & DTOs

#### 2.1 DTO
- [ ] Create `net.knightsandkings.knk.api.dto.UserDto` (record)
  - Jackson annotations: `@JsonProperty("id")`, etc.
  - Fields: `String id`, `String name`, `String email`, `long createdAtMs`

#### 2.2 Mapper
- [ ] Create `net.knightsandkings.knk.api.mapper.UserMapper` (class)
  - Static method: `User toDomain(UserDto dto)`
  - Static method: `UserDto toDto(User user)`

#### 2.3 HTTP Client
- [ ] Create `net.knightsandkings.knk.api.impl.UserApiClient` (implements `UserPort`)
  - Constructor: inject `OkHttpClient`, `ObjectMapper`, `String baseUrl`
  - Method: `CompletableFuture<User> getUser(UUID uuid)`
    - HTTP GET: `/api/v1/users/{uuid}`
    - Parse JSON → UserDto → User
    - Return CompletableFuture with async executor

#### 2.4 API Client Factory
- [ ] Create `net.knightsandkings.knk.api.client.KnkApiClient` (factory)
  - Constructor: `baseUrl`, `timeout`, `auth`
  - Method: `UserPort getUserPort()` → returns `UserApiClient` instance

#### 2.5 Exception
- [ ] Create `net.knightsandkings.knk.api.exception.ApiException` (extends RuntimeException)
  - Fields: `int statusCode`, `String responseBody`

### 3. knk-paper — Command & Bootstrap

#### 3.1 Command
- [ ] Create `net.knightsandkings.knk.paper.commands.StatusCommand` (implements CommandExecutor)
  - Constructor: inject `UserService`
  - Method: `onCommand(CommandSender sender, Command cmd, String label, String[] args)`
    - Get player UUID
    - Call `userService.getUser(uuid)` async
    - `.thenAccept()` → schedule main-thread message
    - `.exceptionally()` → handle error, send error message

#### 3.2 Bootstrap
- [ ] Update `KnKPlugin.onEnable()`:
  - Load config (API base URL, timeout)
  - Create `KnkApiClient` instance
  - Create `UserService` instance
  - Register `/status` command with `StatusCommand`
- [ ] Add `KnKPlugin.onDisable()`:
  - Shutdown HTTP client, executor pools

#### 3.3 Config
- [ ] Create `net.knightsandkings.knk.paper.config.PluginConfig` (record)
  - Fields: `String apiBaseUrl`, `Duration httpTimeout`, etc.
  - Static factory: `fromConfig(FileConfiguration yaml)`
- [ ] Create `config.yml` in `knk-paper/src/main/resources/`:
  ```yaml
  api:
    base-url: "http://localhost:8080"
    timeout: 5
  ```

#### 3.4 plugin.yml
- [ ] Add `/status` command:
  ```yaml
  commands:
    status:
      description: Get player status
      permission: knk.status
      usage: /status
  ```

### 4. Testing

#### 4.1 Unit Tests (knk-core)
- [ ] Test `UserService.getUser()` with mocked `UserPort`
- [ ] Verify `CompletableFuture` behavior
- [ ] Test validation in `User` record

#### 4.2 Integration Tests (knk-api-client)
- [ ] Mock HTTP server (WireMock or MockWebServer)
- [ ] Test `UserApiClient.getUser()` with JSON response
- [ ] Test error responses (404, 500) → `ApiException`
- [ ] Test DTO mapping

#### 4.3 Manual Testing (Paper Dev Server)
- [ ] Start Paper dev server (1.21.10, Java 21)
- [ ] Load plugin JAR
- [ ] Run `/status` in-game
- [ ] Verify async execution (no server lag)
- [ ] Verify error handling (invalid UUID)
- [ ] Check logs for HTTP requests

### 5. Mock API Backend (Optional)
- [ ] Create simple HTTP mock server (or use existing backend)
- [ ] Endpoint: `GET /api/v1/users/{uuid}`
- [ ] Response:
  ```json
  {
    "id": "uuid-string",
    "name": "PlayerName",
    "email": "player@example.com",
    "createdAtMs": 1234567890
  }
  ```

---

## 🚧 Known Issues / Gaps

### Dependencies
- ✅ No issues detected

### Build
- ✅ Build successful (fixed task dependency issue)
- ✅ shadowJar produces uber JAR
- ✅ deployToDevServer works

### Architecture
- ⚠️ **No actual code yet** — only skeleton structure and package-info
- ⚠️ **Threading executor not configured** — need to create ExecutorService for async operations
- ⚠️ **No config loading** — plugin.yml exists but no config.yml or config loader
- ⚠️ **No dependency injection framework** — using simple factory pattern for now
- ⚠️ **No logging framework** — using Bukkit logger; consider SLF4J + Logback

### Testing
- ⚠️ **No tests yet** — JUnit is configured but no test classes exist

---

## 📊 Baseline Metrics

| Metric | Value |
|---|---|
| Build time | ~8s (clean build) |
| JAR size | ~1.5 MB (shaded) |
| Modules | 3 |
| Java classes | 1 (KnKPlugin) |
| Lines of code | ~14 |
| Test coverage | 0% |

---

## ✅ Verification

### Build Verification
```bash
PS> .\gradlew :knk-paper:build
BUILD SUCCESSFUL in 8s
9 actionable tasks: 3 executed, 6 up-to-date
```

### JAR Contents (shadowJar)
- ✅ `net/knightsandkings/knk/paper/KnKPlugin.class`
- ✅ `plugin.yml`
- ✅ `com/squareup/okhttp3/**` (shaded)
- ✅ `com/fasterxml/jackson/**` (shaded)

### plugin.yml Validation
- ✅ `main: net.knightsandkings.knk.paper.KnKPlugin` is correct
- ✅ API version: 1.21
- ⚠️ No commands defined yet (will add `/status` in vertical slice)

---

## 🎯 Next Steps (Priority Order)

1. **Implement vertical slice** (see TODO section above)
2. **Create domain models** (User record)
3. **Create ports & services** (UserPort, UserService)
4. **Implement HTTP client** (UserApiClient)
5. **Create Paper command** (StatusCommand)
6. **Bootstrap wiring** (KnKPlugin updates)
7. **Add unit tests** (knk-core, knk-api-client)
8. **Manual testing** (Paper dev server)
9. **Document learnings** (update MIGRATION_PLAN.md if needed)

---

## 📝 Notes

- Package root `net.knightsandkings.knk` is consistent across all modules ✅
- Copilot instructions are followed (no Bukkit in core/api-client) ✅
- Build infrastructure is solid (Gradle, shadowJar, auto-deploy) ✅
- Ready for feature implementation ✅

**Conclusion**: Baseline setup is **COMPLETE** and **VERIFIED**. Ready to proceed with vertical slice implementation (Step 1 of migration plan).

