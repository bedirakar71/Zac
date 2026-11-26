package com.zornoely.aimguard.decoy;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DecoyNpcManager {

    private static final class ActiveDecoy {
        final UUID playerId;
        final ArmorStand decoyEntity;
        final long spawnedAtMs;
        double angleRad;

        ActiveDecoy(UUID playerId, ArmorStand decoyEntity, long spawnedAtMs) {
            this.playerId = playerId;
            this.decoyEntity = decoyEntity;
            this.spawnedAtMs = spawnedAtMs;
            this.angleRad = 0.0;
        }
    }

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, ActiveDecoy> activeDecoys = new ConcurrentHashMap<>();

    public DecoyNpcManager(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void spawnDecoyForPlayer(Player target, PlayerCheckProfile profile, long nowMs) {
        removeDecoy(target.getUniqueId());
        profile.setDecoyLockTicks(0);
        profile.setDecoyFlagged(false);

        Location spawnLoc = target.getLocation().clone().add(2.3, 0.0, 0.0);
        ArmorStand stand = (ArmorStand) target.getWorld().spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
        stand.setVisible(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSmall(false);
        stand.setMarker(false);
        stand.customName(Component.text("§8[Decoy]"));
        stand.setCustomNameVisible(false);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(target.getUniqueId())) {
                other.hideEntity(plugin, stand);
            }
        }

        activeDecoys.put(target.getUniqueId(), new ActiveDecoy(target.getUniqueId(), stand, nowMs));
    }

    public void evaluateAutoDecoy(Player player, PlayerCheckProfile profile, long nowMs) {
        if (activeDecoys.containsKey(player.getUniqueId())) {
            return;
        }
        boolean inCombat = (nowMs - profile.getLastCombatTimeMs()) < 4000L;
        boolean suspicious = profile.getCombatCheatScore() >= 0.45 || profile.getHeuristicVl() >= 3.5;
        if (inCombat && suspicious && !profile.isLagging()) {
            spawnDecoyForPlayer(player, profile, nowMs);
        }
    }

    public void tickActiveDecoys() {
        long now = System.currentTimeMillis();
        Iterator> it = activeDecoys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveDecoy> entry = it.next();
            ActiveDecoy decoy = entry.getValue();
            Player player = Bukkit.getPlayer(decoy.playerId);

            if (player == null || !player.isOnline() || !decoy.decoyEntity.isValid() || (now - decoy.spawnedAtMs) > 4500L) {
                if (decoy.decoyEntity.isValid()) {
                    decoy.decoyEntity.remove();
                }
                it.remove();
                continue;
            }

            decoy.angleRad += 0.25;
            double radius = 2.4;
            double x = player.getLocation().getX() + Math.cos(decoy.angleRad) * radius;
            double z = player.getLocation().getZ() + Math.sin(decoy.angleRad) * radius;
            double y = player.getLocation().getY();
            decoy.decoyEntity.teleport(new Location(player.getWorld(), x, y, z));
        }
    }

    public void checkAimLockOnDecoy(Player player, PlayerCheckProfile profile, Location to, long nowMs) {
        ActiveDecoy decoy = activeDecoys.get(player.getUniqueId());
        if (decoy == null || !decoy.decoyEntity.isValid() || profile.isLagging()) {
            return;
        }

        Location eye = to.clone().add(0, player.getEyeHeight(), 0);
        Location targetHead = decoy.decoyEntity.getLocation().clone().add(0, 1.35, 0);
        Vector dirToDecoy = targetHead.toVector().subtract(eye.toVector());
        double dist = dirToDecoy.length();
        if (dist < 0.2 || dist > 6.5) {
            return;
        }

        dirToDecoy.normalize();
        Vector look = eye.getDirection().normalize();
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, look.dot(dirToDecoy)))));

        if (angleDeg < 4.5) {
            int lockTicks = profile.getDecoyLockTicks() + 1;
            profile.setDecoyLockTicks(lockTicks);
            profile.setDecoyLockScore(Math.min(1.0, lockTicks / 14.0));

            if (lockTicks >= 8 && !profile.isDecoyFlagged()) {
                profile.setDecoyFlagged(true);
                profile.addHeuristicVl(2.1, nowMs);
                plugin.getPunishmentEngine().evaluateCrossCheck(
                        player,
                        profile,
                        nowMs,
                        "DecoyAimLock(ticks=" + lockTicks + ",angle=" + String.format("%.1f", angleDeg) + ")"
                );
            }
        } else {
            profile.setDecoyLockTicks(profile.getDecoyLockTicks() - 1);
            profile.setDecoyLockScore(profile.getDecoyLockTicks() / 14.0);
        }
    }

    public void removeDecoy(UUID playerId) {
        ActiveDecoy decoy = activeDecoys.remove(playerId);
        if (decoy != null && decoy.decoyEntity.isValid()) {
            decoy.decoyEntity.remove();
        }
    }

    public void clearAll() {
        for (ActiveDecoy decoy : activeDecoys.values()) {
            if (decoy.decoyEntity.isValid()) {
                decoy.decoyEntity.remove();
            }
        }
        activeDecoys.clear();
    }
}
