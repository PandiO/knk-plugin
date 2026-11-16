package net.knightsandkings.knk.core.npc.nav.push;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;

/**
 * Push detection for one NPC (implementation plan 4c.4 item 7, research report § 6.5 item 1): a displacement between
 * two looks that the NPC's own movement does not explain.
 * <ol>
 *     <li>A walking NPC explains a move up to {@link #MAX_SPEED} blocks per tick (plus {@link #SLACK}) that does not go
 *     back more than {@link #BACKWARD} against its walking direction. Height is never judged, so a stair step or a hop
 *     is its own walk. Sideways moves are its own too (a turn at a waypoint) unless something touched it within
 *     {@link #CONTACT_TICKS} ({@link #contact}): then more than {@link #SIDEWAYS} blocks sideways is a push.</li>
 *     <li>A standing NPC (no order, waiting, held) explains nothing: {@link #STANDING_PUSH} blocks from where it came to
 *     stand is a push.</li>
 * </ol>
 * One push is one episode: the looks within {@link #EPISODE_TICKS} of the last unexplained one belong to it, so a
 * player leaning on an NPC for three seconds gives about three pushes, not thirty; those later looks come back as
 * {@link Push#continued()}, so the edge guard sees where the push goes. Each push says where the NPC was
 * before it ({@link Push#from()}: its last explained position walking, or where it stood), which is where it returns
 * to; after it the watch measures from where the push left the body. The watch knows nothing of routes or the world;
 * main thread; one per NPC.
 */
public final class PushWatch {

    /** Ticks between two looks. */
    public static final int LOOK_TICKS = 2;
    /** Blocks per tick an NPC's own walk can take it (Citizens' walking speed with a follower's catch-up, rounded up). */
    public static final double MAX_SPEED = 0.4;
    /** Blocks a look may be off on top of {@link #MAX_SPEED} (position rounding, a waypoint overshot). */
    public static final double SLACK = 0.15;
    /** Blocks a walking NPC may move back against its walking direction between two looks (a waypoint overshot). */
    public static final double BACKWARD = 0.2;
    /** Blocks a walking NPC moved sideways between two looks, right after a contact, that make a push. */
    public static final double SIDEWAYS = 0.25;
    /** Blocks a standing NPC moved from where it came to stand that make a push. */
    public static final double STANDING_PUSH = 0.4;
    /** A contact counts for the looks this many ticks after it. */
    public static final int CONTACT_TICKS = 10;
    /** Looks within this many ticks of the last unexplained one belong to the same push. */
    public static final int EPISODE_TICKS = 20;

    /**
     * One push.
     *
     * @param from    where the NPC was before it: its last explained position while walking, else where it stood
     * @param to      where it is now
     * @param kind    what touched it last within {@link #CONTACT_TICKS}, an attack first, then a player; null: nothing
     *                the engine reported (water, a piston, another plugin)
     * @param by      the engine's id of who touched it, or null
     * @param walking whether it was walking when pushed
     * @param playerAttack for {@link NpcMover.ContactKind#ATTACK}: whether a player attacked
     * @param continued    a look later in an open push (the body is still being moved): not a new push, but the
     *                     edge guard looks again; {@link #from} is where the push began
     */
    public record Push(NavPoint from, NavPoint to, NpcMover.ContactKind kind, String by, boolean walking,
                       boolean playerAttack, boolean continued) {

        /** Horizontal blocks between {@link #from} and {@link #to}. */
        public double blocks() {
            return from.horizontalDistance(to);
        }

        /** Whether a player's body or attack did it, with the player known. */
        public boolean byPlayer() {
            return by != null && (kind == NpcMover.ContactKind.PLAYER
                || kind == NpcMover.ContactKind.ATTACK && playerAttack);
        }
    }

    private NavPoint last;
    private long lastTick;
    /** Where the NPC came to stand (null while walking). */
    private NavPoint rest;
    /** The NPC's last explained position while walking. */
    private NavPoint anchor;
    /** Where the open episode began, or null. */
    private NavPoint episodeFrom;
    private long episodeTick = Long.MIN_VALUE / 2;
    private long playerTick = Long.MIN_VALUE / 2;
    private String player;
    private long attackTick = Long.MIN_VALUE / 2;
    private String attacker;
    private boolean attackerIsPlayer;
    private long npcTick = Long.MIN_VALUE / 2;
    private String npc;
    private long otherTick = Long.MIN_VALUE / 2;
    private String other;
    private long pushes;

