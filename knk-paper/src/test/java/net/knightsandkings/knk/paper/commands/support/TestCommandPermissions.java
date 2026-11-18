package net.knightsandkings.knk.paper.commands.support;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;

/** {@link CommandPermissions} whose in-house model allows exactly the nodes {@code granted} accepts (tests). */
public final class TestCommandPermissions {
    private TestCommandPermissions() {
    }

    public static CommandPermissions inHouse(Predicate<String> granted) {
        return new CommandPermissions((player, node) -> granted.test(node),
            (player, node) -> CompletableFuture.completedFuture(
                granted.test(node) ? PermissionDecision.ALLOWED : PermissionDecision.DENIED),
            Runnable::run);
    }
}
