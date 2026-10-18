package net.knightsandkings.knk.core.domain.permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves single nodes against one user's {@link EffectivePermissionSet} the way knk-web-api's
 * {@code PermissionResolutionService.CheckAsync} does, so the plugin asks the API once per player
 * instead of once per node (KNG-107: {@code /knk help} sent a check per subcommand on a cold cache).
 * <p>
 * Rules, mirrored from the API (keep them in step): the holders are walked in chain order
 * ({@link EffectivePermission#rank()}, lowest first) and the first holder with any matching entry
 * decides. Within one holder the most specific match wins - an exact node, else the wildcard with
 * the longest literal prefix - and a deny beats a grant of the same specificity. A trailing
 * {@code *} matches every node sharing what comes before it, and also the node with the trailing
 * {@code .*} stripped ({@code knk.gate.*} matches {@code knk.gate}); a bare {@code *} matches
 * everything, least specifically. Nodes compare case-sensitively. No match anywhere = UNDECLARED.
 * <p>
 * The effective set keeps one entry per node string (the lowest-ranked holder declaring it); an
 * entry it drops is always shadowed by the same node at a lower rank, so the answers are the same
 * as the API's. Thread-safe; answers are memoized per node.
 */
public final class EffectivePermissionResolver {

    private final List<List<EffectivePermission>> holders;
    private final Map<String, PermissionCheckResult> answers = new ConcurrentHashMap<>();

    private EffectivePermissionResolver(List<List<EffectivePermission>> holders) {
        this.holders = holders;
    }

    /** A resolver for {@code set}, or empty when an entry has no rank (an API that doesn't send it). */
    public static Optional<EffectivePermissionResolver> of(EffectivePermissionSet set) {
        TreeMap<Integer, List<EffectivePermission>> byRank = new TreeMap<>();
        List<EffectivePermission> entries = set == null || set.permissions() == null ? List.of() : set.permissions();
        for (EffectivePermission entry : entries) {
            if (entry.rank() == null) {
                return Optional.empty();
            }
            if (entry.node() != null) {
                byRank.computeIfAbsent(entry.rank(), rank -> new ArrayList<>()).add(entry);
            }
        }
        return Optional.of(new EffectivePermissionResolver(List.copyOf(byRank.values())));
    }

    /** The resolution of {@code node}, as the API's single-node check would answer it. */
    public PermissionCheckResult resolve(String node) {
        return answers.computeIfAbsent(node, this::compute);
    }

    private PermissionCheckResult compute(String node) {
        for (List<EffectivePermission> holder : holders) {
            EffectivePermission best = bestMatch(holder, node);
            if (best != null) {
                return new PermissionCheckResult(node,
                    best.value() ? PermissionResolution.GRANTED : PermissionResolution.DENIED, best.source(), best.node());
            }
        }
        return new PermissionCheckResult(node, PermissionResolution.UNDECLARED, null, null);
    }

    private static EffectivePermission bestMatch(List<EffectivePermission> holder, String node) {
        EffectivePermission best = null;
        int bestSpecificity = -1;
        for (EffectivePermission entry : holder) {
            int specificity = specificity(entry.node(), node);
            if (specificity < 0) {
                continue;
            }
            if (specificity > bestSpecificity || (specificity == bestSpecificity && best.value() && !entry.value())) {
                best = entry;
                bestSpecificity = specificity;
            }
        }
        return best;
    }

    /** How specifically {@code grantNode} matches {@code node} (higher = more specific), or -1 when it doesn't. */
    static int specificity(String grantNode, String node) {
        if (grantNode.equals(node)) {
            return Integer.MAX_VALUE;
        }
        if (!grantNode.endsWith("*")) {
            return -1;
        }
        String prefix = grantNode.substring(0, grantNode.length() - 1);
        if (prefix.isEmpty()) {
            return 0;
        }
        String prefixNoTrailingDot = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return node.startsWith(prefix) || node.equals(prefixNoTrailingDot) ? prefix.length() : -1;
    }
}
