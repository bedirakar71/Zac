package com.zornoely.aimguard.combat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Vurus dogrulamasi icin saf geometri yardimcilari (menzil, bakis acisi, gorus hatti). */
public final class HitGeometry {

    private HitGeometry() {}

    public static Vector direction(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double xz = Math.cos(pitch);
        return new Vector(-xz * Math.sin(yaw), -Math.sin(pitch), xz * Math.cos(yaw));
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Bir noktanin kutuya olan en kisa uzakligi. */
    public static double distanceToBox(Vector p, BoundingBox b) {
        double dx = p.getX() - clamp(p.getX(), b.getMinX(), b.getMaxX());
        double dy = p.getY() - clamp(p.getY(), b.getMinY(), b.getMaxY());
        double dz = p.getZ() - clamp(p.getZ(), b.getMinZ(), b.getMaxZ());
        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    /** Isin (origin + t*dir) kutuyu kesiyor mu? (slab yontemi) */
    public static boolean rayHitsBox(Vector o, Vector d, BoundingBox box, double maxDist) {
        double tMin = 0.0;
        double tMax = maxDist;
        double[] origin = {o.getX(), o.getY(), o.getZ()};
        double[] dir = {d.getX(), d.getY(), d.getZ()};
        double[] min = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] max = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1.0E-9) {
                if (origin[i] < min[i] || origin[i] > max[i]) {
                    return false;
                }
            } else {
                double inv = 1.0 / dir[i];
                double t1 = (min[i] - origin[i]) * inv;
                double t2 = (max[i] - origin[i]) * inv;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Bakis yonu ile kutu arasindaki en kucuk aci (derece). Kursor kutunun uzerindeyse 0 doner.
     * dir birim vektor olmalidir.
     */
    public static double angleToBoxDeg(Vector eye, Vector dir, BoundingBox box) {
        if (box.contains(eye) || rayHitsBox(eye, dir, box, 16.0)) {
            return 0.0;
        }
        Vector center = box.getCenter();
        double tc = center.clone().subtract(eye).dot(dir);
        double[] ts = {tc, tc - 0.5, tc + 0.5, tc * 0.5, tc * 1.5};
        double best = 180.0;
        for (double t : ts) {
            double tt = Math.max(0.05, t);
            Vector onRay = eye.clone().add(dir.clone().multiply(tt));
            Vector closest = new Vector(
                    clamp(onRay.getX(), box.getMinX(), box.getMaxX()),
                    clamp(onRay.getY(), box.getMinY(), box.getMaxY()),
                    clamp(onRay.getZ(), box.getMinZ(), box.getMaxZ()));
            Vector to = closest.subtract(eye);
            double len = to.length();
            if (len < 1.0E-6) {
                return 0.0;
            }
            double cos = clamp(dir.dot(to) / len, -1.0, 1.0);
            best = Math.min(best, Math.toDegrees(Math.acos(cos)));
        }
        return best;
    }

    public static double angleToPointDeg(Vector eye, Vector dir, Vector point) {
        Vector to = point.clone().subtract(eye);
        double len = to.length();
        if (len < 1.0E-6) {
            return 0.0;
        }
        double cos = clamp(dir.dot(to) / len, -1.0, 1.0);
        return Math.toDegrees(Math.acos(cos));
    }

    /** Hedef kutusunun belli bir uzaklikta kapladigi yari gorus acisi (derece). */
    public static double halfAngularWidthDeg(double boxWidth, double distance) {
        return Math.toDegrees(Math.atan2(boxWidth * 0.5, Math.max(0.3, distance)));
    }

    /**
     * Gozden hedef kutusunun HERHANGI bir noktasina engelsiz bir gorus hatti var mi?
     * Merkez, bas, ayak, yuz merkezleri ve koseler denenir; boylece koselerden/sutun kenarlarindan
     * yapilan mesru vuruslar duvar sayilmaz.
     */
    public static boolean hasLineOfSight(World world, Vector eye, BoundingBox box, double inflate) {
        double minX = box.getMinX() - inflate;
        double maxX = box.getMaxX() + inflate;
        double minZ = box.getMinZ() - inflate;
        double maxZ = box.getMaxZ() + inflate;
        double minY = box.getMinY() + 0.1;
        double maxY = box.getMaxY() - 0.1;
        double cx = (minX + maxX) * 0.5;
        double cy = (minY + maxY) * 0.5;
        double cz = (minZ + maxZ) * 0.5;

        if (clearRay(world, eye, new Vector(cx, cy, cz))) {
            return true;
        }
        if (clearRay(world, eye, new Vector(cx, maxY, cz)) || clearRay(world, eye, new Vector(cx, minY, cz))) {
            return true;
        }
        if (clearRay(world, eye, new Vector(minX, cy, cz)) || clearRay(world, eye, new Vector(maxX, cy, cz))
                || clearRay(world, eye, new Vector(cx, cy, minZ)) || clearRay(world, eye, new Vector(cx, cy, maxZ))) {
            return true;
        }
        double[] ys = {maxY, cy, minY};
        double[] xs = {minX, maxX};
        double[] zs = {minZ, maxZ};
        for (double y : ys) {
            for (double x : xs) {
                for (double z : zs) {
                    if (clearRay(world, eye, new Vector(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Gozden noktaya giden dogru hicbir katı blokla (collision) kesilmiyorsa true. */
    public static boolean clearRay(World world, Vector eye, Vector point) {
        Vector dir = point.clone().subtract(eye);
        double len = dir.length();
        if (len < 0.05) {
            return true;
        }
        dir.normalize();
        Location start = new Location(world, eye.getX(), eye.getY(), eye.getZ());
        RayTraceResult r = world.rayTraceBlocks(start, dir, len, FluidCollisionMode.NEVER, true);
        if (r == null || r.getHitBlock() == null) {
            return true;
        }
        double d = r.getHitPosition().distance(eye);
        if (d < 0.03) {
            return true; // goz zaten bir blok sinirinin icinde: engel sayma
        }
        return d >= (len - 0.30);
    }
}
