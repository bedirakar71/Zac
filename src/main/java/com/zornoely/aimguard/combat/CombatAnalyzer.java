package com.zornoely.aimguard.combat;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
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

    private static final long COMBAT_WINDOW_MS = 8000L;
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

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        profile.setLastCombatTimeMs(nowMs);

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
                box.getMinY() + (box.getHeight() * 0.85),
                boxCenter.getZ()
        );
        double aimErrorDeg = computeAimAngleErrorDeg(attacker.getEyeLocation(), upperAimPoint);

        // 1. Arkasi donuk veya hizli kafa cevirip (flick/snap) vurma kontrolu
        boolean blockBackHits = cfg.getBoolean("combat.block-back-hits", true);
        double maxAttackLookAngle = cfg.getDouble("combat.max-attack-look-angle-deg", 72.0);
        long snapWindowMs = cfg.getLong("combat.snap-window-ms", 400L);
        boolean recentFastTurn = (nowMs - profile.getLastFastTurnMs()) <= snapWindowMs;
        double effectiveMaxAngle = profile.isLagging() ? (maxAttackLookAngle + 15.0) : maxAttackLookAngle;

        if (blockBackHits && (aimErrorDeg > effectiveMaxAngle || (recentFastTurn && aimErrorDeg > 18.0))) {
            event.setCancelled(true);
            if (!profile.isLagging()) {
                profile.addHeuristicVl(cfg.getDouble("combat.vl-per-back-hit", 1.85), nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        attacker,
                        profile,
                        nowMs,
                        "FlickBackAura(angle=" + String.format("%.1f", aimErrorDeg)
                                + "deg,turn=" + String.format("%.1f", profile.getLastFastTurnDeg()) + "deg)"
                );
            }
            return;
        }

        // 2. Reach kontrolu
        double baseMaxReach = cfg.getDouble("combat.max-reach-blocks", 3.30);
        double lagExtra = cfg.getDouble("combat.lag-extra-tolerance", 0.45);
        boolean recentKb = (nowMs - profile.getLastKnockbackTimeMs()) < 1200L;
        double effectiveMaxReach = (profile.isLagging() || recentKb || attacker.isGliding()) ? (baseMaxReach + lagExtra) : baseMaxReach;

        if (hitboxSurfaceDist > effectiveMaxReach) {
            event.setCancelled(true);
            if (!profile.isLagging()) {
                profile.addMathVl(cfg.getDouble("combat.vl-per-reach-cancel", 1.15), nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        attacker,
                        profile,
                        nowMs,
                        "ReachHit(" + String.format("%.2f", hitboxSurfaceDist) + "b > " + String.format("%.2f", effectiveMaxReach) + "b)"
                );
            }
            return;
        }

        // 3. Duvar arkasi vurus (WallHit) kontrolu
        if (cfg.getBoolean("combat.block-wall-hits", true) && isBlockedByWall(attacker, box, hitboxSurfaceDist)) {
            event.setCancelled(true);
            if (!profile.isLagging()) {
                profile.addMathVl(cfg.getDouble("combat.vl-per-wall-hit", 1.60), nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        attacker,
                        profile,
                        nowMs,
                        "WallHit(dist=" + String.format("%.2f", hitboxSurfaceDist) + "b)"
                );
            }
            return;
        }

        // 4. Ag (Cobweb) icinden veya ag icindeki hedefe hileli vurus kontrolu
        boolean attackerInWeb = isInCobweb(attacker.getBoundingBox(), attacker.getWorld());
        boolean targetInWeb = isInCobweb(box, livingTarget.getWorld());
        if (cfg.getBoolean("combat.block-cobweb-hits", true) && (attackerInWeb || targetInWeb)) {
            if (hitboxSurfaceDist > 3.10 || profile.getMathVl() >= 2.5) {
                event.setCancelled(true);
                if (!profile.isLagging()) {
                    profile.addMathVl(cfg.getDouble("combat.vl-per-cobweb-hit", 1.25), nowMs);
                    plugin.getPunishmentManager().evaluateAndPunish(
                            attacker,
                            profile,
                            nowMs,
                            "CobwebHit(dist=" + String.format("%.2f", hitboxSurfaceDist) + "b)"
                    );
                }
                return;
            }
        }

        // 5. Sahte Criticals kontrolu (Elytra suzulme ve normal ziplamada false atmayacak sekilde)
        if (cfg.getBoolean("combat.block-fake-criticals", true) && !profile.isLagging() && !attacker.isGliding()) {
            boolean suspiciousCrit = attacker.isOnGround() && attacker.getFallDistance() <= 0.01f;
            if (suspiciousCrit) {
                int critStreak = profile.recordAndGetConsecutiveCrits(nowMs);
                int minStreak = cfg.getInt("combat.min-fake-crit-streak", 5);
                if (critStreak >= minStreak) {
                    event.setDamage(event.getDamage() * 0.72);
                    profile.addHeuristicVl(cfg.getDouble("combat.vl-per-fake-crit", 1.35), nowMs);
                    plugin.getPunishmentManager().evaluateAndPunish(
                            attacker,
                            profile,
                            nowMs,
                            "FakeCriticals(streak=" + critStreak + ")"
                    );
                }
            } else {
                profile.resetConsecutiveCrits();
            }
        }

        // 6. Gelismis AimAssist ve KillAura analizi
        boolean alignedStrafe = attacker.getVelocity().length() > 0.06;

        Deque<PlayerCheckProfile.HitSample> hits = profile.getHitSamples();
        hits.addLast(new PlayerCheckProfile.HitSample(nowMs, hitboxSurfaceDist, aimErrorDeg, alignedStrafe));
        while (!hits.isEmpty() && (nowMs - hits.peekFirst().timestampMs()) > COMBAT_WINDOW_MS) {
            hits.pollFirst();
        }

        if (!cfg.getBoolean("killaura-aimassist.enabled", true) || profile.isLagging()) {
            return;
        }

        int minHits = cfg.getInt("killaura-aimassist.min-hits-in-window", 4);
        double maxAimErr = cfg.getDouble("killaura-aimassist.max-aim-error-deg", 2.60);
        double scoreThreshold = cfg.getDouble("killaura-aimassist.score-threshold", 0.60);
        double vlPerFlag = cfg.getDouble("killaura-aimassist.vl-per-flag", 1.70);

        double aimAssistSignal = evaluateAimAssistSmoothPattern(profile.getRotationSamples());
        double cpsStdDev = computeClickIntervalStdDev(clicks);
        double avgAimError = hits.stream().mapToDouble(PlayerCheckProfile.HitSample::aimErrorDeg).average().orElse(15.0);
        double cps = clicks.size() / (COMBAT_WINDOW_MS / 1000.0);

        double score = 0.0;
        if (hits.size() >= minHits) {
            if (avgAimError <= maxAimErr) {
                score += 0.36;
            }
            if (avgAimError <= maxAimErr && alignedStrafe) {
                score += 0.14;
            }
            if (cps > 13.0 && cpsStdDev < 8.0) {
                score += 0.22;
            }
            score += aimAssistSignal * 0.44;
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
                            + ",smooth=" + String.format("%.2f", aimAssistSignal) + ")"
            );
        }
    }

    private boolean isBlockedByWall(Player attacker, BoundingBox targetBox, double dist) {
        if (dist < 0.8) {
            return false;
        }
        Location eyeLoc = attacker.getEyeLocation();
        Vector origin = eyeLoc.toVector();
        Vector targetPoint = targetBox.getCenter();
        Vector dir = targetPoint.clone().subtract(origin);
        double len = dir.length();
        if (len < 0.1) {
            return false;
        }
        dir.normalize();

        RayTraceResult blockHit = eyeLoc.getWorld().rayTraceBlocks(
                eyeLoc,
                dir,
                Math.min(len, 4.5),
                org.bukkit.FluidCollisionMode.NEVER,
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
        return blockDist + 0.15 < len;
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

    private double evaluateAimAssistSmoothPattern(Deque<PlayerCheckProfile.RotationSample> samples) {
        if (samples.size() < 16) {
            return 0.0;
        }

        int count = 0;
        int microAssistCount = 0;
        int snapCount = 0;
        int proportionalStepCount = 0;
        Float prevDeltaYaw = null;

        for (PlayerCheckProfile.RotationSample s : samples) {
            if (count >= ROTATION_WINDOW_SIZE) {
                break;
            }
            count++;
            if (s.snapped()) {
                snapCount++;
            }
            if (s.deltaYaw() > 0.20f && s.deltaYaw() < 4.5f) {
                microAssistCount++;
            }
            if (prevDeltaYaw != null && prevDeltaYaw > 0.35f && s.deltaYaw() > 0.15f) {
                float ratio = s.deltaYaw() / prevDeltaYaw;
                if (ratio > 0.32f && ratio < 0.72f) {
                    proportionalStepCount++;
                }
            }
            prevDeltaYaw = s.deltaYaw();
        }

        double microRatio = (double) microAssistCount / count;
        double signal = 0.0;

        if (microRatio > 0.65 && count >= 20) {
            signal += 0.35;
        }
        if (proportionalStepCount >= 6) {
            signal += 0.40;
        }
        if (snapCount >= 4) {
            signal += 0.25;
        }

        return Math.min(1.0, signal);
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
