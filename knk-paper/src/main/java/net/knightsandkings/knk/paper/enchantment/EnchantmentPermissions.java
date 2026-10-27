package net.knightsandkings.knk.paper.enchantment;

import java.util.Locale;

import org.bukkit.command.CommandSender;

import net.knightsandkings.knk.paper.commands.support.CommandPermissions;

/**
 * Who may use a custom enchantment (PandiO/knk-workspace#193). Before, the checks were Bukkit-only on
 * nodes plugin.yml didn't declare, so only ops could use abilities: the rank system never reaches Bukkit.
 * Now every check goes through {@link CommandPermissions} - Bukkit first (ops, through {@code default: op}),
 * then the in-house model (rank and group grants, wildcards).
 * <p>
 * A player may use enchantment {@code <id>} when they hold {@code customenchantments.<id>} or
 * {@code customenchantments.use.<id>}. The second form exists for the group grant {@value #USE_ALL}, which
 * covers every enchantment, also ones added later, without a plugin change. The group node is not
 * {@code customenchantments.*}: in the in-house model that wildcard would also grant the {@code /ce} admin
 * nodes ({@code customenchantments.command.*}). To withhold one enchantment from a rank that has
 * {@value #USE_ALL}, deny {@code customenchantments.use.<id>}.
 */
public final class EnchantmentPermissions {

    /** Prefix of the per-enchantment node, {@code customenchantments.<id>}. */
    public static final String NODE_PREFIX = "customenchantments.";
    /** Prefix of the group form, {@code customenchantments.use.<id>}. */
    public static final String USE_PREFIX = "customenchantments.use.";
    /** Grants every custom enchantment (the node for the Default rank). */
    public static final String USE_ALL = "customenchantments.use.*";

    private final CommandPermissions permissions;

    public EnchantmentPermissions(CommandPermissions permissions) {
        this.permissions = permissions != null ? permissions : CommandPermissions.bukkitOnly();
    }

    /** Plain Bukkit checks, for tests and when the API isn't configured. */
    public static EnchantmentPermissions bukkitOnly() {
        return new EnchantmentPermissions(CommandPermissions.bukkitOnly());
    }

    /** {@code customenchantments.<id>}. */
    public static String node(String enchantmentId) {
        return NODE_PREFIX + normalize(enchantmentId);
    }

    /** {@code customenchantments.use.<id>}, matched by {@value #USE_ALL}. */
    public static String useNode(String enchantmentId) {
        return USE_PREFIX + normalize(enchantmentId);
    }

    /**
     * Whether the sender may use the enchantment. Immediate (cache-only for the in-house model, which
     * fails closed until the player's permissions are loaded; they are preloaded at join). The console
     * always may.
     */
    public boolean canUse(CommandSender sender, String enchantmentId) {
        return permissions.has(sender, node(enchantmentId)) || permissions.has(sender, useNode(enchantmentId));
    }

    /** The command checks behind this, for the {@code /ce} subcommand nodes. */
    public CommandPermissions commands() {
        return permissions;
    }

    private static String normalize(String enchantmentId) {
        return enchantmentId == null ? "" : enchantmentId.trim().toLowerCase(Locale.ROOT);
    }
}