    /** What touched the body at {@code tick}. */
    public void contact(NpcMover.Contact contact, long tick) {
        switch (contact.kind()) {
            case PLAYER -> {
                playerTick = tick;
                player = contact.by();
            }
            case ATTACK -> {
                attackTick = tick;
                attacker = contact.by();
                attackerIsPlayer = contact.player();
            }
            case NPC -> {
                npcTick = tick;
                npc = contact.by();
            }
            case OTHER -> {
                otherTick = tick;
                other = contact.by();
            }
        }
    }

    /**
     * One look at the NPC's feet at {@code tick}; a push when the move since the last look is not its own.
     *
     * @param walking whether it walks under an order now (else it stands: idle, waiting, held)
     * @param dirX    its walking direction (any length; 0, 0 when unknown)
     */
    public Push look(NavPoint feet, long tick, boolean walking, double dirX, double dirZ) {
        if (last == null || !last.world().equals(feet.world())) {
            reset(feet, walking);
            lastTick = tick;
            return null;
        }
        int dt = (int) Math.max(1, tick - lastTick);
        double sx = feet.x() - last.x();
        double sz = feet.z() - last.z();
        boolean touched = recent(playerTick, tick) || recent(attackTick, tick) || recent(npcTick, tick)
            || recent(otherTick, tick);
        boolean explained;
        if (walking) {
            rest = null;
            if (anchor == null) {
                anchor = last;
            }
            double len = Math.hypot(dirX, dirZ);
            double step = Math.hypot(sx, sz);
            double along = len < 1e-9 ? step : (sx * dirX + sz * dirZ) / len;
            double sideways = len < 1e-9 ? 0 : Math.abs(sx * dirZ - sz * dirX) / len;
            explained = step <= MAX_SPEED * dt + SLACK && along >= -BACKWARD && !(touched && sideways > SIDEWAYS)
                && !(recent(attackTick, tick) && step > SLACK && along < step * 0.5);
        } else {
            anchor = null;
            if (rest == null) {
                rest = last;
            }
            explained = rest.horizontalDistance(feet) < STANDING_PUSH;
        }
        NavPoint before = walking ? anchor : rest;
        last = feet;
        lastTick = tick;
        if (explained) {
            if (walking) {
                anchor = feet;
            }
            return null;
        }
        // measured from here on: a pushed NPC that stays where it was pushed to is not pushed again
        if (walking) {
            anchor = feet;
        } else {
            rest = feet;
        }
        boolean open = tick - episodeTick <= EPISODE_TICKS;
        episodeTick = tick;
        if (!open) {
            episodeFrom = before;
            pushes++;
        }
        NpcMover.ContactKind kind = null;
        String by = null;
        boolean playerAttack = false;
        if (recent(attackTick, tick)) {
            kind = NpcMover.ContactKind.ATTACK;
            by = attacker;
            playerAttack = attackerIsPlayer;
        } else if (recent(playerTick, tick)) {
            kind = NpcMover.ContactKind.PLAYER;
            by = player;
        } else if (recent(npcTick, tick)) {
            kind = NpcMover.ContactKind.NPC;
            by = npc;
        } else if (recent(otherTick, tick)) {
            kind = NpcMover.ContactKind.OTHER;
            by = other;
        }
        return new Push(open ? episodeFrom : before, feet, kind, by, walking, playerAttack, open);
    }

    /** Where the open push episode began (where the NPC returns to), or null when none is open. */
    public NavPoint episodeFrom(long tick) {
        return tick - episodeTick <= EPISODE_TICKS ? episodeFrom : null;
    }

    /**
     * Starts over at {@code feet} (the NPC returned, was teleported by K&K, respawned, or got a new order): no anchor
     * from before, no open episode.
     */
    public void reset(NavPoint feet, boolean walking) {
        last = feet;
        rest = walking ? null : feet;
        anchor = walking ? feet : null;
        episodeFrom = null;
        episodeTick = Long.MIN_VALUE / 2;
    }

    /**
     * The NPC is back where a push took it from ({@code home}): a standing NPC's post is {@code home} again, not the
     * spot within a few tenths of it where its walk back ended, so its post does not creep with each push.
     */
    public void settle(NavPoint home) {
        if (rest != null && home != null && home.world().equals(rest.world())) {
            rest = home;
        }
    }

    /** Where the standing NPC came to stand (its post while it has no order), or null while it walks. */
    public NavPoint rest() {
        return rest;
    }

    /** Pushes seen so far. */
    public long pushes() {
        return pushes;
    }

    private static boolean recent(long contactTick, long tick) {
        return tick - contactTick <= CONTACT_TICKS;
    }
}
