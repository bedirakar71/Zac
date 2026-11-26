package com.zornoely.aimguard.combat;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Deque;

public final class CombatAnalyzer {

    private static final long COMBAT_WINDOW_MS = 8000L;
    private static final int ROTATION_WINDOW_SIZE = 64;

    private final ZornoAimGuardPlugin plugin;

    public CombatAnalyzer(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void processHit(Player attacker, Entity target, PlayerCheckProfile profile, long nowMs) {
        if (!(target instanceof LivingEntity livingTarget)) {
            return;
        }

        profile.setLastCombatTimeMs(nowMs);
        if (livingTarget instanceof Player) {
            profile.getOrCreateIfNeeded();
        }

        Deque<Long> clicks = profile.getClickTimestamps();
        clicks.addLast(nowMs);
        while (!clicks.isEmpty() && (nowMs - clicks.peekFirst()) > COMBAT_WINDOW_MS) {
            clicks.pollFirst();
        }

        Location rewoundLoc = plugin.getLagManager().getRewoundLocation(target.getUniqueId(), attacker.getPing(), nowMs);
        Location compareLoc = (rewoundLoc != null) ? rewoundLoc : livingTarget.getLocation();

        Vector targetCenter = compareLoc.toVector().add(0, livingTarget.getHeight() * 0.5, 0);
        double distance = attacker.getEyeLocation().toVector().distance(targetCenter);
        double aimErrorDeg = computeAimAngleErrorDeg(attacker.getEyeLocation(), targetCenter);
        boolean alignedStrafe = attacker.getVelocity().length() > 0.08;

        Deque<PlayerCheckProfile.HitSample> hits = profile.getHitSamples();
        hits.addLast(new PlayerCheckProfile.HitSample(nowMs, distance, aimErrorDeg, alignedStrafe));
        while (!hits.isEmpty() && (nowMs - hits.peekFirst().timestampMs()) > COMBAT_WINDOW_MS) {
            hits.pollFirst();
        }

        double rotationCheatSignal = evaluateRotationWindow(profile.getRotationSamples());
        double cpsStdDev = computeClickIntervalStdDev(clicks);
        double avgAimError = hits.stream().mapToDouble(PlayerCheckProfile.HitSample::aimErrorDeg).average().orElse(15.0);
        double cps = clicks.size() / (COMBAT_WINDOW_MS / 1000.0);

        // False-positive onlemi: yeterli vurus ve pencere verisi olmadan yuksek skor uretilmez
        double score = 0.0;
        if (hits.size() >= 5) {
            if (cps > 16.0 && cpsStdDev < 5.5) {
                score += 0.20;
            }
            if (avgAimError < 2.4 && alignedStrafe) {
                score += 0.25;
            }
            if (distance > 3.85 && !profile.isLagging()) {
                score += 0.20;
            }
            score += rotationCheatSignal * 0.40;
        }

        profile.setCombatCheatScore(score);

        if (score >= 0.62 && !profile.isLagging()) {
            profile.addHeuristicVl(score * 1.35, nowMs);
            plugin.getPunishmentEngine().evaluateCrossCheck(
                    attacker,
                    profile,
                    nowMs,
                    "AimWindow(score=" + String.format("%.2f", score)
                            + ",rot=" + String.format("%.2f", rotationCheatSignal)
                            + ",aimErr=" + String.format("%.1f", avgAimError) + ")"
            );
        }
    }

    private double evaluateRotationWindow(Deque<PlayerCheckProfile.RotationSample> samples) {
        if (samples.size() < 20) {
            return 0.0;
        }

        int count = 0;
        int microAssistCount = 0;
        int snapCount = 0;
        double gcdSum = 0.0;
        Float prevDeltaYaw = null;
        int constantStepCount = 0;

        for (PlayerCheckProfile.RotationSample s : samples) {
            if (count >= ROTATION_WINDOW_SIZE) {
                break;
            }
            count++;
            gcdSum += s.gcdYaw();

            if (s.snapped()) {
                snapCount++;
            }
            if (s.deltaYaw() > 0.25f && s.deltaYaw() < 3.2f) {
                microAssistCount++;
            }
            if (prevDeltaYaw != null && s.deltaYaw() > 1.2f && Math.abs(s.deltaYaw() - prevDeltaYaw) < 0.01f) {
                constantStepCount++;
            }
            prevDeltaYaw = s.deltaYaw();
        }

        double avgGcd = gcdSum / count;
        double microRatio = (double) microAssistCount / count;

        double signal = 0.0;
        if (microRatio > 0.65 && count >= 32) {
            signal += 0.35;
        }
        if (constantStepCount >= 6) {
            signal += 0.40;
        }
        if (snapCount >= 5) {
            signal += 0.20;
        }
        if (avgGcd < 1e-6 && count >= 32) {
            signal += 0.15;
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
