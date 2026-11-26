package com.zornoely.aimguard.decoy;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DecoyNpcManager {

    private record ActiveDecoy(
            UUID decoyEntityId,
            UUID ownerPlayerId,
            long spawnTimeMs,
            long expiresAtMs,
            String slotName
    ) {}

    private static final double[] RELATIVE_YAW_OFFSETS = {
            180.0, // Tam arka
            -90.0, // Tam sol
            90.0,  // Tam sag
            -135.0,// Sol arka capraz
            135.0, // Sag arka capraz
            -55.0, // Sol yan
            55.0   // Sag yan
    };

    private static final String[] SLOT_NAMES = {
            "ARKA",
            "SOL",
            "SAG",
            "SOL-ARKA",
            "SAG-ARKA",
            "SOL-YAN",
            "SAG-YAN"
    };

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, ActiveDecoy> activeByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSpawnByOwner = new ConcurrentHashMap<>();

    public DecoyNpcManager(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAnyDecoyEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        UUID id = entity.getUniqueId();
        for (ActiveDecoy decoy : activeByOwner.values()) {
            if (decoy.decoyEntityId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean handleDecoyAttack(Player attacker, Entity target, PlayerCheckProfile profile, long nowMs) {
        if (target == null || !isAnyDecoyEntity(target)) {
            return false;
        }

        ActiveDecoy ownerDecoy = activeByOwner.get(attacker.getUniqueId());
        if (ownerDecoy == null || !ownerDecoy.decoyEntityId().equals(target.getUniqueId()) || profile.isLagging()) {
            return true;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double vlAdd = cfg.getDouble("decoy.vl-per-decoy-flag", 2.20);

        profile.setDecoyLockTicks(profile.getDecoyLockTicks() + 3);
        profile.setDecoyLockScore(1.0);
        profile.addHeuristicVl(vlAdd, nowMs);

        plugin.getPunishmentManager().evaluateAndPunish(
                attacker,
                profile,
                nowMs,
                "DecoyAuraHit(slot=" + ownerDecoy.slotName() + ",reactionMs=" + (nowMs - ownerDecoy.spawnTimeMs()) + ")"
        );

        // Vurus alindiktan sonra NPC kisa surede kaybolur
        removeDecoy(attacker.getUniqueId());
        return true;
    }

    public void evaluateAutoDecoy(Player player, PlayerCheckProfile profile, long nowMs) {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        if (!cfg.getBoolean("decoy.enabled", true)) {
            return;
        }
        if (profile.isLagging()) {
            return;
        }
        if (activeByOwner.containsKey(player.getUniqueId())) {
            return;
        }

        long cooldownMs = cfg.getLong("decoy.respawn-cooldown-ms", 2400L);
        long lastSpawn = lastSpawnByOwner.getOrDefault(player.getUniqueId(), 0L);
        if ((nowMs - lastSpawn) < cooldownMs) {
            return;
        }

        boolean focused = plugin.isPlayerFocused(player.getUniqueId(), nowMs);
        boolean testOnly = plugin.isTestLogOnly(player.getUniqueId());

        double minVl = (focused || testOnly) ? 0.5 : cfg.getDouble("decoy.auto-spawn-min-vl", 1.8);
        double minScore = (focused || testOnly) ? 0.12 : cfg.getDouble("decoy.auto-spawn-min-score", 0.24);
        boolean spawnOnSuspiciousHit = cfg.getBoolean("decoy.spawn-on-suspicious-hit", true);

        double totalVl = profile.getMathVl() + profile.getHeuristicVl();
        boolean recentCombat = (nowMs - profile.getLastCombatTimeMs()) <= 9000L;
        boolean recentSnap = spawnOnSuspiciousHit
                && (nowMs - profile.getLastFastTurnMs()) <= 2500L
                && profile.getLastFastTurnDeg() >= cfg.getDouble("combat.snap-turn-min-deg", 45.0);

        if (recentCombat && (totalVl >= minVl || profile.getCombatCheatScore() >= minScore || recentSnap || focused || testOnly)) {
            spawnDecoyForPlayer(player, profile, nowMs);
        }
    }

    public void spawnDecoyForPlayer(Player player, PlayerCheckProfile profile, long nowMs) {
        removeDecoy(player.getUniqueId());

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double distance = cfg.getDouble("decoy.spawn-distance-blocks", 2.45);
        long visibleMs = cfg.getLong("decoy.visible-duration-ms", 1250L);

        int idx = ThreadLocalRandom.current().nextInt(RELATIVE_YAW_OFFSETS.length);
        double yawOffset = RELATIVE_YAW_OFFSETS[idx];
        String slotName = SLOT_NAMES[idx];

        Location spawnLoc = computeSpotLocation(player, yawOffset, distance);
        World world = spawnLoc.getWorld();
        if (world == null) {
            return;
        }

        // Oyuncuya dogru bakar sekilde yonlendir
        Vector lookAtPlayer = player.getEyeLocation().toVector().subtract(spawnLoc.toVector().add(new Vector(0, 1.4, 0)));
        if (lookAtPlayer.lengthSquared() > 1e-6) {
            spawnLoc.setDirection(lookAtPlayer.normalize());
        }

        Entity spawned = spawnNpcEntity(world, spawnLoc);
        if (spawned == null) {
            return;
        }

        profile.setDecoyLockTicks(0);
        profile.setDecoyLockScore(0.0);
        profile.setDecoyFlagged(false);

        lastSpawnByOwner.put(player.getUniqueId(), nowMs);
        activeByOwner.put(player.getUniqueId(),
                new ActiveDecoy(spawned.getUniqueId(), player.getUniqueId(), nowMs, nowMs + visibleMs, slotName));
    }

    private Entity spawnNpcEntity(World world, Location spawnLoc) {
        EntityType mannequinType = resolveEntityType("MANNEQUIN");
        if (mannequinType != null) {
            try {
                Entity entity = world.spawnEntity(spawnLoc, mannequinType);
                configureEntity(entity);
                return entity;
            } catch (Throwable ignored) {
            }
        }

        Villager villager = world.spawn(spawnLoc, Villager.class, v -> {
            v.setProfession(Villager.Profession.NITWIT);
            v.setVillagerType(Villager.Type.PLAINS);
            v.setAge(0);
            v.setAgeLock(true);
        });
        configureEntity(villager);
        return villager;
    }

    private void configureEntity(Entity entity) {
        entity.setGravity(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setCustomNameVisible(false);
        entity.customName(Component.empty());
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
            living.setRemoveWhenFarAway(true);
        }
    }

    private EntityType resolveEntityType(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void tickActiveDecoys() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, ActiveDecoy> entry : new ArrayList<>(activeByOwner.entrySet())) {
            UUID ownerId = entry.getKey();
            ActiveDecoy decoy = entry.getValue();

            if (now >= decoy.expiresAtMs()) {
                removeEntityById(decoy.decoyEntityId());
                activeByOwner.remove(ownerId);
                continue;
            }

            Player owner = Bukkit.getPlayer(decoy.ownerPlayerId());
            if (owner == null || !owner.isOnline() || owner.isDead()) {
                removeEntityById(decoy.decoyEntityId());
                activeByOwner.remove(ownerId);
                continue;
            }

            Entity entity = Bukkit.getEntity(decoy.decoyEntityId());
            if (entity == null || !entity.isValid() || entity.getWorld() != owner.getWorld()) {
                removeEntityById(decoy.decoyEntityId());
                activeByOwner.remove(ownerId);
            }
        }
    }

    public void checkAimLockOnDecoy(Player player, PlayerCheckProfile profile, Location toLoc, long nowMs) {
        ActiveDecoy decoy = activeByOwner.get(player.getUniqueId());
        if (decoy == null || toLoc == null || profile.isLagging()) {
            return;
        }

        Entity entity = Bukkit.getEntity(decoy.decoyEntityId());
        if (entity == null || !entity.isValid() || entity.getWorld() != toLoc.getWorld()) {
            return;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double instantLockFovDeg = cfg.getDouble("decoy.lock-fov-deg", 8.5);
        long minReactionMs = cfg.getLong("decoy.min-lock-reaction-ms", 60L);
        double vlPerFlag = cfg.getDouble("decoy.vl-per-decoy-flag", 2.20);

        long elapsedMs = nowMs - decoy.spawnTimeMs();
        if (elapsedMs < minReactionMs) {
            return;
        }

        Location eyeLoc = toLoc.clone().add(0.0, player.getEyeHeight(), 0.0);
        Vector targetCenter = entity.getLocation().clone().add(0.0, 1.35, 0.0).toVector();
        Vector toDecoy = targetCenter.subtract(eyeLoc.toVector());
        if (toDecoy.lengthSquared() < 1e-6) {
            return;
        }
        toDecoy.normalize();

        Vector lookDir = eyeLoc.getDirection().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, lookDir.dot(toDecoy)));
        double angleDeg = Math.toDegrees(Math.acos(dot));

        if (angleDeg <= instantLockFovDeg && !profile.isDecoyFlagged()) {
            profile.setDecoyFlagged(true);
            profile.setDecoyLockTicks(profile.getDecoyLockTicks() + 1);
            profile.setDecoyLockScore(1.0);
            profile.addHeuristicVl(vlPerFlag, nowMs);

            plugin.getPunishmentManager().evaluateAndPunish(
                    player,
                    profile,
                    nowMs,
                    "DecoyInstantLock(slot=" + decoy.slotName()
                            + ",angle=" + String.format("%.1f", angleDeg) + "deg"
                            + ",ms=" + elapsedMs + ")"
            );
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
        lastSpawnByOwner.clear();
    }

    private void removeEntityById(UUID id) {
        Entity e = Bukkit.getEntity(id);
        if (e != null && e.isValid()) {
            e.remove();
        }
    }

    private Location computeSpotLocation(Player player, double yawOffsetDeg, double distance) {
        Location base = player.getLocation();
        double rad = Math.toRadians(base.getYaw() + yawOffsetDeg);
        // Minecraft yaw: -sin(yaw), cos(yaw) yatay ileri yonudur
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);

        double x = base.getX() + (dirX * distance);
        double z = base.getZ() + (dirZ * distance);
        double y = base.getY();

        World world = base.getWorld();
        if (world != null) {
            Location candidate = new Location(world, x, y, z);
            if (!candidate.getBlock().isPassable() || !candidate.clone().add(0, 1, 0).getBlock().isPassable()) {
                return base.clone();
            }
            return candidate;
        }
        return new Location(world, x, y, z);
    }
}
