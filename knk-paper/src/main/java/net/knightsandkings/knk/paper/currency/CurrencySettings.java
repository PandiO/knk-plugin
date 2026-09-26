package net.knightsandkings.knk.paper.currency;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code currency:} block and {@code messages.currency.*} of config.yml (currency DESIGN.md
 * §3.6). Every message has a built-in default, so an older config.yml without the block keeps
 * working. Templates use {@code &} colour codes and {@code {placeholder}}s; values are put in
 * after the colours are applied, so a name or reason can't inject formatting.
 */
public final class CurrencySettings {

    static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
        Map.entry("pay-sent", "&aSent &6{amount} {currency} &ato &e{player}&a. Balance: &6{balance}"),
        Map.entry("pay-received", "&aYou received &6{amount} {currency} &afrom &e{player}&a."),
        Map.entry("pay-confirm", "&eSend &6{amount} {currency} &eto &e{player}&e? {confirm} {cancel} &7(expires in {seconds}s)"),
        Map.entry("pay-confirm-button", "&a&l[Confirm]"),
        Map.entry("pay-cancel-button", "&c&l[Cancel]"),
        Map.entry("pay-cancelled", "&7Payment to &e{player} &7cancelled."),
        Map.entry("pay-expired", "&cThat payment request expired - send it again."),
        Map.entry("pay-closed", "&cThat payment request is no longer open."),
        Map.entry("pay-no-pending", "&cYou have no payment waiting for confirmation."),
        Map.entry("pay-insufficient", "&cYou only have &6{balance} {currency}&c."),
        Map.entry("pay-cap", "&cDaily limit reached - you can send &6{remaining} &cmore {currency} (resets in {when})."),
        Map.entry("pay-recipient-cap", "&e{player} &ccan't receive that much more {currency} today."),
        Map.entry("pay-new-account", "&cYour account must be {hours}h old and reach {title} before sending {currency}."),
        Map.entry("pay-not-transferable", "&c{Currency} can't be transferred."),
        Map.entry("pay-disabled", "&c{Currency} transfers are switched off right now."),
        Map.entry("pay-self", "&cYou can't pay yourself."),
        Map.entry("pay-unknown", "&cWe couldn't confirm the payment. Check &e/transactions &cbefore trying again."),
        Map.entry("pay-unknown-player", "&cNo player found named &e{player}&c."),
        Map.entry("pay-locked", "&cYour account can't send payments right now."),
        Map.entry("pay-recipient-locked", "&e{player} &ccan't receive payments right now."),
        Map.entry("pay-recipient-full", "&e{player} &ccan't hold that many more {currency}."),
        Map.entry("pay-cooldown", "&cSlow down - you can pay again in {when}."),
        Map.entry("pay-amount-range", "&cSend between &6{min} &cand &6{max} {currency} at a time."),
        Map.entry("pay-invalid-amount", "&cThe amount must be a whole number from 1 to 999,999,999 - no signs, decimals or separators."),
        Map.entry("pay-busy", "&7Your last payment is still being processed..."),
        Map.entry("pay-usage", "&eUsage: /pay <player> <amount> [coins|gems] &7| &e/pay confirm|cancel [id]"),
        Map.entry("balance-self", "&7Balance: &6{coins} coins &7| &b{gems} gems"),
        Map.entry("balance-other", "&e{player}&7: &6{coins} coins &7| &b{gems} gems"),
        Map.entry("baltop-header", "&6--- Richest players ({currency}) - page {page}/{pages} ---"),
        Map.entry("baltop-line", "&7{rank}. &e{player} &7- &6{amount}"),
        Map.entry("baltop-empty", "&7Nobody is on the leaderboard yet."),
        Map.entry("transactions-header", "&6--- {player}: {filter}history - page {page}/{pages} ---"),
        Map.entry("transactions-line", "&8{time} {change} &7{what} &8-> &f{balance}"),
        Map.entry("transactions-empty", "&7No transactions yet."),
        Map.entry("error-generic", "&cSomething went wrong - try again in a moment."),
        Map.entry("no-permission", "&cYou don't have permission to do that."),
        Map.entry("account-not-loaded", "&cYour account isn't loaded yet - try again in a moment.")
    );

    private final Map<String, String> messages;
    private final int clientCooldownSeconds;
    private final int baltopPageSize;
    private final int transactionsPageSize;

    public CurrencySettings(Map<String, String> overrides, int clientCooldownSeconds, int baltopPageSize, int transactionsPageSize) {
        this.messages = new HashMap<>(DEFAULT_MESSAGES);
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    messages.put(key, value);
                }
            });
        }
        this.clientCooldownSeconds = Math.max(0, clientCooldownSeconds);
        this.baltopPageSize = clamp(baltopPageSize, 1, 50, 10);
        this.transactionsPageSize = clamp(transactionsPageSize, 1, 50, 8);
    }

    /** Defaults only (tests, or a server without the block). */
    public static CurrencySettings defaults() {
        return new CurrencySettings(Map.of(), 3, 10, 8);
    }

    public static CurrencySettings from(FileConfiguration config) {
        Map<String, String> overrides = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("messages.currency");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                overrides.put(key, section.getString(key));
            }
        }
        return new CurrencySettings(overrides,
            config.getInt("currency.client-cooldown-seconds", 3),
            config.getInt("currency.baltop-page-size", 10),
            config.getInt("currency.transactions-page-size", 8));
    }

    /** UX only - the server enforces the real cooldown (CurrencyPolicy.CooldownSeconds). */
    public int clientCooldownSeconds() {
        return clientCooldownSeconds;
    }

    public int baltopPageSize() {
        return baltopPageSize;
    }

    public int transactionsPageSize() {
        return transactionsPageSize;
    }

    /** The coloured template for {@code key} (unknown keys render as the key itself). */
    public String template(String key) {
        return ChatColor.translateAlternateColorCodes('&', messages.getOrDefault(key, key));
    }

    /** {@code key}'s message with {@code placeholders} ({@code "amount", "1,000", "player", "Bob", ...}). */
    public String message(String key, String... placeholders) {
        return fill(template(key), placeholders);
    }

    /** Replaces {@code {name}} with its value; values lose any section sign so they can't carry formatting. */
    static String fill(String text, String... placeholders) {
        String result = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] == null ? "" : placeholders[i + 1].replace("§", "");
            result = result.replace("{" + placeholders[i] + "}", value);
        }
        return result;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value <= 0 ? fallback : Math.max(min, Math.min(max, value));
    }
}
