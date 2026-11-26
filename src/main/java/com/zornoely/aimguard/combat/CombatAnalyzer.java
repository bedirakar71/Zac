package com.zornoely.aimguard.combat;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
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

        // 1. Son 8 saniyelik tiklama gecmisi
        Deque<Long> clicks = profile.getClickTimestamps();
        clicks.addLast(nowMs);
        while (!clicks.isEmpty() && (nowMs - clicks.peekFirst()) > COMBAT_WINDOW_MS) {
            clicks.pollFirst();
        }

        // 2. Backtrack / Rewind ile kurbanin gecmis konumu ve 3D Hitbox (BoundingBox) mesafesi
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
        double aimErrorDeg = computeAimAngleErrorDeg(attacker.getEyeLocation(), boxCenter);
        boolean alignedStrafe = attacker.getVelocity().length() > 0.08;

        // 3. Reach & Hitbox kontrolu: belirlenen blogu gecince vurusu iptal et
        boolean reachEnabled = cfg.getBoolean("reach.enabled", true);
        double maxReach = cfg.getDouble("reach.max-reach-blocks", 3.45);
        double lagExtra = cfg.getDouble("reach.lag-extra-tolerance", 0.55);
        double vlPerCancel = cfg.getDouble("reach.vl-per-cancel", 1.35);

        double effectiveMaxReach = profile.isLagging() ? (maxReach + lagExtra) : maxReach;
        if (reachEnabled && hitboxSurfaceDist > effectiveMaxReach) {
            event.setCancelled(true);
            if (!profile.isLagging()) {
                profile.addMathVl(vlPerCancel, nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        attacker,
                        profile,
                        nowMs,
                        "ReachHitbox(" + String.format("%.2f", hitboxSurfaceDist) + "b > " + String.format("%.2f", effectiveMaxReach) + "b)"
                );
            }
            return;
        }

        // 4. Combat penceresi kaydi
        Deque<PlayerCheckProfile.HitSample> hits = profile.getHitSamples();
        hits.addLast(new PlayerCheckProfile.HitSample(nowMs, hitboxSurfaceDist, aimErrorDeg, alignedStrafe));
        while (!hits.isEmpty() && (nowMs - hits.peekFirst().timestampMs()) > COMBAT_WINDOW_MS) {
            hits.pollFirst();
        }

        // 5. Gelismis KillAura analizi (sadece kafa cevirmeye degil, combat butunlugune bakar)
        if (!cfg.getBoolean("killaura.enabled", true) || profile.isLagging()) {
            return;
        }

        int minHits = cfg.getInt("killaura.min-hits-in-window", 6);
        double maxAimErr = cfg.getDouble("killaura.max-aim-error-deg", 2.2);
        double scoreThreshold = cfg.getDouble("killaura.score-threshold", 0.72);
        double vlPerFlag = cfg.getDouble("killaura.vl-per-flag", 1.5);
        boolean ignorePureRotation = cfg.getBoolean("killaura.ignore-pure-rotation", true);

        double rotationSignal = evaluateRotationWindow(profile.getRotationSamples(), ignorePureRotation);
        double cpsStdDev = computeClickIntervalStdDev(clicks);
        double avgAimError = hits.stream().mapToDouble(PlayerCheckProfile.HitSample::aimErrorDeg).average().orElse(15.0);
        double cps = clicks.size() / (COMBAT_WINDOW_MS / 1000.0);

        double score = 0.0;
        if (hits.size() >= minHits) {
            // Hedefe asiri tutarli kilitlenme + hareket/strafe uyumu
            if (avgAimError <= maxAimErr && alignedStrafe) {
                score += 0.35;
            }
            // Cok duzgun tiklama araligi + yuksek CPS
            if (cps > 15.5 && cpsStdDev < 6.0) {
                score += 0.25;
            }
            // Sinira yakin agresif hitbox mesafesi
            if (hitboxSurfaceDist > 3.15) {
                score += 0.15;
            }
            // Rotation sinyali (sadece combat sirasinda anlamli skor uretir)
            score += rotationSignal * 0.35;
        }

        profile.setCombatCheatScore(score);

        if (score >= scoreThreshold) {
            profile.addHeuristicVl(vlPerFlag, nowMs);
            plugin.getPunishmentManager().evaluateAndPunish(
                    attacker,
                    profile,
                    nowMs,
                    "KillAura(score=" + String.format("%.2f", score)
                            + ",aim=" + String.format("%.1f", avgAimError)
                            + ",reach=" + String.format("%.2f", hitboxSurfaceDist) + "b)"
            );
        }
    }

    private double computeBoxSurfaceDistance(Vector point, BoundingBox box) {
        double clampedX = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double clampedY = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double clampedZ = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));
        return point.distance(new Vector(clampedX, clampedY, clampedZ));
    }

    private double evaluateRotationWindow(Deque<PlayerCheckProfile.RotationSample> samples, boolean ignorePureRotation) {
        if (samples.size() < 24) {
            return 0.0;
        }

        int count = 0;
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
            if (prevDeltaYaw != null && s.deltaYaw() > 1.5f && Math.abs(s.deltaYaw() - prevDeltaYaw) < 0.008f) {
                constantStepCount++;
            }
            prevDeltaYaw = s.deltaYaw();
        }

        // Iyi oyuncularin duzgun fare cevirmesine tek basina yuksek puan vermemek icin agirlik dusuk tutulur
        double signal = 0.0;
        if (constantStepCount >= 8) {
            signal += ignorePureRotation ? 0.25 : 0.45;
        }
        if (snapCount >= 6) {
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
