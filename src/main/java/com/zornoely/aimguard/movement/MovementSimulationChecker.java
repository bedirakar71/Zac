package com.zornoely.aimguard.movement;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MovementSimulationChecker {

    private record HorizWindow(long windowStartMs, int count) {}

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, HorizWindow> horizWindows = new ConcurrentHashMap<>();

    public MovementSimulationChecker(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkMovement(Player player, PlayerCheckProfile profile, Location from, Location to, long nowMs) {
        if (from == null || to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (player.isInsideVehicle() || player.isFlying()) {
            return;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();

        float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
        if (deltaYaw > 180.0f) {
            deltaYaw = 360.0f - deltaYaw;
        }
        float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
        double gcdYaw = computeGcd(deltaYaw);
        double gcdPitch = computeGcd(deltaPitch);

        double snapThreshold = cfg.getDouble("combat.snap-turn-min-deg", 56.0);
        boolean snapped = deltaYaw >= snapThreshold;
        if (snapped) {
            profile.recordFastTurn(deltaYaw, nowMs);
        }

        appendRotationSample(profile, nowMs, deltaYaw, deltaPitch, gcdYaw, gcdPitch, snapped);

        boolean elytraSafe = cfg.getBoolean("environment.elytra-safe-mode", true);
        boolean recentKbOrImpact = (nowMs - profile.getLastKnockbackTimeMs()) < 1800L;
        if (elytraSafe && (player.isGliding() || recentKbOrImpact || player.getPing() > 140)) {
            profile.clearMitigation();
            profile.setHasSimulatedVy(false);
            horizWindows.remove(player.getUniqueId());
            return;
        }

        if (player.isOnGround() || to.getY() <= from.getY() - 0.55) {
            profile.setHasSimulatedVy(false);
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizDist = Math.hypot(dx, dz);

        double allowedHoriz = computeAllowedHorizontalSpeed(player, from, nowMs, profile);
        double horizOffset = horizDist - allowedHoriz;

        double offsetThresh = cfg.getDouble("movement.horiz-offset-threshold", 0.38);
        double severeThresh = cfg.getDouble("movement.horiz-severe-threshold", 0.65);
        long windowMs = cfg.getLong("movement.min-sustained-window-ms", 1100L);
        int minHits = cfg.getInt("movement.min-sustained-hits", 3);
        double vlForPunish = cfg.getDouble("movement.horiz-vl-for-punish", 8.5);

        if (horizOffset > offsetThresh && !profile.isLagging()) {
            HorizWindow current = horizWindows.get(player.getUniqueId());
            if (current == null || (nowMs - current.windowStartMs()) > windowMs) {
                horizWindows.put(player.getUniqueId(), new HorizWindow(nowMs, 1));
            } else {
                int nextCount = current.count() + 1;
                horizWindows.put(player.getUniqueId(), new HorizWindow(current.windowStartMs(), nextCount));

                if (nextCount >= minHits && (nowMs - current.windowStartMs()) >= 350L) {
                    double vlAdd = Math.min(1.40, horizOffset * 1.6);
                    profile.addMathVl(vlAdd, nowMs);
                    if (horizOffset > severeThresh && profile.getMathVl() >= vlForPunish) {
                        plugin.getPunishmentManager().evaluateAndPunish(
                                player,
                                profile,
                                nowMs,
                                "HorizontalOffset(" + String.format("%.3f", horizOffset)
                                        + ",hits=" + nextCount + ")"
                        );
                    }
                }
            }
        } else {
            horizWindows.remove(player.getUniqueId());
        }
    }

    private void appendRotationSample(PlayerCheckProfile profile, long nowMs, float deltaYaw, float deltaPitch, double gcdYaw, double gcdPitch, boolean snapped) {
        var samples = profile.getRotationSamples();
        samples.addLast(new PlayerCheckProfile.RotationSample(nowMs, deltaYaw, deltaPitch, gcdYaw, gcdPitch, snapped));
        while (samples.size() > 64) {
            samples.pollFirst();
        }
    }

    private double computeAllowedHorizontalSpeed(Player player, Location from, long nowMs, PlayerCheckProfile profile) {
        double base = player.isOnGround() ? 0.42 : 0.54;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            base += amp * 0.10;
        }
        if ((nowMs - profile.getLastKnockbackTimeMs()) < 1800L) {
            base += 0.70;
        }
        Material blockType = from.getBlock().getType();
        if (blockType == Material.COBWEB) {
            base = 0.22;
        } else if (blockType == Material.ICE || blockType == Material.PACKED_ICE || blockType == Material.BLUE_ICE) {
            base += 0.28;
        }
        return base;
    }

    private double computeGcd(double value) {
        if (value < 1e-5) {
            return 0.0;
        }
        double a = value;
        double b = 0.15;
        int iter = 0;
        while (b > 1e-6 && iter++ < 16) {
            double temp = a % b;
            a = b;
            b = temp;
        }
        return a;
    }
}
