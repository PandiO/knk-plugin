package net.knightsandkings.knk.core.domain.permissions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Rules, mirrored from the API (keep them in step): the holders are walked in chain order (the
 * user's own grants first, then the groups in precedence order) and the first holder with any
 * matching entry decides. Within one holder the most specific match wins - an exact node, else the
 * wildcard with the longest literal prefix - and a deny beats a grant of the same specificity. A
 * trailing {@code *} matches every node sharing what comes before it, and also the node with the
 * trailing {@code .*} stripped ({@code knk.gate.*} matches {@code knk.gate}); a bare {@code *}
 * matches everything, least specifically. Nodes compare case-sensitively. No match = UNDECLARED.
 * <p>
 * The chain order comes from {@link EffectivePermission#rank()}. Without ranks (an API older than
 * KNG-107) a node is still answered exactly whenever the order can't change the outcome: the user's
 * own grants always come first, a single matching holder decides alone, and when every matching
 * holder's best entry agrees that is the answer. Only when two groups disagree is the node left
 * unresolved ({@link #resolve} is empty) for the caller to ask the API about that node alone.
 * <p>
 * The effective set keeps one entry per node string (the lowest-ranked holder declaring it); an
 * entry it drops is always shadowed by the same node at a lower rank, so the deciding holder's
 * matching entries are all present and the answers are the same as the API's. Thread-safe; answers
 * are memoized per node.
 */
public final class EffectivePermissionResolver {

    /** Each holder's entries, in chain order when the set is ranked, else the user first and the groups unordered. */
    private final List<List<EffectivePermission>> holders;
    private final boolean ranked;
    private final Map<String, Optional<PermissionCheckResult>> answers = new ConcurrentHashMap<>();

    private EffectivePermissionResolver(List<List<EffectivePermission>> holders, boolean ranked) {
        this.holders = holders;
        this.ranked = ranked;
    }

    /** A resolver for {@code set} (null or empty: every node UNDECLARED). */
    public static EffectivePermissionResolver of(EffectivePermissionSet set) {
        List<EffectivePermission> entries = set == null || set.permissions() == null ? List.of() : set.permissions();
        boolean ranked = entries.stream().allMatch(entry -> entry.rank() != null);
        if (ranked) {
            TreeMap<Integer, List<EffectivePermission>> byRank = new TreeMap<>();
            for (EffectivePermission entry : entries) {
                if (entry.node() != null) {
                    byRank.computeIfAbsent(entry.rank(), rank -> new ArrayList<>()).add(entry);
                }
            }
            return new EffectivePermissionResolver(List.copyOf(byRank.values()), true);
        }
        List<EffectivePermission> user = new ArrayList<>();
        Map<String, List<EffectivePermission>> groups = new LinkedHashMap<>();
        for (EffectivePermission entry : entries) {
            if (entry.node() == null) {
                continue;
            }
            if (isUser(entry.source())) {
                user.add(entry);
            } else {
                groups.computeIfAbsent(holderKey(entry.source()), key -> new ArrayList<>()).add(entry);
            }
        }
        List<List<EffectivePermission>> holders = new ArrayList<>();
        if (!user.isEmpty()) {
            holders.add(user);
        }
        holders.addAll(groups.values());
        return new EffectivePermissionResolver(List.copyOf(holders), false);
    }

    /** Whether every entry carried its chain rank (then every node resolves). */
    public boolean ranked() {
        return ranked;
    }

    /**
     * The resolution of {@code node}, as the API's single-node check would answer it; empty only for
     * an unranked set when two groups disagree about the node (ask the API about that node).
     */
    public Optional<PermissionCheckResult> resolve(String node) {
        return answers.computeIfAbsent(node, this::compute);
    }

    private Optional<PermissionCheckResult> compute(String node) {
        if (ranked) {
            for (List<EffectivePermission> holder : holders) {
                EffectivePermission best = bestMatch(holder, node);
                if (best != null) {
                    return Optional.of(result(node, best));
                }
            }
            return Optional.of(undeclared(node));
        }
        EffectivePermission decided = null;
        for (int i = 0; i < holders.size(); i++) {
            EffectivePermission best = bestMatch(holders.get(i), node);
            if (best == null) {
                continue;
            }
            if (i == 0 && isUser(best.source())) {
                return Optional.of(result(node, best)); // the user's own grants always come first
            }
            if (decided != null && decided.value() != best.value()) {
                return Optional.empty(); // two groups disagree; only the chain order can tell
            }
            if (decided == null) {
                decided = best;
            }
        }
        return Optional.of(decided != null ? result(node, decided) : undeclared(node));
    }

    private static PermissionCheckResult result(String node, EffectivePermission best) {
        return new PermissionCheckResult(node,
            best.value() ? PermissionResolution.GRANTED : PermissionResolution.DENIED, best.source(), best.node());
    }

    private static PermissionCheckResult undeclared(String node) {
        return new PermissionCheckResult(node, PermissionResolution.UNDECLARED, null, null);
    }

    private static boolean isUser(PermissionHolder source) {
        return source != null && "User".equals(source.type());
    }

    private static String holderKey(PermissionHolder source) {
        return source == null ? "?" : source.type() + ":" + source.id();
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
