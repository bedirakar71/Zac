package com.zornoely.aimguard.movement;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

public final class MovementSimulationChecker {

    private final ZornoAimGuardPlugin plugin;

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
        if (player.isGliding() || player.isInsideVehicle() || player.isFlying()) {
            return;
        }

        // Rotation sample kaydi (64 ornek penceresi)
        float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
        if (deltaYaw > 180.0f) {
            deltaYaw = 360.0f - deltaYaw;
        }
        float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
        double gcdYaw = computeGcd(deltaYaw);
        double gcdPitch = computeGcd(deltaPitch);
        boolean snapped = deltaYaw > 28.0f && deltaPitch < 1.5f;

        DequeAdd(profile, nowMs, deltaYaw, deltaPitch, gcdYaw, gcdPitch, snapped);

        // Yatay hareket simülasyonu (HorizontalOffset + hız/etki/ağ toleransı)
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizDist = Math.hypot(dx, dz);

        double allowedHoriz = computeAllowedHorizontalSpeed(player, from, nowMs, profile);
        double horizOffset = horizDist - allowedHoriz;

        if (horizOffset > 0.08 && !profile.isLagging()) {
            double vlAdd = Math.min(2.2, horizOffset * 3.2);
            profile.addMathVl(vlAdd, nowMs);
            if (horizOffset > 0.28 && profile.getMathVl() >= 4.5) {
                plugin.getPunishmentManager().evaluateAndPunish(
                        player,
                        profile,
                        nowMs,
                        "HorizontalOffset(" + String.format("%.3f", horizOffset) + ")"
                );
            }
        }

        // Dikey / yerçekimi simülasyonu (VerticalOffset / AirStrafe / Step)
        double dy = to.getY() - from.getY();
        if (!player.isOnGround() && !isInClimbableOrFluid(from)) {
            if (!profile.hasSimulatedVy()) {
                profile.setLastSimulatedVy(dy);
                profile.setHasSimulatedVy(true);
            } else {
                double expectedVy = (profile.getLastSimulatedVy() - 0.08) * 0.98;
                double vertDiff = Math.abs(dy - expectedVy);
                if (vertDiff > 0.24 && dy > -0.15 && !profile.isLagging() && (nowMs - profile.getLastKnockbackTimeMs()) > 800L) {
                    profile.addMathVl(0.95, nowMs);
                    if (profile.getMathVl() >= 6.0) {
                        plugin.getPunishmentManager().evaluateAndPunish(
                                player,
                                profile,
                                nowMs,
                                "VerticalSim(diff=" + String.format("%.3f", vertDiff) + ")"
                        );
                    }
                }
                profile.setLastSimulatedVy(dy);
            }
        } else {
            profile.setHasSimulatedVy(false);
        }
    }

    private void DequeAdd(PlayerCheckProfile profile, long nowMs, float deltaYaw, float deltaPitch, double gcdYaw, double gcdPitch, boolean snapped) {
        var samples = profile.getRotationSamples();
        samples.addLast(new PlayerCheckProfile.RotationSample(nowMs, deltaYaw, deltaPitch, gcdYaw, gcdPitch, snapped));
        while (samples.size() > 64) {
            samples.pollFirst();
        }
    }

    private double computeAllowedHorizontalSpeed(Player player, Location from, long nowMs, PlayerCheckProfile profile) {
        double base = player.isOnGround() ? 0.36 : 0.44;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            base += amp * 0.085;
        }
        if ((nowMs - profile.getLastKnockbackTimeMs()) < 900L) {
            base += 0.45;
        }
        Material blockType = from.getBlock().getType();
        if (blockType == Material.COBWEB) {
            base = 0.16;
        } else if (blockType == Material.ICE || blockType == Material.PACKED_ICE || blockType == Material.BLUE_ICE) {
            base += 0.22;
        }
        return base;
    }

    private boolean isInClimbableOrFluid(Location loc) {
        Material m = loc.getBlock().getType();
        return m == Material.LADDER || m == Material.VINE || m == Material.WATER || m == Material.LAVA || m == Material.COBWEB;
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
