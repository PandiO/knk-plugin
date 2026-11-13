# Knights and Kings plugin (knk-plugin)

The Paper plugin of Knights and Kings, a Minecraft MMO: commands, listeners, inventory menus, gates and sieges,
WorldGuard regions and world tasks. It talks to `knk-web-api` over REST (`knk-api-client`) and SignalR.

Gradle modules (Kotlin DSL):

1. `knk-core`: domain models, services and ports, no Bukkit/Paper.
2. `knk-api-client`: the REST client to knk-web-api (OkHttp + Jackson), DTOs, auth.
3. `knk-paper`: the plugin itself (Paper API, see `paperApiVersion` in `gradle.properties`).

## Quick start

You need JDK 21 (runs Gradle 8.14) and JDK 25 (the toolchain that compiles and tests). If Gradle doesn't detect
JDK 25, pass `-Porg.gradle.java.installations.paths=<jdk25 dir>`.

```bash
./gradlew build -x deployToDevServer   # build and test all modules, no copy to a dev server
./gradlew test                          # tests only (integration / requires-bukkit tags are excluded)
./gradlew :knk-paper:dev                # shadowJar + copy into <devServerDirectory>/plugins
```

A plain `./gradlew build` also runs `deployToDevServer`, which copies the jar to the `devServerDirectory` set in
`gradle.properties`. The jar lands in `knk-paper/build/libs/`. On first start the plugin writes
`plugins/KnightsAndKings/config.yml`; set `api.base-url` and `api.auth.*` (the API's plugin key) there.

## Documentation

All documentation lives in [knk-workspace](https://github.com/PandiO/knk-workspace/tree/main/docs):

1. [Plugin architecture](https://github.com/PandiO/knk-workspace/blob/main/docs/architecture/plugin-architecture.md)
   and the [plugin reading guide](https://github.com/PandiO/knk-workspace/blob/main/docs/guides/plugin-reading-guide.md).
2. [Plugin configuration reference](https://github.com/PandiO/knk-workspace/blob/main/docs/guides/admin/plugin-configuration.md)
   (every `config.yml` key; existing server configs never get new keys automatically).
3. [Commands](https://github.com/PandiO/knk-workspace/blob/main/docs/guides/users/commands.md) and the other
   [player and admin guides](https://github.com/PandiO/knk-workspace/blob/main/docs/guides/README.md).

Agents and contributors: start at [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md).
