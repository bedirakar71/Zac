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

        // Tiklama gecmisi ve CPS/interval hesabi
        Deque<Long> clicks = profile.getClickTimestamps();
        clicks.addLast(nowMs);
        while (!clicks.isEmpty() && (nowMs - clicks.peekFirst()) > COMBAT_WINDOW_MS) {
            clicks.pollFirst();
        }

        // Geri sarilmis konum + 3D Hitbox sinir mesafesi
        Location rewoundLoc = plugin.getLagManager().getRewoundLocation(target.getUniqueId(), attacker.getPing(), nowMs);
        Location compareLoc = (rewoundLoc != null) ? rewoundLoc : livingTarget.getLocation();

        BoundingBox box = livingTarget.getBoundingBox().shift(
                compareLoc.getX() - livingTarget.getLocation().getX(),
                compareLoc.getY() - livingTarget.getLocation().getY(),
                compareLoc.getZ() - livingTarget.getLocation().getZ()
        );

        Vector eyePos = attacker.getEyeLocation().toVector();
        double hitboxSurfaceDist = computeBoxSurfaceDistance(eyePos, box);

        // 1. Reach kontrolu: guvenli siniri gecen vurusu iptal et
        double baseMaxReach = cfg.getDouble("combat.max-reach-blocks", 3.25);
        double lagExtra = cfg.getDouble("combat.lag-extra-tolerance", 0.40);
        boolean recentKb = (nowMs - profile.getLastKnockbackTimeMs()) < 900L;
        double effectiveMaxReach = (profile.isLagging() || recentKb) ? (baseMaxReach + lagExtra) : baseMaxReach;

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

        // 2. Duvar / kati blok arkasindan vurma (WallHit) kontrolu
        if (cfg.getBoolean("combat.block-wall-hits", true) && isBlockedByWall(attacker, compareLoc, box, hitboxSurfaceDist)) {
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

        // 3. Ag (Cobweb) icinden veya ag icindeki hedefe vurus kontrolu
        boolean attackerInWeb = isInCobweb(attacker.getBoundingBox(), attacker.getWorld());
        boolean targetInWeb = isInCobweb(box, livingTarget.getWorld());
        if (cfg.getBoolean("combat.block-cobweb-hits", true) && (attackerInWeb || targetInWeb)) {
            // Ag icindeki oyuncu cok hizli / normal hareket sinirinin ustunde vuruyorsa iptal et
            if (hitboxSurfaceDist > 3.05 || profile.getMathVl() >= 2.0) {
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

        // 4. Gelismis AimAssist ve AttackAura analizi
        Vector boxCenter = box.getCenter();
        double aimErrorDeg = computeAimAngleErrorDeg(attacker.getEyeLocation(), boxCenter);
        boolean alignedStrafe = attacker.getVelocity().length() > 0.07;

        Deque<PlayerCheckProfile.HitSample> hits = profile.getHitSamples();
        hits.addLast(new PlayerCheckProfile.HitSample(nowMs, hitboxSurfaceDist, aimErrorDeg, alignedStrafe));
        while (!hits.isEmpty() && (nowMs - hits.peekFirst().timestampMs()) > COMBAT_WINDOW_MS) {
            hits.pollFirst();
        }

        if (!cfg.getBoolean("killaura-aimassist.enabled", true) || profile.isLagging()) {
            return;
        }

        int minHits = cfg.getInt("killaura-aimassist.min-hits-in-window", 5);
        double maxAimErr = cfg.getDouble("killaura-aimassist.max-aim-error-deg", 2.35);
        double scoreThreshold = cfg.getDouble("killaura-aimassist.score-threshold", 0.68);
        double vlPerFlag = cfg.getDouble("killaura-aimassist.vl-per-flag", 1.50);

        double aimAssistSignal = evaluateRotationAndAimWindow(profile.getRotationSamples());
        double cpsStdDev = computeClickIntervalStdDev(clicks);
        double avgAimError = hits.stream().mapToDouble(PlayerCheckProfile.HitSample::aimErrorDeg).average().orElse(15.0);
        double cps = clicks.size() / (COMBAT_WINDOW_MS / 1000.0);

        double score = 0.0;
        if (hits.size() >= minHits) {
            // Hedefe dar aci tutarliligi + hareket/strafe uyumu (AimAssist / AttackAura)
            if (avgAimError <= maxAimErr && alignedStrafe) {
                score += 0.34;
            }
            // Yuksek CPS ve duzensiz olmayan/mekanik tiklama araligi
            if (cps > 14.5 && cpsStdDev < 6.5) {
                score += 0.22;
            }
            // Sinira yakin vurus mesafesi
            if (hitboxSurfaceDist > 3.02) {
                score += 0.14;
            }
            // Pencere icindeki mikro-assist ve sert kilitlenme sinyali
            score += aimAssistSignal * 0.38;
        }

        profile.setCombatCheatScore(score);

        if (score >= scoreThreshold) {
            profile.addHeuristicVl(vlPerFlag, nowMs);
            plugin.getPunishmentManager().evaluateAndPunish(
                    attacker,
                    profile,
                    nowMs,
                    "AttackAura/AimAssist(score=" + String.format("%.2f", score)
                            + ",aim=" + String.format("%.1f", avgAimError)
                            + ",cps=" + String.format("%.1f", cps) + ")"
            );
        }
    }

    private boolean isBlockedByWall(Player attacker, Location targetLoc, BoundingBox targetBox, double dist) {
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

    private double evaluateRotationAndAimWindow(Deque<PlayerCheckProfile.RotationSample> samples) {
        if (samples.size() < 20) {
            return 0.0;
        }

        int count = 0;
        int microAssistCount = 0;
        int snapCount = 0;
        Float prevDeltaYaw = null;
        int constantStepCount = 0;

        for (PlayerCheckProfile.RotationSample s : samples) {
            if (count >= ROTATION_WINDOW_SIZE) {
                break;
            }
            count++;
            if (s.snapped()) {
                snapCount++;
            }
            if (s.deltaYaw() > 0.25f && s.deltaYaw() < 3.4f) {
                microAssistCount++;
            }
            if (prevDeltaYaw != null && s.deltaYaw() > 1.2f && Math.abs(s.deltaYaw() - prevDeltaYaw) < 0.01f) {
                constantStepCount++;
            }
            prevDeltaYaw = s.deltaYaw();
        }

        double microRatio = (double) microAssistCount / count;
        double signal = 0.0;
        if (microRatio > 0.68 && count >= 28) {
            signal += 0.35;
        }
        if (constantStepCount >= 7) {
            signal += 0.35;
        }
        if (snapCount >= 5) {
            signal += 0.25;
        }
        return Math.min(1.0, signal);
    }

    private double computeAimAngleErrorDeg(Location eyeLoc, Vector targetCenter) {
        Vector toTarget = targetCenter.clone().subtract(eyeLoc.toVector());
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
