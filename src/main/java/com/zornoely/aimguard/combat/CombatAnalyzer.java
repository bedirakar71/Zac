package com.zornoely.aimguard.combat;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Deque;

public final class CombatAnalyzer {

    private static final long COMBAT_WINDOW_MS = 7000L;
    private static final int ROTATION_WINDOW_SIZE = 64;

    private final ZornoAimGuardPlugin plugin;

    public CombatAnalyzer(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void processAttackEvent(Player attacker, Entity target, PlayerCheckProfile profile, long nowMs, EntityDamageByEntityEvent event) {
        if (!(target instanceof LivingEntity livingTarget)) {
            return;
        }
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        profile.resetConsecutiveCrits();

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        profile.setLastCombatTimeMs(nowMs);
        boolean focused = plugin.isPlayerFocused(attacker.getUniqueId(), nowMs);

        Deque<Long> clicks = profile.getClickTimestamps();
        clicks.addLast(nowMs);
        while (!clicks.isEmpty() && (nowMs - clicks.peekFirst()) > COMBAT_WINDOW_MS) {
            clicks.pollFirst();
        }

        Location rewoundLoc = plugin.getLagManager().getRewoundLocation(target.getUniqueId(), attacker.getPing(), nowMs);
        Location compareLoc = (rewoundLoc != null) ? rewoundLoc : livingTarget.getLocation();

        BoundingBox box = livingTarget.getBoundingBox().shift(
                compareLoc.getX() - livingTarget.getLocation().getX(),
                compareLoc.getY() - livingTarget.getLocation().getY(),
                compareLoc.getZ() - livingTarget.getLocation().getZ()
        );

        Vector eyePos = attacker.getEyeLocation().toVector();
        double hitboxSurfaceDist = computeBoxSurfaceDistance(eyePos, box);

        Vector boxCenter = box.getCenter();
        Vector upperAimPoint = new Vector(
                boxCenter.getX(),
                box.getMinY() + (box.getHeight() * 0.80),
                boxCenter.getZ()
        );
        double aimErrorDeg = computeAimAngleErrorDeg(attacker.getEyeLocation(), upperAimPoint);

        boolean blockBackHits = cfg.getBoolean("combat.block-back-hits", true);
        double maxAttackLookAngle = cfg.getDouble("combat.max-attack-look-angle-deg", 94.0);
        long snapWindowMs = cfg.getLong("combat.snap-window-ms", 280L);
        double snapTurnMin = cfg.getDouble("combat.snap-turn-min-deg", 52.0);
        boolean recentFastTurn = (nowMs - profile.getLastFastTurnMs()) <= snapWindowMs
                && profile.getLastFastTurnDeg() >= snapTurnMin;
        double effectiveMaxAngle = profile.isLagging() ? (maxAttackLookAngle + 25.0) : maxAttackLookAngle;

        boolean isBackHit = aimErrorDeg > effectiveMaxAngle;
        boolean isSnapAuraHit = recentFastTurn && (aimErrorDeg <= 8.0 || aimErrorDeg > 42.0);

        if (blockBackHits && (isBackHit || isSnapAuraHit) && !profile.isLagging()) {
            event.setCancelled(true);
            profile.addHeuristicVl(cfg.getDouble("combat.vl-per-back-hit", 1.50), nowMs);
            plugin.getPunishmentManager().evaluateAndPunish(
                    attacker,
                    profile,
                    nowMs,
                    "FlickBackAura(angle=" + String.format("%.1f", aimErrorDeg)
                            + "deg,turn=" + String.format("%.1f", profile.getLastFastTurnDeg()) + "deg)"
            );
            return;
        }

        double baseMaxReach = cfg.getDouble("combat.max-reach-blocks", 3.15);
        double lagExtra = cfg.getDouble("combat.lag-extra-tolerance", 0.35);
        boolean recentKb = (nowMs - profile.getLastKnockbackTimeMs()) < 1200L;
        double effectiveMaxReach = (profile.isLagging() || recentKb || attacker.isGliding() || attacker.getPing() > 140)
                ? (baseMaxReach + lagExtra)
                : baseMaxReach;

        if (hitboxSurfaceDist > effectiveMaxReach) {
            event.setCancelled(true);
            int reachStreak = profile.recordAndGetConsecutiveReach(nowMs);
            int minReachStreakForPunish = cfg.getInt("combat.min-consecutive-reach-for-ban", 7);

            if (!profile.isLagging()) {
                double vlPerReach = cfg.getDouble("combat.vl-per-reach-cancel", 0.60);
                double vlToAdd = (reachStreak >= minReachStreakForPunish) ? (vlPerReach * 2.2) : (vlPerReach * 0.45);
                profile.addMathVl(vlToAdd, nowMs);

                if (reachStreak >= minReachStreakForPunish) {
                    plugin.getPunishmentManager().evaluateAndPunish(
                            attacker,
                            profile,
                            nowMs,
                            "ReachHit(" + String.format("%.2f", hitboxSurfaceDist)
                                    + "b > " + String.format("%.2f", effectiveMaxReach)
                                    + "b,streak=" + reachStreak + ")"
                    );
                }
            }
            return;
        } else {
            profile.resetConsecutiveReach();
        }

        if (cfg.getBoolean("combat.block-wall-hits", true) && isBlockedByWall(attacker, box, hitboxSurfaceDist)) {
            event.setCancelled(true);
            if (!profile.isLagging()) {
                profile.addMathVl(cfg.getDouble("combat.vl-per-wall-hit", 2.05), nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        attacker,
                        profile,
                        nowMs,
                        "WallHit(dist=" + String.format("%.2f", hitboxSurfaceDist) + "b)"
                );
            }
            return;
        }

        if (cfg.getBoolean("combat.block-cobweb-hits", false)) {
            boolean attackerInWeb = isInCobweb(attacker.getBoundingBox(), attacker.getWorld());
            boolean targetInWeb = isInCobweb(box, livingTarget.getWorld());
            if ((attackerInWeb || targetInWeb) && hitboxSurfaceDist > 3.15 && !profile.isLagging()) {
                event.setCancelled(true);
                profile.addMathVl(cfg.getDouble("combat.vl-per-cobweb-hit", 0.50), nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        attacker,
                        profile,
                        nowMs,
                        "CobwebHit(dist=" + String.format("%.2f", hitboxSurfaceDist) + "b)"
                );
                return;
            }
        }

        boolean alignedStrafe = attacker.getVelocity().length() > 0.05;

        Deque<PlayerCheckProfile.HitSample> hits = profile.getHitSamples();
        hits.addLast(new PlayerCheckProfile.HitSample(nowMs, hitboxSurfaceDist, aimErrorDeg, alignedStrafe));
        while (!hits.isEmpty() && (nowMs - hits.peekFirst().timestampMs()) > COMBAT_WINDOW_MS) {
            hits.pollFirst();
        }

        if (!cfg.getBoolean("killaura-aimassist.enabled", true) || profile.isLagging() || attacker.getPing() > 170) {
            return;
        }

        int minHits = cfg.getInt("killaura-aimassist.min-hits-in-window", 4);
        double maxAimErr = cfg.getDouble("killaura-aimassist.max-aim-error-deg", 3.30);
        double scoreThreshold = cfg.getDouble("killaura-aimassist.score-threshold", 0.43);
        double vlPerFlag = cfg.getDouble("killaura-aimassist.vl-per-flag", 2.35);

        double aimConsistency = computeAimLockConsistency(hits, maxAimErr);
        double rotationPattern = evaluateRotationCheatPattern(profile.getRotationSamples(), recentFastTurn);
        double cpsStdDev = computeClickIntervalStdDev(clicks);
        double avgAimError = hits.stream().mapToDouble(PlayerCheckProfile.HitSample::aimErrorDeg).average().orElse(15.0);
        double cps = clicks.size() / (COMBAT_WINDOW_MS / 1000.0);

        long recentBurstHits = hits.stream().filter(h -> (nowMs - h.timestampMs()) <= 2000L).count();

        double score = 0.0;
        if (hits.size() >= minHits) {
            if (avgAimError <= maxAimErr && aimConsistency >= 0.50) {
                score += 0.30;
            }
            if (recentBurstHits >= 3 && avgAimError <= (maxAimErr * 0.85) && aimConsistency >= 0.45) {
                score += 0.20;
            }
            if (cps > 9.5 && cpsStdDev < 11.0) {
                score += 0.14;
            }
            score += rotationPattern * 0.38;
        }

        profile.setCombatCheatScore(score);

        if (score >= scoreThreshold) {
            profile.addHeuristicVl(vlPerFlag, nowMs);
            plugin.getPunishmentManager().evaluateAndPunish(
                    attacker,
                    profile,
                    nowMs,
                    "AimAssist/KillAura(score=" + String.format("%.2f", score)
                            + ",aim=" + String.format("%.1f", avgAimError)
                            + ",burst=" + recentBurstHits + ")"
            );
        } else if (focused && hits.size() >= 2) {
            plugin.notifyFocusTelemetry(
                    attacker,
                    profile,
                    "Hit(dist=" + String.format("%.2f", hitboxSurfaceDist)
                            + "b,aim=" + String.format("%.1f", aimErrorDeg)
                            + "deg,score=" + String.format("%.2f", score) + ")"
            );
        }
    }

    private double computeAimLockConsistency(Deque<PlayerCheckProfile.HitSample> hits, double maxAimErr) {
        if (hits.size() < 4) {
            return 0.0;
        }
        int tightHits = 0;
        double sumErr = 0.0;
        for (PlayerCheckProfile.HitSample h : hits) {
            sumErr += h.aimErrorDeg();
            if (h.aimErrorDeg() <= maxAimErr) {
                tightHits++;
            }
        }
        double mean = sumErr / hits.size();
        double sqDiff = 0.0;
        for (PlayerCheckProfile.HitSample h : hits) {
            sqDiff += Math.pow(h.aimErrorDeg() - mean, 2);
        }
        double stdDev = Math.sqrt(sqDiff / hits.size());
        double tightRatio = (double) tightHits / hits.size();

        double stabilityScore = Math.max(0.0, 1.0 - (stdDev / 4.2));
        return Math.min(1.0, (tightRatio * 0.55) + (stabilityScore * 0.45));
    }

    private double evaluateRotationCheatPattern(Deque<PlayerCheckProfile.RotationSample> samples, boolean recentFastTurn) {
        if (samples.size() < 14) {
            return recentFastTurn ? 0.22 : 0.0;
        }

        int count = 0;
        int microAssistCount = 0;
        int proportionalStepCount = 0;
        int lowGcdCount = 0;
        Float prevDeltaYaw = null;

        for (PlayerCheckProfile.RotationSample s : samples) {
            if (count >= ROTATION_WINDOW_SIZE) {
                break;
            }
            count++;
            if (s.deltaYaw() > 0.15f && s.deltaYaw() < 4.8f) {
                microAssistCount++;
            }
            if (s.gcdYaw() > 0.0 && s.gcdYaw() < 0.025) {
                lowGcdCount++;
            }
            if (prevDeltaYaw != null && prevDeltaYaw > 0.25f && s.deltaYaw() > 0.12f) {
                float ratio = s.deltaYaw() / prevDeltaYaw;
                if (ratio > 0.30f && ratio < 0.75f) {
                    proportionalStepCount++;
                }
            }
            prevDeltaYaw = s.deltaYaw();
        }

        double microRatio = (double) microAssistCount / count;
        double lowGcdRatio = (double) lowGcdCount / count;
        double signal = 0.0;

        if (microRatio > 0.64 && count >= 18) {
            signal += 0.28;
        }
        if (proportionalStepCount >= 5) {
            signal += 0.38;
        }
        if (lowGcdRatio > 0.55 && count >= 16) {
            signal += 0.16;
        }
        if (recentFastTurn) {
            signal += 0.20;
        }
        return Math.min(1.0, signal);
    }

    private boolean isBlockedByWall(Player attacker, BoundingBox targetBox, double dist) {
        if (dist < 0.85) {
            return false;
        }
        Location eyeLoc = attacker.getEyeLocation();
        World world = eyeLoc.getWorld();
        if (world == null) {
            return false;
        }

        double cx = targetBox.getCenterX();
        double cz = targetBox.getCenterZ();
        double minY = targetBox.getMinY();
        double h = targetBox.getHeight();

        // Ust blok kapali olsa bile alt/orta bosluktan vurulabilir oldugu icin coklu gorus hatti kontrol edilir
        Vector[] targetPoints = new Vector[]{
                new Vector(cx, minY + (h * 0.82), cz),
                new Vector(cx, minY + (h * 0.50), cz),
                new Vector(cx, minY + (h * 0.20), cz)
        };

        for (Vector pt : targetPoints) {
            if (!isPointObstructed(world, eyeLoc, pt)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPointObstructed(World world, Location eyeLoc, Vector targetPoint) {
        Vector origin = eyeLoc.toVector();
        Vector dir = targetPoint.clone().subtract(origin);
        double len = dir.length();
        if (len < 0.1) {
            return false;
        }
        dir.normalize();

        RayTraceResult blockHit = world.rayTraceBlocks(
                eyeLoc,
                dir,
                Math.min(len, 4.5),
                FluidCollisionMode.NEVER,
                true
        );
        if (blockHit == null || blockHit.getHitBlock() == null) {
            return false;
        }
        Block hitBlock = blockHit.getHitBlock();
        if (!hitBlock.getType().isOccluding() || !hitBlock.getType().isSolid()) {
            return false;
        }
        double blockDist = blockHit.getHitPosition().distance(origin);
        return blockDist + 0.22 < len;
    }

    private boolean isInCobweb(BoundingBox box, World world) {
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type == Material.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private double computeBoxSurfaceDistance(Vector point, BoundingBox box) {
        double clampedX = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double clampedY = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double clampedZ = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));
        return point.distance(new Vector(clampedX, clampedY, clampedZ));
    }

    private double computeAimAngleErrorDeg(Location eyeLoc, Vector targetPoint) {
        Vector toTarget = targetPoint.clone().subtract(eyeLoc.toVector());
        if (toTarget.lengthSquared() < 1e-6) {
            return 0.0;
        }
        toTarget.normalize();
        Vector lookDir = eyeLoc.getDirection().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, lookDir.dot(toTarget)));
        return Math.toDegrees(Math.acos(dot));
    }

    private double computeClickIntervalStdDev(Deque<Long> clicks) {
        if (clicks.size() < 4) {
            return 99.0;
        }
        Long[] arr = clicks.toArray(new Long[0]);
        int n = arr.length - 1;
        double[] intervals = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            intervals[i] = arr[i + 1] - arr[i];
            sum += intervals[i];
        }
        double mean = sum / n;
        double sqSum = 0.0;
        for (double v : intervals) {
            sqSum += Math.pow(v - mean, 2);
        }
        return Math.sqrt(sqSum / n);
    }
}
