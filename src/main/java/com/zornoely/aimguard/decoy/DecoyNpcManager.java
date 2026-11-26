package com.zornoely.aimguard.decoy;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DecoyNpcManager {

    private record ActiveDecoy(
            UUID decoyEntityId,
            UUID ownerPlayerId,
            long spawnTimeMs,
            long expiresAtMs,
            double angleRad
    ) {}

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, ActiveDecoy> activeByOwner = new ConcurrentHashMap<>();

    public DecoyNpcManager(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void evaluateAutoDecoy(Player player, PlayerCheckProfile profile, long nowMs) {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        if (!cfg.getBoolean("decoy.enabled", true)) {
            return;
        }
        if (profile.isLagging() || player.isGliding()) {
            return;
        }
        if (activeByOwner.containsKey(player.getUniqueId())) {
            return;
        }

        double minVl = cfg.getDouble("decoy.auto-spawn-min-vl", 4.5);
        double totalVl = profile.getMathVl() + profile.getHeuristicVl();
        boolean recentCombat = (nowMs - profile.getLastCombatTimeMs()) <= 4500L;

        if (recentCombat && (totalVl >= minVl || profile.getCombatCheatScore() >= 0.58)) {
            spawnDecoyForPlayer(player, profile, nowMs);
        }
    }

    public void spawnDecoyForPlayer(Player player, PlayerCheckProfile profile, long nowMs) {
        removeDecoy(player.getUniqueId());

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double radius = cfg.getDouble("decoy.radius-blocks", 2.4);
        long durationTicks = cfg.getLong("decoy.duration-ticks", 100L);
        long durationMs = durationTicks * 50L;

        Location base = player.getLocation();
        double angle = 0.0;
        Location spawnLoc = computeOrbitLocation(base, angle, radius);
        World world = spawnLoc.getWorld();
        if (world == null) {
            return;
        }

        ArmorStand stand = world.spawn(spawnLoc, ArmorStand.class, entity -> {
            entity.setVisible(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setMarker(false);
            entity.setSmall(false);
            entity.setBasePlate(false);
            entity.setArms(true);
            entity.setCustomNameVisible(true);
            entity.customName(Component.text("§8[§dDecoy§8]"));
        });

        profile.setDecoyLockTicks(0);
        profile.setDecoyLockScore(0.0);
        profile.setDecoyFlagged(false);

        activeByOwner.put(player.getUniqueId(),
                new ActiveDecoy(stand.getUniqueId(), player.getUniqueId(), nowMs, nowMs + durationMs, angle));
    }

    public void tickActiveDecoys() {
        long now = System.currentTimeMillis();
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double radius = cfg.getDouble("decoy.radius-blocks", 2.4);

        Iterator> it = activeByOwner.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveDecoy> entry = it.next();
            ActiveDecoy decoy = entry.getValue();

            if (now >= decoy.expiresAtMs()) {
                removeEntityById(decoy.decoyEntityId());
                it.remove();
                continue;
            }

            Player owner = Bukkit.getPlayer(decoy.ownerPlayerId());
            if (owner == null || !owner.isOnline() || owner.isDead()) {
                removeEntityById(decoy.decoyEntityId());
                it.remove();
                continue;
            }

            Entity entity = Bukkit.getEntity(decoy.decoyEntityId());
            if (!(entity instanceof ArmorStand stand) || !stand.isValid()) {
                it.remove();
                continue;
            }

            double nextAngle = decoy.angleRad() + 0.32;
            Location targetLoc = computeOrbitLocation(owner.getLocation(), nextAngle, radius);
            if (stand.getWorld() == targetLoc.getWorld()) {
                stand.teleport(targetLoc);
            }

            entry.setValue(new ActiveDecoy(
                    decoy.decoyEntityId(),
                    decoy.ownerPlayerId(),
                    decoy.spawnTimeMs(),
                    decoy.expiresAtMs(),
                    nextAngle
            ));
        }
    }

    public void checkAimLockOnDecoy(Player player, PlayerCheckProfile profile, Location toLoc, long nowMs) {
        ActiveDecoy decoy = activeByOwner.get(player.getUniqueId());
        if (decoy == null || toLoc == null || profile.isLagging() || player.isGliding()) {
            return;
        }

        Entity entity = Bukkit.getEntity(decoy.decoyEntityId());
        if (!(entity instanceof ArmorStand stand) || !stand.isValid() || stand.getWorld() != toLoc.getWorld()) {
            return;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double lockFovDeg = cfg.getDouble("decoy.lock-fov-deg", 11.5);
        int minLockTicks = cfg.getInt("decoy.min-lock-ticks-for-score", 6);
        double scoreThreshold = cfg.getDouble("decoy.lock-score-threshold", 0.72);
        double vlPerFlag = cfg.getDouble("decoy.vl-per-decoy-flag", 1.40);

        Location eyeLoc = toLoc.clone().add(0.0, player.getEyeHeight(), 0.0);
        Vector decoyCenter = stand.getLocation().clone().add(0.0, 1.0, 0.0).toVector();
        Vector toDecoy = decoyCenter.subtract(eyeLoc.toVector());
        if (toDecoy.lengthSquared() < 1e-6) {
            return;
        }
        toDecoy.normalize();

        Vector lookDir = eyeLoc.getDirection().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, lookDir.dot(toDecoy)));
        double angleDeg = Math.toDegrees(Math.acos(dot));

        if (angleDeg <= lockFovDeg) {
            int ticks = profile.getDecoyLockTicks() + 1;
            profile.setDecoyLockTicks(ticks);

            double normalized = Math.min(1.0, (double) ticks / Math.max(1, minLockTicks * 2));
            profile.setDecoyLockScore(normalized);

            if (ticks >= minLockTicks && normalized >= scoreThreshold && !profile.isDecoyFlagged()) {
                profile.setDecoyFlagged(true);
                profile.addHeuristicVl(vlPerFlag, nowMs);
                plugin.getPunishmentManager().evaluateAndPunish(
                        player,
                        profile,
                        nowMs,
                        "DecoyLock(ticks=" + ticks + ",angle=" + String.format("%.1f", angleDeg) + "deg)"
                );
            }
        } else {
            int current = profile.getDecoyLockTicks();
            if (current > 0) {
                profile.setDecoyLockTicks(current - 1);
            }
            profile.setDecoyLockScore(Math.max(0.0, profile.getDecoyLockScore() - 0.12));
            if (profile.getDecoyLockTicks() < Math.max(2, minLockTicks / 2)) {
                profile.setDecoyFlagged(false);
            }
        }
    }

    public void removeDecoy(UUID ownerPlayerId) {
        ActiveDecoy removed = activeByOwner.remove(ownerPlayerId);
        if (removed != null) {
            removeEntityById(removed.decoyEntityId());
        }
    }

    public void clearAll() {
        for (ActiveDecoy decoy : activeByOwner.values()) {
            removeEntityById(decoy.decoyEntityId());
        }
        activeByOwner.clear();
    }

    private void removeEntityById(UUID id) {
        Entity e = Bukkit.getEntity(id);
        if (e != null && e.isValid()) {
            e.remove();
        }
    }

    private Location computeOrbitLocation(Location center, double angleRad, double radius) {
        double x = center.getX() + Math.cos(angleRad) * radius;
        double y = center.getY() + 0.15;
        double z = center.getZ() + Math.sin(angleRad) * radius;
        return new Location(center.getWorld(), x, y, z);
    }
}
