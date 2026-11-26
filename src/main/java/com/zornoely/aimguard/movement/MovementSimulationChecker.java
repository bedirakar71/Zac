package com.zornoely.aimguard.movement;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Deque;

public final class MovementSimulationChecker {

    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.9800000190734863;
    private static final double MIN_VY = 0.003;
    private static final double OFFSET_TOLERANCE = 0.0025;

    private final ZornoAimGuardPlugin plugin;

    public MovementSimulationChecker(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkMovement(Player player, PlayerCheckProfile profile, Location from, Location to, long nowMs) {
        if (to == null || from == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }

        analyzeRotation(player, profile, from, to, nowMs);

        // Ozel hareket durumlarinda ve lag sirasinda false-positive engelleme
        if (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isGliding()
                || player.isSwimming()
                || player.isRiptiding()
                || player.isInsideVehicle()
                || profile.isLagging()
                || (nowMs - profile.getLastKnockbackTimeMs()) < 650L) {
            profile.setHasSimulatedVy(false);
            return;
        }

        double actualVy = to.getY() - from.getY();
        boolean onGround = player.isOnGround();

        if (!onGround && profile.hasSimulatedVy()) {
            double predictedVy = (profile.getLastSimulatedVy() - GRAVITY) * VERTICAL_DRAG;
            if (Math.abs(predictedVy) < MIN_VY) {
                predictedVy = 0.0;
            }

            double vyOffset = Math.abs(actualVy - predictedVy);
            double jumpBonus = player.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                    ? (player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1) * 0.22
                    : 0.0;

            if (actualVy > predictedVy + OFFSET_TOLERANCE + jumpBonus && actualVy > 0.0) {
                profile.addMathVl(1.15, nowMs);
                plugin.getPunishmentEngine().evaluateCrossCheck(
                        player,
                        profile,
                        nowMs,
                        "VerticalOffset(" + String.format("%.4f", vyOffset) + ")"
                );
            }
        }

        profile.setLastSimulatedVy(actualVy);
        profile.setHasSimulatedVy(!onGround);

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDist = Math.hypot(dx, dz);
        double maxAllowedHorizontal = computeMaxHorizontalSpeed(player, from);

        if (horizontalDist > maxAllowedHorizontal + 0.035) {
            profile.addMathVl(0.95, nowMs);
            plugin.getPunishmentEngine().evaluateCrossCheck(
                    player,
                    profile,
                    nowMs,
                    "HorizontalOffset(" + String.format("%.3f", horizontalDist) + ")"
            );
        }
    }

    private double computeMaxHorizontalSpeed(Player player, Location from) {
        double base = player.isSprinting() ? 0.37 : 0.29;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            base += amp * 0.08;
        }
        Block below = from.clone().subtract(0, 0.25, 0).getBlock();
        Material type = below.getType();
        if (type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE) {
            base *= 1.7;
        } else if (type == Material.SOUL_SAND || type == Material.SOUL_SOIL) {
            base *= 0.7;
        }
        return base;
    }

    private void analyzeRotation(Player player, PlayerCheckProfile profile, Location from, Location to, long nowMs) {
        float deltaYaw = Math.abs(wrapAngle180(to.getYaw() - from.getYaw()));
        float deltaPitch = Math.abs(to.getPitch() - from.getPitch());

        if (deltaYaw < 0.02f && deltaPitch < 0.02f) {
            return;
        }

        double gcdYaw = computeGcd(deltaYaw, 0.001);
        double gcdPitch = computeGcd(deltaPitch, 0.001);

        // Hizli flick yapan iyi oyunculari ayirmak icin tek seferlik snap degil, tekrarli durum olculur
        boolean snapped = deltaYaw > 45.0f && deltaPitch > 15.0f;

        Deque<PlayerCheckProfile.RotationSample> samples = profile.getRotationSamples();
        samples.addLast(new PlayerCheckProfile.RotationSample(nowMs, deltaYaw, deltaPitch, gcdYaw, gcdPitch, snapped));
        while (samples.size() > 64) {
            samples.pollFirst();
        }
    }

    private double computeGcd(double a, double b) {
        if (b < 1e-7) {
            return a;
        }
        int iterations = 0;
        while (b > 1e-6 && iterations++ < 25) {
            double temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    private float wrapAngle180(float angle) {
        float wrapped = (angle + 180.0f) % 360.0f;
        if (wrapped < 0) {
            wrapped += 360.0f;
        }
        return wrapped - 180.0f;
    }
}
