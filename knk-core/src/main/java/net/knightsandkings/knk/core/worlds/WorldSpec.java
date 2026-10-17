package net.knightsandkings.knk.core.worlds;

import java.util.Objects;

/**
 * One world KnK loads at startup ({@link KnkWorldsConfig}).
 *
 * @param name        the world folder and Bukkit world name
 * @param environment its dimension type
 * @param generator   how new chunks are generated; applied at every load, so a void hub stays void beyond what was built
 * @param create      create the world when its folder does not exist yet
 * @param required    players may not join while it is missing (hub DESIGN §16: fail closed)
 */
public record WorldSpec(String name, Environment environment, Generator generator, boolean create, boolean required) {

    public enum Environment { NORMAL, NETHER, THE_END }

    /** {@code VOID}: empty, with a small stone platform at the spawn (a hub is built, not generated). */
    public enum Generator { DEFAULT, FLAT, VOID }

    public WorldSpec {
        Objects.requireNonNull(name, "name");
        environment = environment != null ? environment : Environment.NORMAL;
        generator = generator != null ? generator : Generator.DEFAULT;
    }
}
