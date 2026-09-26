package net.knightsandkings.knk.core.lootbox;

/** A grade as the runtime config lists it, so a box can be named by its stars ("Legendary Weapons Lootbox"). */
public record KnkLootboxGrade(int id, String name, int stars) {
}
