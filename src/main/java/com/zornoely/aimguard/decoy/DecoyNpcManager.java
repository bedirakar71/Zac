package com.zornoely.aimguard.decoy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
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
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class DecoyNpcManager {

    private record ActiveDecoy(
            int packetEntityId,
            UUID decoyUuid,
            UUID ownerPlayerId,
            Location spawnLoc,
            long spawnTimeMs,
            long expiresAtMs,
            String slotName,
            int slotIndex,
            int waveNumber,
            boolean packetPlayerNpc,
            Entity fallbackEntity
    ) {}

    private record ChainState(
            int confirmedLocks,
            long lastLockTimeMs,
            int lastSlotIndex,
            boolean hitConfirmed
    ) {}

    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(-910_000);

    private static final double[] RELATIVE_YAW_OFFSETS = {
            180.0, // Tam arka
            -95.0, // Sol arka/yan
            95.0,  // Sag arka/yan
            -140.0,// Sol arka capraz
            140.0, // Sag arka capraz
            -60.0, // Sol yan
            60.0   // Sag yan
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
        for (ActiveDecoy decoy : activeByOwner.values()) {
            if (decoy.fallbackEntity() != null && decoy.fallbackEntity().getUniqueId().equals(entity.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public boolean isDecoyEntityId(int entityId) {
        for (ActiveDecoy decoy : activeByOwner.values()) {
            if (decoy.packetEntityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    public void handlePacketAttackOnDecoy(Player attacker, int targetEntityId, long nowMs) {
        if (attacker == null || !attacker.isOnline() || plugin.isPlayerBypassed(attacker)) {
            return;
        }
        PlayerCheckProfile profile = plugin.getOrCreateProfile(attacker);
        if (profile.isLagging()) {
            return;
        }

        ActiveDecoy decoy = activeByOwner.get(attacker.getUniqueId());
        if (decoy == null || decoy.packetEntityId() != targetEntityId) {
            return;
        }

        long reactionMs = Math.max(0L, nowMs - decoy.spawnTimeMs());
        ChainState prev = chainByOwner.getOrDefault(attacker.getUniqueId(), new ChainState(0, 0L, -1, false));
        int nextLocks = prev.confirmedLocks() + 1;
        boolean verifiedCheat = nextLocks >= 2 || reactionMs <= 85L;

        chainByOwner.put(attacker.getUniqueId(), new ChainState(nextLocks, nowMs, decoy.slotIndex(), true));

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double baseVl = cfg.getDouble("decoy.vl-per-decoy-flag", 2.00);
        double vlAdd = verifiedCheat ? (baseVl * 1.45) : (baseVl * 0.65);

        profile.setDecoyLockTicks(profile.getDecoyLockTicks() + 3);
        profile.setDecoyLockScore(verifiedCheat ? 1.0 : 0.65);
        profile.addHeuristicVl(vlAdd, nowMs);

        String tag = verifiedCheat
                ? "DecoyVerifiedHit(wave=" + decoy.waveNumber() + ",locks=" + nextLocks + ",slot=" + decoy.slotName() + ",ms=" + reactionMs + ")"
                : "DecoyFirstHit(wave=" + decoy.waveNumber() + ",slot=" + decoy.slotName() + ",ms=" + reactionMs + ")";

        plugin.getPunishmentManager().evaluateAndPunish(attacker, profile, nowMs, tag);

        despawnDecoyOnly(attacker.getUniqueId());
        if (nextLocks < 2) {
            scheduleFollowUpWave(attacker, nextLocks + 1, decoy.slotIndex());
        }
    }

    public boolean handleDecoyAttack(Player attacker, Entity target, PlayerCheckProfile profile, long nowMs) {
        if (target == null || !isAnyDecoyEntity(target)) {
            return false;
        }
        ActiveDecoy ownerDecoy = activeByOwner.get(attacker.getUniqueId());
        if (ownerDecoy != null && ownerDecoy.fallbackEntity() != null
                && ownerDecoy.fallbackEntity().getUniqueId().equals(target.getUniqueId())) {
            handlePacketAttackOnDecoy(attacker, ownerDecoy.packetEntityId(), nowMs);
        }
        return true;
    }

    public void evaluateAutoDecoy(Player player, PlayerCheckProfile profile, long nowMs) {
        if (plugin.isPlayerBypassed(player)) {
            return;
        }
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        if (!cfg.getBoolean("decoy.enabled", true) || profile.isLagging()) {
            return;
        }
        if (activeByOwner.containsKey(player.getUniqueId())) {
            return;
        }

        ChainState chain = chainByOwner.get(player.getUniqueId());
        if (chain != null && (nowMs - chain.lastLockTimeMs()) > 10000L) {
            chainByOwner.remove(player.getUniqueId());
            chain = null;
        }

        boolean inChain = chain != null && chain.confirmedLocks() > 0 && chain.confirmedLocks() < 2;
        long cooldownMs = inChain ? 850L : cfg.getLong("decoy.respawn-cooldown-ms", 4500L);
        long lastSpawn = lastSpawnByOwner.getOrDefault(player.getUniqueId(), 0L);
        if ((nowMs - lastSpawn) < cooldownMs) {
            return;
        }

        boolean focused = plugin.isPlayerFocused(player.getUniqueId(), nowMs);
        boolean testOnly = plugin.isTestLogOnly(player.getUniqueId());

        double minVl = testOnly ? 1.2 : (focused ? 1.4 : cfg.getDouble("decoy.auto-spawn-min-vl", 2.8));
        double minScore = testOnly ? 0.22 : (focused ? 0.25 : cfg.getDouble("decoy.auto-spawn-min-score", 0.36));
        boolean spawnOnSuspiciousHit = cfg.getBoolean("decoy.spawn-on-suspicious-hit", true);

        double totalVl = profile.getMathVl() + profile.getHeuristicVl();
        boolean recentCombat = (nowMs - profile.getLastCombatTimeMs()) <= 7000L;
        boolean recentSnap = spawnOnSuspiciousHit
                && (nowMs - profile.getLastFastTurnMs()) <= 1800L
                && profile.getLastFastTurnDeg() >= cfg.getDouble("combat.snap-turn-min-deg", 55.0);

        if (inChain || (recentCombat && (totalVl >= minVl || profile.getCombatCheatScore() >= minScore || recentSnap))) {
            int wave = inChain ? (chain.confirmedLocks() + 1) : 1;
            int avoidSlot = inChain ? chain.lastSlotIndex() : -1;
            spawnDecoyWave(player, profile, nowMs, wave, avoidSlot);
        }
    }

    public void spawnDecoyForPlayer(Player player, PlayerCheckProfile profile, long nowMs) {
        chainByOwner.remove(player.getUniqueId());
        spawnDecoyWave(player, profile, nowMs, 1, -1);
    }

    private void spawnDecoyWave(Player player, PlayerCheckProfile profile, long nowMs, int waveNumber, int avoidSlotIndex) {
        despawnDecoyOnly(player.getUniqueId());

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double distance = cfg.getDouble("decoy.spawn-distance-blocks", 2.55);
        long visibleMs = cfg.getLong("decoy.visible-duration-ms", 1050L);

        int idx = pickDifferentSlot(avoidSlotIndex);
        double yawOffset = RELATIVE_YAW_OFFSETS[idx];
        String slotName = SLOT_NAMES[idx];

        Location spawnLoc = computeSpotLocation(player, yawOffset, distance);
        World world = spawnLoc.getWorld();
        if (world == null) {
            return;
        }

        Vector toEye = player.getEyeLocation().toVector().subtract(spawnLoc.toVector().add(new Vector(0, 1.45, 0)));
        if (toEye.lengthSquared() > 1e-6) {
            spawnLoc.setDirection(toEye.normalize());
        }

        int entityId = NEXT_ENTITY_ID.decrementAndGet();
        UUID decoyUuid = UUID.randomUUID();
        boolean spawnedAsPacketPlayer = sendPacketPlayerNpc(player, entityId, decoyUuid, spawnLoc);

        Entity fallbackEntity = null;
        if (!spawnedAsPacketPlayer) {
            fallbackEntity = spawnFallbackEntity(world, spawnLoc);
            if (fallbackEntity != null) {
                entityId = fallbackEntity.getEntityId();
                decoyUuid = fallbackEntity.getUniqueId();
            }
        }

        profile.setDecoyFlagged(false);
        lastSpawnByOwner.put(player.getUniqueId(), nowMs);
        activeByOwner.put(player.getUniqueId(), new ActiveDecoy(
                entityId,
                decoyUuid,
                player.getUniqueId(),
                spawnLoc,
                nowMs,
                nowMs + visibleMs,
                slotName,
                idx,
                waveNumber,
                spawnedAsPacketPlayer,
                fallbackEntity
        ));
     }
    private boolean sendPacketPlayerNpc(Player viewer, int entityId, UUID npcUuid, Location loc) {
        try {
            var api = PacketEvents.getAPI();
            if (api == null || api.getPlayerManager() == null) {
                return false;
            }
            String shortName = "Zrn_" + Integer.toHexString(Math.abs(entityId) % 0xFFF);
            UserProfile profile = new UserProfile(npcUuid, shortName);

            WrapperPlayServerPlayerInfoUpdate infoAdd = new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(
                            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED
                    ),
                    List.of(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                            profile,
                            false,
                            20,
                            GameMode.SURVIVAL,
                            Component.empty(),
                            null
                    ))
            );
            api.getPlayerManager().sendPacket(viewer, infoAdd);

            WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(npcUuid),
                    EntityTypes.PLAYER,
                    new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                    loc.getPitch(),
                    loc.getYaw(),
                    loc.getYaw(),
                    0,
                    Optional.empty()
            );
            api.getPlayerManager().sendPacket(viewer, spawnPacket);

            WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(entityId, loc.getYaw());
            api.getPlayerManager().sendPacket(viewer, headLook);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (viewer.isOnline() && activeByOwner.containsKey(viewer.getUniqueId())) {
                    WrapperPlayServerPlayerInfoUpdate removeListed = new WrapperPlayServerPlayerInfoUpdate(
                            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                            List.of(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                                    profile,
                                    false,
                                    20,
                                    GameMode.SURVIVAL,
                                    Component.empty(),
                                    null
                            ))
                    );
                    api.getPlayerManager().sendPacket(viewer, removeListed);
                }
            }, 8L);

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void destroyPacketPlayerNpc(Player viewer, int entityId, UUID npcUuid) {
        try {
            var api = PacketEvents.getAPI();
            if (api != null && api.getPlayerManager() != null && viewer != null && viewer.isOnline()) {
                api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityId));
                api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfo(
                        WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
                        new WrapperPlayServerPlayerInfo.PlayerData(
                                Component.empty(),
                                new UserProfile(npcUuid, ""),
                                GameMode.SURVIVAL,
                                0
                        )
                ));
            }
        } catch (Throwable ignored) {
        }
    }

    private Entity spawnFallbackEntity(World world, Location spawnLoc) {
        EntityType mannequinType = resolveEntityType("MANNEQUIN");
        if (mannequinType != null) {
            try {
                Entity entity = world.spawnEntity(spawnLoc, mannequinType);
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
                return entity;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private EntityType resolveEntityType(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int pickDifferentSlot(int avoidSlotIndex) {
        int len = RELATIVE_YAW_OFFSETS.length;
        int idx = ThreadLocalRandom.current().nextInt(len);
        if (idx == avoidSlotIndex) {
            idx = (idx + 1 + ThreadLocalRandom.current().nextInt(len - 1)) % len;
        }
        return idx;
    }

    private void scheduleFollowUpWave(Player player, int nextWave, int avoidSlotIndex) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline() || online.isDead() || plugin.isPlayerBypassed(online)) {
                return;
            }
            PlayerCheckProfile currentProfile = plugin.getProfileIfPresent(playerId);
            if (currentProfile == null || currentProfile.isLagging()) {
                return;
            }
            if (!activeByOwner.containsKey(playerId)) {
                spawnDecoyWave(online, currentProfile, System.currentTimeMillis(), nextWave, avoidSlotIndex);
            }
        }, 18L);
    }

    public void tickActiveDecoys() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, ActiveDecoy> entry : new ArrayList<>(activeByOwner.entrySet())) {
            UUID ownerId = entry.getKey();
            ActiveDecoy decoy = entry.getValue();

            if (now >= decoy.expiresAtMs()) {
                despawnDecoyOnly(ownerId);
                continue;
            }

            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()) {
                despawnDecoyOnly(ownerId);
            }
        }
    }

    public void checkAimLockOnDecoy(Player player, PlayerCheckProfile profile, Location toLoc, long nowMs) {
        if (plugin.isPlayerBypassed(player)) {
            return;
        }
        ActiveDecoy decoy = activeByOwner.get(player.getUniqueId());
        if (decoy == null || toLoc == null || profile.isLagging()) {
            return;
        }
        if (decoy.spawnLoc().getWorld() != toLoc.getWorld()) {
            return;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double lockFovDeg = cfg.getDouble("decoy.lock-fov-deg", 7.5);
        long minReactionMs = cfg.getLong("decoy.min-lock-reaction-ms", 90L);
        double vlPerFlag = cfg.getDouble("decoy.vl-per-decoy-flag", 2.00);

        long elapsedMs = nowMs - decoy.spawnTimeMs();
        if (elapsedMs < minReactionMs) {
            return;
        }

        Location eyeLoc = toLoc.clone().add(0.0, player.getEyeHeight(), 0.0);
        Vector targetCenter = decoy.spawnLoc().clone().add(0.0, 1.40, 0.0).toVector();
        Vector toDecoy = targetCenter.subtract(eyeLoc.toVector());
        if (toDecoy.lengthSquared() < 1e-6) {
            return;
        }
        toDecoy.normalize();

        Vector lookDir = eyeLoc.getDirection().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, lookDir.dot(toDecoy)));
        double angleDeg = Math.toDegrees(Math.acos(dot));

        if (angleDeg <= lockFovDeg && !profile.isDecoyFlagged()) {
            profile.setDecoyFlagged(true);
            profile.setDecoyLockTicks(profile.getDecoyLockTicks() + 1);

            ChainState prev = chainByOwner.getOrDefault(player.getUniqueId(), new ChainState(0, 0L, -1, false));
            int nextLocks = prev.confirmedLocks() + 1;
            chainByOwner.put(player.getUniqueId(), new ChainState(nextLocks, nowMs, decoy.slotIndex(), prev.hitConfirmed()));

            double weight = (nextLocks >= 2) ? 1.35 : 0.55;
            profile.setDecoyLockScore(Math.min(1.0, 0.5 * nextLocks));
            profile.addHeuristicVl(vlPerFlag * weight, nowMs);

            plugin.getPunishmentManager().evaluateAndPunish(
                    player,
                    profile,
                    nowMs,
                    "DecoyWave" + decoy.waveNumber() + "Lock(locks=" + nextLocks
                            + ",slot=" + decoy.slotName()
                            + ",angle=" + String.format("%.1f", angleDeg) + "deg"
                            + ",ms=" + elapsedMs + ")"
            );

            if (nextLocks < 2) {
                scheduleFollowUpWave(player, nextLocks + 1, decoy.slotIndex());
            }
        }
    }

    public void removeDecoy(UUID ownerPlayerId) {
        chainByOwner.remove(ownerPlayerId);
        despawnDecoyOnly(ownerPlayerId);
    }

    private void despawnDecoyOnly(UUID ownerPlayerId) {
        ActiveDecoy removed = activeByOwner.remove(ownerPlayerId);
        if (removed != null) {
            if (removed.packetPlayerNpc()) {
                Player owner = Bukkit.getPlayer(ownerPlayerId);
                destroyPacketPlayerNpc(owner, removed.packetEntityId(), removed.decoyUuid());
            }
            if (removed.fallbackEntity() != null && removed.fallbackEntity().isValid()) {
                removed.fallbackEntity().remove();
            }
        }
    }

    public void clearAll() {
        for (UUID ownerId : new ArrayList<>(activeByOwner.keySet())) {
            despawnDecoyOnly(ownerId);
        }
        activeByOwner.clear();
        lastSpawnByOwner.clear();
        chainByOwner.clear();
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
