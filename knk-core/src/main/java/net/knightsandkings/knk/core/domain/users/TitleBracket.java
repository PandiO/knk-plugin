package net.knightsandkings.knk.core.domain.users;

/**
 * One title bracket ({@code GET /api/title-brackets}, knk-web-api {@code TitleBracket}): the
 * gendered title names, the XP needed to hold it, its salary and the one-time bonuses for first
 * reaching it. Used by the InventoryMenu Profile and Player manager menus
 * (docs/specs/inventory-menu/CONTENT_PORT_PLAN.md CP3/CP8).
 */
public record TitleBracket(
        int id,
        String maleName,
        String femaleName,
        int minExperience,
        int salary,
        int coinBonus,
        int gemBonus,
        int expBonus
) {
    /** Same rule as the web-api's {@code TitleBracket.NameFor}: "Female" → female name, anything else (incl. unset) → male. */
    public String nameFor(String gender) {
        return "Female".equalsIgnoreCase(gender) && femaleName != null && !femaleName.isBlank() ? femaleName : maleName;
    }
}
