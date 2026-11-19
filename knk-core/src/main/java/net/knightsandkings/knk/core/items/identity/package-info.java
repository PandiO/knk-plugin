/**
 * Item identity (PandiO/knk-workspace#141, production DESIGN §8): every item a player obtains resolves to one item
 * blueprint. Bukkit-free rules: the material → blueprint map ({@link net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap}),
 * the resolver ({@link net.knightsandkings.knk.core.items.identity.ItemIdentityResolver}: instance tag → blueprint tag
 * → vanilla match) with its retag rule, and the conversion log ({@link net.knightsandkings.knk.core.items.identity.ItemConversionLog}).
 */
package net.knightsandkings.knk.core.items.identity;
