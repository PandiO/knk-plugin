# API Client Pattern (knk-plugin)

Guideline for adding a new HTTP endpoint across modules.

## 1) knk-core (pure domain + ports)
- Define domain record(s) in `net.knightsandkings.knk.core.domain`.
- Define port interface in `net.knightsandkings.knk.core.ports.api` returning `CompletableFuture<T>`.
- No Bukkit/Paper/OkHttp/Jackson imports.

**Example (Health):**
```java
// core/ports/api/HealthApi.java
CompletableFuture<HealthStatus> getHealth();

// core/domain/HealthStatus.java
public record HealthStatus(String status, String version) {
    public boolean isHealthy() { return "UP".equalsIgnoreCase(status) || "OK".equalsIgnoreCase(status); }
}
```

## 2) knk-api-client (HTTP + mapping)
- DTO in `net.knightsandkings.knk.api.dto` (Jackson annotated if needed).
- Mapper in `net.knightsandkings.knk.api.mapper` (domain ↔ DTO).
- Implementation in `net.knightsandkings.knk.api.impl` implements the core port.
- Use `KnkApiClient` builder for baseUrl, auth, timeouts, debug logging, SSL options.
- All I/O async via `CompletableFuture.supplyAsync(..., executor)`.
- On non-2xx: throw `ApiException(url, statusCode, message, responseSnippet)`.

**Example (Health):**
```java
// api/impl/HealthApiImpl.java
String url = baseUrl + "/health"; // TODO adjust if backend differs
Response resp = httpClient.newCall(request).execute();
if (!resp.isSuccessful()) throw new ApiException(url, resp.code(), "Health check failed", snippet(resp));
HealthStatusDto dto = objectMapper.readValue(resp.body().string(), HealthStatusDto.class);
return HealthStatusMapper.toDomain(dto);
```

## 3) knk-paper (wiring + commands/listeners)
- Load config and build `KnkApiClient` in `KnKPlugin.onEnable()`.
- Inject port into commands/listeners; never do HTTP on main thread.
- Schedule Bukkit mutations on main thread: `Bukkit.getScheduler().runTask(plugin, ...)`.

**Example (Health command):**
```java
healthApi.getHealth().thenAccept(health -> {
    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Status: " + health.status()));
}).exceptionally(ex -> {
    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Error: " + ex.getMessage()));
    return null;
});
```

## Threading Rules (critical)
- All HTTP/IO off the Paper main thread (use executor from `KnkApiClient`).
- Bukkit/world mutations only on main thread via scheduler.
- Keep auth secrets out of logs.

## Config Keys (knk-paper/src/main/resources/config.yml)
```yaml
api:
  base-url: "http://localhost:5294/api"
  auth:
    type: none|bearer|apikey
    bearer-token: "..."
    api-key: "..."
    api-key-header: "X-API-Key"
  timeouts:
    connect: 10
    read: 10
    write: 10
  debug-logging: false
  allow-untrusted-ssl: false   # true only for dev/self-signed
```

## Minimal Checklist to Add an Endpoint
1) Core: add domain record + port interface (CompletableFuture).
2) API-client: add DTO, mapper, impl; hook into `KnkApiClient` (add getter).
3) Paper: wire `KnkApiClient` in `KnKPlugin`; expose via command/listener; keep async.
4) Error handling: throw `ApiException` with URL + status + snippet; show safe errors to users.
5) Tests: unit tests for domain/mapper; optional integration with MockWebServer.

Use HealthApi as reference for structure and logging patterns.
