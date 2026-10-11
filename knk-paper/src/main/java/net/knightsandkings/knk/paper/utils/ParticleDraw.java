package net.knightsandkings.knk.paper.utils;

import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Per-viewer particle drawing (road navigation plan §2 R9: extracted from
 * {@code SiegeWorldPresenter.ring}, which delegates here unchanged). Every method spawns the
 * particles for the given viewers only ({@link Player#spawnParticle}), skips viewers in another
 * world or farther than {@link #PARTICLE_RANGE} from the shape, and never touches the world.
 */
public final class ParticleDraw {
    /** Viewers farther than this from a shape's reference point don't get its particles. */
    public static final double PARTICLE_RANGE = 64.0;

    private ParticleDraw() {
    }

    /**
     * A flat ring of {@code particle} around {@code center} at {@code center.y + 0.15}, as the siege
     * capture rings draw it: at least 12 points, 8 per block of radius. Nothing for a radius {@code <= 0}.
     */
    public static void ring(List<Player> viewers, Location center, double radius, Particle particle) {
        if (radius <= 0) return;
        World world = center.getWorld();
        int points = Math.max(12, (int) Math.ceil(radius * 8));
        for (Player viewer : viewers) {
            if (!canSee(viewer, world, center.getX(), center.getY(), center.getZ())) continue;
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                viewer.spawnParticle(particle, center.getX() + radius * Math.cos(angle), center.getY() + 0.15,
                        center.getZ() + radius * Math.sin(angle), 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * A polyline through {@code points} (world coordinates, e.g. block centres) with one particle every
     * {@code spacing} blocks along it, for one viewer. {@code data} is the particle's data
     * ({@link Particle.DustOptions} for DUST) or null. Segments entirely farther than
     * {@link #PARTICLE_RANGE} from the viewer are skipped.
     */
    public static void polyline(Player viewer, List<Vector> points, double spacing, Particle particle, Object data) {
        Objects.requireNonNull(points, "points");
        if (points.size() < 2 || spacing <= 0) {
            if (points.size() == 1) {
                spawn(viewer, particle, data, points.get(0));
            }
            return;
        }
        World world = viewer.getWorld();
        double carry = 0; // distance already covered since the last particle, carried across vertices
        for (int i = 0; i + 1 < points.size(); i++) {
            Vector a = points.get(i);
            Vector b = points.get(i + 1);
            double length = a.distance(b);
            if (length == 0) continue;
            if (!canSee(viewer, world, a.getX(), a.getY(), a.getZ()) && !canSee(viewer, world, b.getX(), b.getY(), b.getZ())) {
                carry = 0;
                continue;
            }
            double along = spacing - carry;
            if (i == 0) {
                spawn(viewer, particle, data, a);
                along = spacing;
            }
            while (along <= length) {
                double t = along / length;
                spawn(viewer, particle, data, new Vector(a.getX() + (b.getX() - a.getX()) * t,
                        a.getY() + (b.getY() - a.getY()) * t, a.getZ() + (b.getZ() - a.getZ()) * t));
                along += spacing;
            }
            carry = length - (along - spacing);
        }
        Vector last = points.get(points.size() - 1);
        if (canSee(viewer, world, last.getX(), last.getY(), last.getZ())) {
            spawn(viewer, particle, data, last);
        }
    }

    /**
     * A vertical line of particles from {@code base} up {@code height} blocks, one every {@code spacing},
     * for one viewer - node markers of the road overlay.
     */
    public static void pillar(Player viewer, Vector base, double height, double spacing, Particle particle, Object data) {
        if (!canSee(viewer, viewer.getWorld(), base.getX(), base.getY(), base.getZ())) return;
        if (spacing <= 0 || height < 0) {
            spawn(viewer, particle, data, base);
            return;
        }
        for (double dy = 0; dy <= height + 1e-9; dy += spacing) {
            spawn(viewer, particle, data, new Vector(base.getX(), base.getY() + dy, base.getZ()));
        }
    }

    /** Whether the viewer is in {@code world} and within {@link #PARTICLE_RANGE} of the point. */
    public static boolean canSee(Player viewer, World world, double x, double y, double z) {
        if (!Objects.equals(viewer.getWorld(), world)) return false;
        Location at = viewer.getLocation();
        double dx = at.getX() - x;
        double dy = at.getY() - y;
        double dz = at.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= PARTICLE_RANGE * PARTICLE_RANGE;
    }

    private static void spawn(Player viewer, Particle particle, Object data, Vector at) {
        if (data == null) {
            viewer.spawnParticle(particle, at.getX(), at.getY(), at.getZ(), 1, 0, 0, 0, 0);
        } else {
            viewer.spawnParticle(particle, at.getX(), at.getY(), at.getZ(), 1, 0, 0, 0, 0, data);
        }
    }
}
