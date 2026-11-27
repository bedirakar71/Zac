package com.zornoely.aimguard.movement;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.profile.PlayerCheckProfile.Category;
import com.zornoely.aimguard.profile.PlayerCheckProfile.RotationSample;
import com.zornoely.aimguard.punishment.PunishmentRecordManager.Severity;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basit yatay hiz kontrolu. Savas hilelerine odakli bu eklentide hareket kategorisi "yumusak" kanittir:
 * tek basina asla kick/ban sebebi olmaz (bkz. PunishmentRecordManager.flag), yalnizca loglanir.
 */
public final class MovementSimulationChecker {

    private record HorizWindow(long windowStartMs, int count) {}

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, HorizWindow> horizWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastMoveYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastMovePitch = new ConcurrentHashMap<>();

    public MovementSimulationChecker(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearPlayer(UUID uuid) {
        horizWindows.remove(uuid);
        lastMoveYaw.remove(uuid);
        lastMovePitch.remove(uuid);
    }

    public void checkMovement(Player player, PlayerCheckProfile profile, Location from, Location to, long nowMs) {
        if (from == null || to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        // PacketEvents calismiyorsa rotasyon orneklerini Bukkit hareket olaylarindan uret (yedek mod)
        if (!plugin.getPacketEventsBridge().isHooked()) {
            recordFallbackRotation(player, profile, to, nowMs);
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) {
            return;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        if (!cfg.getBoolean("movement.enabled", true)) {
            return;
        }

        boolean elytraSafe = cfg.getBoolean("environment.elytra-safe-mode", true);
        boolean recentKbOrImpact = (nowMs - profile.getLastKnockbackTimeMs()) < 1800L
                || (nowMs - profile.getLastVelocityMs()) < 2500L
                || (nowMs - profile.getLastDamagedMs()) < 1200L;
        boolean special = player.isRiptiding() || player.isSwimming() || player.isInWater() || player.isClimbing()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        if (profile.inGrace(nowMs) || special
                || (elytraSafe && (player.isGliding() || recentKbOrImpact || player.getPing() > 140))) {
            profile.setHasSimulatedVy(false);
            horizWindows.remove(player.getUniqueId());
            return;
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizDist = Math.hypot(dx, dz);

        double allowedHoriz = computeAllowedHorizontalSpeed(player, from, nowMs, profile);
        double horizOffset = horizDist - allowedHoriz;

        double offsetThresh = cfg.getDouble("movement.horiz-offset-threshold", 0.45);
        double severeThresh = cfg.getDouble("movement.horiz-severe-threshold", 0.75);
        long windowMs = cfg.getLong("movement.min-sustained-window-ms", 1100L);
        int minHits = cfg.getInt("movement.min-sustained-hits", 4);
        double vlForPunish = cfg.getDouble("movement.horiz-vl-for-punish", 9.0);

        if (horizOffset > offsetThresh && !profile.isLagging()) {
            HorizWindow current = horizWindows.get(player.getUniqueId());
            if (current == null || (nowMs - current.windowStartMs()) > windowMs) {
                horizWindows.put(player.getUniqueId(), new HorizWindow(nowMs, 1));
            } else {
                int nextCount = current.count() + 1;
                horizWindows.put(player.getUniqueId(), new HorizWindow(current.windowStartMs(), nextCount));

                if (nextCount >= minHits && (nowMs - current.windowStartMs()) >= 350L) {
                    profile.addMathVl(Math.min(1.0, horizOffset * 1.2), nowMs);
                    String detail = String.format(Locale.ROOT, "fark=%.3f vurus=%d", horizOffset, nextCount);
                    boolean punishPhase = horizOffset > severeThresh && profile.getMathVl() >= vlForPunish;
                    plugin.getPunishmentManager().flag(player, profile, nowMs, Category.MOVEMENT, "HorizontalOffset",
                            detail, punishPhase ? Severity.NORMAL : Severity.SOFT);
                }
            }
        } else {
            horizWindows.remove(player.getUniqueId());
        }
    }

    private void recordFallbackRotation(Player player, PlayerCheckProfile profile, Location to, long nowMs) {
        UUID id = player.getUniqueId();
        float yaw = to.getYaw();
        float pitch = to.getPitch();
        Float py = lastMoveYaw.put(id, yaw);
        Float pp = lastMovePitch.put(id, pitch);
        if (py == null || pp == null) {
            return;
        }
        float dy = yaw - py;
        while (dy > 180.0f) {
            dy -= 360.0f;
        }
        while (dy < -180.0f) {
            dy += 360.0f;
        }
        float dp = pitch - pp;
        if (dy == 0.0f && dp == 0.0f) {
            return;
        }
        if (profile.inGrace(nowMs)) {
            dy = 0.0f;
            dp = 0.0f;
        }
        profile.addRotation(new RotationSample(nowMs, yaw, pitch, dy, dp));
        float mag = (float) Math.hypot(dy, dp);
        if (mag >= 40.0f) {
            profile.recordFastTurn(mag, nowMs);
        }
    }

    private double computeAllowedHorizontalSpeed(Player player, Location from, long nowMs, PlayerCheckProfile profile) {
        double base = player.isOnGround() ? 0.42 : 0.54;
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            base += (speed.getAmplifier() + 1) * 0.10;
        }
        // Eklentilerin degistirdigi yurume hizi (varsayilan 0.2)
        double walkRatio = Math.max(1.0, player.getWalkSpeed() / 0.2f);
        base *= walkRatio;

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
}
