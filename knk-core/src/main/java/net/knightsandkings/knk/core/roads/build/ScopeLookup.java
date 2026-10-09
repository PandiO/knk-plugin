package net.knightsandkings.knk.core.roads.build;

import java.util.OptionalInt;

/**
 * Which town a column belongs to, for profiles with a town scope (DESIGN §3.1, plan D8: the scope is
 * a list of Town domain ids). Phase 3 answers it from the WorldGuard regions at the column; a build
 * without scoped profiles can pass {@link #NONE}.
 */
@FunctionalInterface
public interface ScopeLookup {

    /** No column belongs to any town: scoped profiles never apply. */
    ScopeLookup NONE = (x, z) -> OptionalInt.empty();

    /** The Town domain id owning the column {@code (x, z)}, if any. */
    OptionalInt townAt(int x, int z);
}
