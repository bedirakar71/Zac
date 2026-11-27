package com.zornoely.aimguard.decoy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.combat.HitGeometry;
import com.zornoely.aimguard.packet.PacketEventsBridge;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.profile.PlayerCheckProfile.Category;
import com.zornoely.aimguard.profile.PlayerCheckProfile.RotationSample;
import com.zornoely.aimguard.punishment.PunishmentRecordManager.Severity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paket tabanli "tuzak" oyuncu NPC'si (yalnizca supheli oyuncuya gonderilir).
 *
 * <p>Onceki surumde PacketEvents hic baslatilmadigi icin paket NPC hicbir zaman olusmuyor, "yedek" varlik devreye
 * giriyordu (kullanicinin gordugu koylu bu yuzdendi). Artik: PacketEvents duzgun baslatilir, NPC her zaman gercek
 * bir OYUNCU varligi olarak gonderilir; PacketEvents yoksa tuzak sistemi sessizce kapali kalir, baska varlik
 * turu (koylu vb.) ASLA olusturulmaz.
 *
 * <p>Yanlis ban onlemi: tuzaga vurus yalnizca oyuncunun kursoru tuzagin uzerinde DEGILKEN kesin kanit sayilir
 * (gercek istemci kursor hedefte degilken vuramaz). Tuzaga bakarak vurmak (merak) sadece bilgi satiri olarak loglanir.
 */
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
            int waveNumber
    ) {}

    private record ChainState(int hits, long lastHitTimeMs, int lastSlotIndex) {}

    private static final AtomicInteger FALLBACK_ENTITY_ID = new AtomicInteger(1_800_000_000);

    private static final double[] RELATIVE_YAW_OFFSETS = {
            180.0, -95.0, 95.0, -140.0, 140.0, -60.0, 60.0
    };

    private static final String[] SLOT_NAMES = {
            "ARKA", "SOL", "SAG", "SOL-ARKA", "SAG-ARKA", "SOL-YAN", "SAG-YAN"
    };

    private static final String[] NAME_BASES = {
            "Mert", "Emre", "Kaan", "Efe", "Arda", "Berk", "Can", "Ege", "Onur", "Tuna", "Alp", "Baris", "Yigit", "Cem"
    };

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, ActiveDecoy> activeByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSpawnByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, ChainState> chainByOwner = new ConcurrentHashMap<>();
    private volatile boolean warnedUnavailable;

    public DecoyNpcManager(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** Tuzak sistemi yalnizca PacketEvents calisiyorsa kullanilabilir. */
    public boolean isAvailable() {
        PacketEventsBridge bridge = plugin.getPacketEventsBridge();
        return bridge != null && bridge.isHooked() && PacketEvents.getAPI() != null;
    }

    public boolean isDecoyEntityId(int entityId) {
        for (ActiveDecoy decoy : activeByOwner.values()) {
            if (decoy.packetEntityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    // ===================================================================== TUZAGA SALDIRI

    /** Ana thread: istemci tuzak varliga ATTACK paketi gonderdi. Karar birkac tick sonra, rotasyon paketleri gelince verilir. */
    public void onDecoyAttackPacket(UUID attackerId, int entityId, long attackMs) {
        Player attacker = Bukkit.getPlayer(attackerId);
        if (attacker == null || !attacker.isOnline() || plugin.isPlayerBypassed(attacker)) {
            return;
        }
        ActiveDecoy decoy = activeByOwner.get(attackerId);
        if (decoy == null || decoy.packetEntityId() != entityId) {
            return;
        }

        long reactionMs = Math.max(0L, attackMs - decoy.spawnTimeMs());
        Location s = decoy.spawnLoc();
        BoundingBox box = new BoundingBox(s.getX() - 0.3, s.getY(), s.getZ() - 0.3, s.getX() + 0.3, s.getY() + 1.8, s.getZ() + 0.3);
        Location eyeLoc = attacker.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        float yaw = eyeLoc.getYaw();
        float pitch = eyeLoc.getPitch();
        int wave = decoy.waveNumber();
        String slot = decoy.slotName();
        int slotIdx = decoy.slotIndex();

        despawnDecoyOnly(attackerId);
        ChainState prev = chainByOwner.get(attackerId);
        int hits = (prev != null ? prev.hits() : 0) + 1;
        chainByOwner.put(attackerId, new ChainState(hits, attackMs, slotIdx));

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> evaluateDecoyHit(attackerId, eye, box, yaw, pitch, attackMs, reactionMs, wave, slot, hits, slotIdx), 3L);
    }

    private void evaluateDecoyHit(UUID attackerId, Vector eye, BoundingBox box, float eventYaw, float eventPitch,
                                  long attackMs, long reactionMs, int wave, String slot, int chainHits, int slotIdx) {
        Player attacker = Bukkit.getPlayer(attackerId);
        if (attacker == null || !attacker.isOnline() || plugin.isPlayerBypassed(attacker)) {
            return;
        }
        PlayerCheckProfile profile = plugin.getProfileIfPresent(attackerId);
        if (profile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();

        List<float[]> candidates = new ArrayList<>();
        for (RotationSample s : profile.rotationsBetween(attackMs - 140L, attackMs + 110L)) {
            candidates.add(new float[]{s.yaw(), s.pitch()});
        }
        RotationSample before = profile.lastRotationBefore(attackMs - 140L);
        if (before != null) {
            candidates.add(new float[]{before.yaw(), before.pitch()});
        }
        candidates.add(new float[]{eventYaw, eventPitch});

        double bestErr = 180.0;
        for (float[] c : candidates) {
            bestErr = Math.min(bestErr, HitGeometry.angleToBoxDeg(eye, HitGeometry.direction(c[0], c[1]), box));
        }

        profile.incrementDecoyHits();
        String base = "dalga=" + wave + " yer=" + slot + " tepki=" + reactionMs + "ms";

        if (profile.rotationCount() < 4) {
            plugin.getViolationLogger().logInfo(attacker, "DecoyHit(dogrulanamadi)", base + " (rotasyon verisi yok)");
            return;
        }

        double aimedMax = cfg.getDouble("decoy.aimed-max-error-deg", 12.0) + Math.min(10.0, profile.getLastPingMs() / 20.0);
        if (bestErr > aimedMax) {
            double vl = cfg.getDouble("decoy.vl-per-aura-hit", 5.0);
            profile.setDecoyLockScore(1.0);
            boolean trustworthy = !profile.isLagging() && !profile.inGrace(now);
            String detail = base + String.format(Locale.ROOT, " bakis-hatasi=%.1f", bestErr);
            if (trustworthy) {
                profile.addHeuristicVl(vl, now);
                plugin.getPunishmentManager().flag(attacker, profile, now, Category.DECOY, "DecoyAuraHit", detail, Severity.HARD);
                profile.activateAimMitigation(now + 8000L);
            } else {
                plugin.getPunishmentManager().flag(attacker, profile, now, Category.DECOY, "DecoyAuraHit", detail, Severity.SOFT);
            }
            if (chainHits < cfg.getInt("decoy.max-waves", 3)) {
                scheduleFollowUpWave(attackerId, chainHits + 1, slotIdx);
            }
        } else {
            profile.setDecoyLockScore(Math.max(profile.getDecoyLockScore(), 0.4));
            plugin.getViolationLogger().logInfo(attacker, "DecoyHit(nisanli)",
                    base + String.format(Locale.ROOT, " bakis-hatasi=%.1f (kursor tuzakta, merak sayildi)", bestErr));
        }
    }

    // ===================================================================== OTOMATIK / MANUEL OLUSTURMA

    public void evaluateAutoDecoy(Player player, PlayerCheckProfile profile, long nowMs) {
        if (plugin.isPlayerBypassed(player) || profile.isBedrock() || profile.isLagging() || profile.inGrace(nowMs)) {
            return;
        }
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        if (!cfg.getBoolean("decoy.enabled", true) || !isAvailable()) {
            return;
        }
        if (activeByOwner.containsKey(player.getUniqueId())) {
            return;
        }

        ChainState chain = chainByOwner.get(player.getUniqueId());
        if (chain != null && (nowMs - chain.lastHitTimeMs()) > 12_000L) {
            chainByOwner.remove(player.getUniqueId());
        }

        long cooldownMs = cfg.getLong("decoy.respawn-cooldown-ms", 6000L);
        long lastSpawn = lastSpawnByOwner.getOrDefault(player.getUniqueId(), 0L);
        if ((nowMs - lastSpawn) < cooldownMs) {
            return;
        }

        boolean focused = plugin.isPlayerFocused(player.getUniqueId(), nowMs);
        boolean testOnly = plugin.isTestLogOnly(player.getUniqueId());
        double minVl = testOnly ? 1.2 : (focused ? 1.4 : cfg.getDouble("decoy.auto-spawn-min-vl", 3.0));
        double minScore = testOnly ? 0.22 : (focused ? 0.25 : cfg.getDouble("decoy.auto-spawn-min-score", 0.30));

        double totalVl = profile.getMathVl() + profile.getHeuristicVl();
        boolean recentCombat = (nowMs - profile.getLastCombatTimeMs()) <= 7000L;
        if (recentCombat && (totalVl >= minVl || profile.getCombatCheatScore() >= minScore)) {
            ChainState c = chainByOwner.get(player.getUniqueId());
            spawnDecoyWave(player, profile, nowMs, c != null ? c.hits() + 1 : 1, c != null ? c.lastSlotIndex() : -1);
        }
    }

    public void spawnDecoyForPlayer(Player player, PlayerCheckProfile profile, long nowMs) {
        chainByOwner.remove(player.getUniqueId());
        if (!isAvailable()) {
            warnUnavailable();
            return;
        }
        spawnDecoyWave(player, profile, nowMs, 1, -1);
    }

    private void warnUnavailable() {
        if (!warnedUnavailable) {
            warnedUnavailable = true;
            plugin.getLogger().warning("Tuzak NPC kapali: PacketEvents baslatilamadi. (Koylu/yedek varlik ASLA olusturulmaz.)");
        }
    }

    private void spawnDecoyWave(Player player, PlayerCheckProfile profile, long nowMs, int waveNumber, int avoidSlotIndex) {
        if (!isAvailable()) {
            warnUnavailable();
            return;
        }
        despawnDecoyOnly(player.getUniqueId());

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        double distance = cfg.getDouble("decoy.spawn-distance-blocks", 2.55);
        long visibleMs = cfg.getLong("decoy.visible-duration-ms", 1100L);

        int idx = -1;
        Location spawnLoc = null;
        int avoid = avoidSlotIndex;
        for (int attempt = 0; attempt < 4 && spawnLoc == null; attempt++) {
            int candidate = pickDifferentSlot(avoid);
            avoid = candidate;
            Location loc = computeSpotLocation(player, RELATIVE_YAW_OFFSETS[candidate], distance);
            if (loc != null) {
                spawnLoc = loc;
                idx = candidate;
            }
        }
        if (spawnLoc == null) {
            return; // uygun zemin yok: tuzak olusturma
        }

        Vector toEye = player.getEyeLocation().toVector().subtract(spawnLoc.toVector().add(new Vector(0, 1.45, 0)));
        if (toEye.lengthSquared() > 1.0E-6) {
            spawnLoc.setDirection(toEye.normalize());
        }

        int entityId = nextEntityId();
        UUID decoyUuid = UUID.randomUUID();
        if (!sendPacketPlayerNpc(player, entityId, decoyUuid, randomName(), spawnLoc)) {
            return;
        }

        profile.setDecoyLockScore(0.0);
        lastSpawnByOwner.put(player.getUniqueId(), nowMs);
        activeByOwner.put(player.getUniqueId(), new ActiveDecoy(
                entityId, decoyUuid, player.getUniqueId(), spawnLoc, nowMs, nowMs + visibleMs,
                SLOT_NAMES[idx], idx, waveNumber));
    }

    private int nextEntityId() {
        try {
            return SpigotReflectionUtil.generateEntityId();
        } catch (Throwable ignored) {
            return FALLBACK_ENTITY_ID.incrementAndGet();
        }
    }

    private static String randomName() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String name = NAME_BASES[r.nextInt(NAME_BASES.length)] + (r.nextBoolean() ? "_" : "") + (10 + r.nextInt(990));
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    // ===================================================================== PAKETLER

    private boolean sendPacketPlayerNpc(Player viewer, int entityId, UUID npcUuid, String name, Location loc) {
        try {
            var api = PacketEvents.getAPI();
            if (api == null || api.getPlayerManager() == null) {
                return false;
            }
            User user = api.getPlayerManager().getUser(viewer);
            if (user == null || user.getConnectionState() != ConnectionState.PLAY) {
                return false; // istemci surumu/baglantisi bilinmiyorsa yanlis varlik turu gitmesin
            }

            UserProfile npcProfile = new UserProfile(npcUuid, name);

            api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                    List.of(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(npcProfile, true, 30, GameMode.SURVIVAL, null, null))
            ));

            api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(npcUuid),
                    EntityTypes.PLAYER,
                    new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                    loc.getPitch(),
                    loc.getYaw(),
                    loc.getYaw(),
                    0,
                    Optional.empty()
            ));
            api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityHeadLook(entityId, loc.getYaw()));

            // Sekme listesinden kisa sure sonra cikar (kalici "hayalet" oyuncu kalmasin)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (viewer.isOnline()) {
                    try {
                        api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfoUpdate(
                                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                                List.of(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(npcProfile, false, 30, GameMode.SURVIVAL, null, null))
                        ));
                    } catch (Throwable ignored) {
                        // oyuncu ayrildi
                    }
                }
            }, 10L);
            return true;
        } catch (Throwable ex) {
            plugin.getLogger().warning("Tuzak NPC gonderilemedi: " + ex);
            return false;
        }
    }

    private void destroyPacketPlayerNpc(Player viewer, int entityId, UUID npcUuid) {
        try {
            var api = PacketEvents.getAPI();
            if (api != null && api.getPlayerManager() != null && viewer != null && viewer.isOnline()) {
                api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityId));
                api.getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfoRemove(npcUuid));
            }
        } catch (Throwable ignored) {
            // oyuncu ayrildi / PacketEvents kapandi
        }
    }

    // ===================================================================== DONGU

    private int pickDifferentSlot(int avoidSlotIndex) {
        int len = RELATIVE_YAW_OFFSETS.length;
        int idx = ThreadLocalRandom.current().nextInt(len);
        if (idx == avoidSlotIndex) {
            idx = (idx + 1 + ThreadLocalRandom.current().nextInt(len - 1)) % len;
        }
        return idx;
    }

    private void scheduleFollowUpWave(UUID playerId, int nextWave, int avoidSlotIndex) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline() || online.isDead() || plugin.isPlayerBypassed(online)) {
                return;
            }
            PlayerCheckProfile current = plugin.getProfileIfPresent(playerId);
            if (current == null || current.isLagging() || activeByOwner.containsKey(playerId)) {
                return;
            }
            spawnDecoyWave(online, current, System.currentTimeMillis(), nextWave, avoidSlotIndex);
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

    public void removeDecoy(UUID ownerPlayerId) {
        chainByOwner.remove(ownerPlayerId);
        lastSpawnByOwner.remove(ownerPlayerId);
        despawnDecoyOnly(ownerPlayerId);
    }

    private void despawnDecoyOnly(UUID ownerPlayerId) {
        ActiveDecoy removed = activeByOwner.remove(ownerPlayerId);
        if (removed != null) {
            destroyPacketPlayerNpc(Bukkit.getPlayer(ownerPlayerId), removed.packetEntityId(), removed.decoyUuid());
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

    /** Oyuncunun cevresinde, zemini olan ve icinde durulabilir bir nokta bulur; yoksa null. */
    private Location computeSpotLocation(Player player, double yawOffsetDeg, double distance) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) {
            return null;
        }
        double rad = Math.toRadians(base.getYaw() + yawOffsetDeg);
        double x = base.getX() - (Math.sin(rad) * distance);
        double z = base.getZ() + (Math.cos(rad) * distance);
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int by = (int) Math.floor(base.getY());

        for (int dy = 0; dy >= -2; dy--) {
            Block feet = world.getBlockAt(bx, by + dy, bz);
            Block head = feet.getRelative(0, 1, 0);
            Block below = feet.getRelative(0, -1, 0);
            if (feet.isPassable() && head.isPassable() && !below.isPassable()) {
                return new Location(world, x, feet.getY(), z);
            }
        }
        return null;
    }
}
