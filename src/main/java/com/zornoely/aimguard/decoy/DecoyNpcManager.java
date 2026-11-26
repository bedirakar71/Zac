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
            String slotName,
            int slotIndex,
            int waveNumber
    ) {}

    private record ChainState(
            int consecutiveLocks,
            long lastLockTimeMs,
            int lastSlotIndex
    ) {}

    private static final double[] RELATIVE_YAW_OFFSETS = {
            180.0, // 0: Tam arka
            -95.0, // 1: Tam sol
            95.0,  // 2: Tam sag
            -140.0,// 3: Sol arka capraz
            140.0, // 4: Sag arka capraz
            -65.0, // 5: Sol yan
            65.0   // 6: Sag yan
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
    private final Map<UUID, ChainState> chainByOwner = new ConcurrentHashMap<>();

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
        long reactionMs = Math.max(0L, nowMs - ownerDecoy.spawnTimeMs());
        long impossibleHitMs = cfg.getLong("decoy.impossible-hit-ms", 115L);

        ChainState prev = chainByOwner.getOrDefault(attacker.getUniqueId(), new ChainState(0, 0L, -1));
        int nextLocks = prev.consecutiveLocks() + 1;
        chainByOwner.put(attacker.getUniqueId(), new ChainState(nextLocks, nowMs, ownerDecoy.slotIndex()));

        boolean impossibleReaction = reactionMs <= impossibleHitMs;
        double baseVl = cfg.getDouble("decoy.vl-per-decoy-flag", 2.20);
        double vlToAdd = impossibleReaction
                ? (baseVl * 2.4)
                : (baseVl * (1.0 + (nextLocks * 0.45)));

        profile.setDecoyLockTicks(profile.getDecoyLockTicks() + 4);
        profile.setDecoyLockScore(1.0);
        profile.addHeuristicVl(vlToAdd, nowMs);

        String tag = impossibleReaction
                ? "DecoyImpossibleSnapHit(wave=" + ownerDecoy.waveNumber() + ",slot=" + ownerDecoy.slotName() + ",ms=" + reactionMs + ")"
                : "DecoyAuraHit(wave=" + ownerDecoy.waveNumber() + ",chain=" + nextLocks + ",slot=" + ownerDecoy.slotName() + ",ms=" + reactionMs + ")";

        plugin.getPunishmentManager().evaluateAndPunish(attacker, profile, nowMs, tag);

        // Vurustan sonra mevcut NPC kalkar; zincir devam ediyorsa hemen sonraki dalga planlanir
        removeDecoyOnly(attacker.getUniqueId());
        if (nextLocks < 3) {
            scheduleFollowUpWave(attacker, profile, nextLocks + 1, ownerDecoy.slotIndex());
        }
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

        ChainState chain = chainByOwner.get(player.getUniqueId());
        if (chain != null && (nowMs - chain.lastLockTimeMs()) > 12000L) {
            chainByOwner.remove(player.getUniqueId());
            chain = null;
        }

        boolean inActiveChain = chain != null && chain.consecutiveLocks() > 0 && chain.consecutiveLocks() < 3;
        long cooldownMs = inActiveChain ? 750L : cfg.getLong("decoy.respawn-cooldown-ms", 2200L);
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

        if (inActiveChain || (recentCombat && (totalVl >= minVl || profile.getCombatCheatScore() >= minScore || recentSnap || focused || testOnly))) {
            int wave = inActiveChain ? (chain.consecutiveLocks() + 1) : 1;
            int avoidSlot = inActiveChain ? chain.lastSlotIndex() : -1;
            spawnDecoyWave(player, profile, nowMs, wave, avoidSlot);
        }
    }

    public void spawnDecoyForPlayer(Player player, PlayerCheckProfile profile, long nowMs) {
        chainByOwner.remove(player.getUniqueId());
        spawnDecoyWave(player, profile, nowMs, 1, -1);
    }

    private void spawnDecoyWave(Player player, PlayerCheckProfile profile, long nowMs, int waveNumber, int avoidSlotIndex) {
        removeDecoyOnly(player.getUniqueId());

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double distance = cfg.getDouble("decoy.spawn-distance-blocks", 2.45);
        long visibleMs = cfg.getLong("decoy.visible-duration-ms", 1150L);

        int idx = pickDifferentSlot(avoidSlotIndex);
        double yawOffset = RELATIVE_YAW_OFFSETS[idx];
        String slotName = SLOT_NAMES[idx];

        Location spawnLoc = computeSpotLocation(player, yawOffset, distance);
        World world = spawnLoc.getWorld();
        if (world == null) {
            return;
        }

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
                new ActiveDecoy(spawned.getUniqueId(), player.getUniqueId(), nowMs, nowMs + visibleMs, slotName, idx, waveNumber));
    }

    private int pickDifferentSlot(int avoidSlotIndex) {
        int len = RELATIVE_YAW_OFFSETS.length;
        int idx = ThreadLocalRandom.current().nextInt(len);
        if (idx == avoidSlotIndex) {
            idx = (idx + 1 + ThreadLocalRandom.current().nextInt(len - 1)) % len;
        }
        return idx;
    }

    private void scheduleFollowUpWave(Player player, PlayerCheckProfile profile, int nextWave, int avoidSlotIndex) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline() || online.isDead()) {
                return;
            }
            PlayerCheckProfile currentProfile = plugin.getProfileIfPresent(playerId);
            if (currentProfile == null || currentProfile.isLagging()) {
                return;
            }
            if (!activeByOwner.containsKey(playerId)) {
                spawnDecoyWave(online, currentProfile, System.currentTimeMillis(), nextWave, avoidSlotIndex);
            }
        }, 16L); // ~0.8 saniye sonra farkli yonde 2./3. NPC cikar
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
        long minReactionMs = cfg.getLong("decoy.min-lock-reaction-ms", 45L);
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

            ChainState prev = chainByOwner.getOrDefault(player.getUniqueId(), new ChainState(0, 0L, -1));
            int nextLocks = prev.consecutiveLocks() + 1;
            chainByOwner.put(player.getUniqueId(), new ChainState(nextLocks, nowMs, decoy.slotIndex()));

            double chainMultiplier = 1.0 + ((nextLocks - 1) * 0.55);
            double vlToAdd = vlPerFlag * chainMultiplier;

            profile.setDecoyLockScore(Math.min(1.0, 0.45 * nextLocks));
            profile.addHeuristicVl(vlToAdd, nowMs);

            plugin.getPunishmentManager().evaluateAndPunish(
                    player,
                    profile,
                    nowMs,
                    "DecoyWave" + decoy.waveNumber() + "Lock(chain=" + nextLocks
                            + ",slot=" + decoy.slotName()
                            + ",angle=" + String.format("%.1f", angleDeg) + "deg"
                            + ",ms=" + elapsedMs + ")"
            );

            // Ilk NPC'ye aninda kitlendiyse kisa sure sonra farkli yonde 2. ve 3. NPC'yi tetikle
            if (nextLocks < 3) {
                scheduleFollowUpWave(player, profile, nextLocks + 1, decoy.slotIndex());
            }
        }
    }

    public void removeDecoy(UUID ownerPlayerId) {
        chainByOwner.remove(ownerPlayerId);
        removeDecoyOnly(ownerPlayerId);
    }

    private void removeDecoyOnly(UUID ownerPlayerId) {
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
        chainByOwner.clear();
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
