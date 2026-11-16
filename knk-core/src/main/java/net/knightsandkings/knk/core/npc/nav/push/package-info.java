/**
 * Push resistance for NPC bodies with collision on (implementation plan 4c.4 item 7, link M7; research report
 * 2026-11-15 § 6): push detection from the NPC's own movement ({@link net.knightsandkings.knk.core.npc.nav.push.PushWatch}),
 * the per-player record of repeated pushes ({@link net.knightsandkings.knk.core.npc.nav.push.PushLedger}) and the
 * edge guard ({@link net.knightsandkings.knk.core.npc.nav.push.EdgeGuard}). Engine-neutral over the navigation ports;
 * main thread.
 */
package net.knightsandkings.knk.core.npc.nav.push;
