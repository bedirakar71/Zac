package com.zornoely.aimguard.profile;

import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Oyuncu basina tutulan tespit durumu. Paket thread'i (netty) ve ana thread ayni nesneye eristigi icin
 * paylasilan yapilar senkronize veya volatile'dir.
 */
public final class PlayerCheckProfile {

    /** Tespit kategorileri. Ceza vermek icin birden fazla savas kategorisi veya "sert kanit" gerekir. */
    public enum Category {
        REACH, WALL, AIM, KILLAURA, PACKET, DECOY, MOVEMENT;

        public boolean isCombat() {
            return this != MOVEMENT;
        }
    }

    public record HitSample(long timestampMs, double distance, double aimErrorDeg, double normCenterError, boolean targetMoving) {}

    /** Istemciden gelen rotasyon paketi ornegi (delta'lar isaretli). */
    public record RotationSample(long timestampMs, float yaw, float pitch, float deltaYaw, float deltaPitch) {}

    /** Vurus aninda alinan, rotasyon paketleri tamamlandiktan sonra analiz edilecek kayit. */
    public record PendingHit(
            long attackMs,
            UUID targetUuid,
            int targetEntityId,
            double eyeX, double eyeY, double eyeZ,
            BoundingBox box,
            double distance,
            boolean targetMoving,
            float eventYaw,
            float eventPitch,
            double slackBlocks
    ) {}

    private record AttackPacket(int entityId, long timeMs) {}

    private static final class Buf {
        double value;
        long lastMs;
    }

    private final UUID playerId;
    private final long createdMs = System.currentTimeMillis();

    // --- rotasyon halkasi (paket thread'i yazar, ana thread okur) ---
    private final ArrayDeque<RotationSample> rotations = new ArrayDeque<>();
    private volatile float packetYaw;
    private volatile float packetPitch;
    private volatile boolean packetRotationInit;

    // --- vurus verileri (ana thread) ---
    private final Deque<HitSample> hitSamples = new ArrayDeque<>();
    private final Deque<PendingHit> pendingHits = new ArrayDeque<>();
    private final ArrayDeque<AttackPacket> attackPackets = new ArrayDeque<>();

    // --- tampon (buffer) sistemi ---
    private final Map<String, Buf> buffers = new HashMap<>();

    // --- kanit takibi ---
    private final EnumMap<Category, Long> lastFlagMs = new EnumMap<>(Category.class);
    private volatile long lastHardEvidenceMs;

    private volatile double mathVl;
    private volatile double heuristicVl;
    private volatile double combatCheatScore;

    private volatile long lastCombatTimeMs;
    private volatile long lastKnockbackTimeMs;
    private volatile long lastFastTurnMs;
    private volatile float lastFastTurnDeg;
    private volatile long lastKickTimeMs;
    private volatile long lastDecayTimeMs;

    private volatile long graceUntilMs;
    private volatile long lastVelocityMs;
    private volatile long lastDamagedMs;
    private volatile long aimMitigationUntilMs;

    private volatile double decoyLockScore;
    private volatile int decoyHits;

    private volatile boolean lagging;
    private volatile boolean hasSimulatedVy;
    private volatile int lastPingMs = -1;
    private volatile long pingDeltaMs;
    private volatile boolean bedrock;
    private volatile boolean bypassCached;

    public PlayerCheckProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public long getCreatedMs() {
        return createdMs;
    }

    // ------------------------------------------------------------------ rotasyon

    public void addRotation(RotationSample sample) {
        synchronized (rotations) {
            rotations.addLast(sample);
            while (rotations.size() > 64) {
                rotations.pollFirst();
            }
        }
    }

    public List<RotationSample> rotationsBetween(long fromMs, long toMs) {
        List<RotationSample> out = new ArrayList<>();
        synchronized (rotations) {
            for (RotationSample s : rotations) {
                if (s.timestampMs() >= fromMs && s.timestampMs() <= toMs) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public RotationSample lastRotationBefore(long ms) {
        RotationSample best = null;
        synchronized (rotations) {
            for (RotationSample s : rotations) {
                if (s.timestampMs() < ms) {
                    best = s;
                } else {
                    break;
                }
            }
        }
        return best;
    }

    public int rotationCount() {
        synchronized (rotations) {
            return rotations.size();
        }
    }

    public boolean isPacketRotationInit() {
        return packetRotationInit;
    }

    public float getPacketYaw() {
        return packetYaw;
    }

    public float getPacketPitch() {
        return packetPitch;
    }

    public void setPacketRotation(float yaw, float pitch) {
        this.packetYaw = yaw;
        this.packetPitch = pitch;
        this.packetRotationInit = true;
    }

    // ------------------------------------------------------------------ vurus kayitlari

    public Deque<HitSample> getHitSamples() {
        return hitSamples;
    }

    public void addPendingHit(PendingHit hit) {
        synchronized (pendingHits) {
            pendingHits.addLast(hit);
            while (pendingHits.size() > 32) {
                pendingHits.pollFirst();
            }
        }
    }

    public PendingHit pollPendingHitOlderThan(long nowMs, long minAgeMs) {
        synchronized (pendingHits) {
            PendingHit first = pendingHits.peekFirst();
            if (first != null && (nowMs - first.attackMs()) >= minAgeMs) {
                return pendingHits.pollFirst();
            }
            return null;
        }
    }

    public boolean hasPendingHits() {
        synchronized (pendingHits) {
            return !pendingHits.isEmpty();
        }
    }

    /** Istemciden gelen bir ATTACK paketini kaydeder (paket thread'i). */
    public void recordAttackPacket(int entityId, long nowMs) {
        synchronized (attackPackets) {
            attackPackets.addLast(new AttackPacket(entityId, nowMs));
            while (!attackPackets.isEmpty() && (nowMs - attackPackets.peekFirst().timeMs()) > 1500L) {
                attackPackets.pollFirst();
            }
            while (attackPackets.size() > 48) {
                attackPackets.pollFirst();
            }
        }
    }

    /** Bir Bukkit vurus olayini gercek bir istemci paketiyle eslestirir (eklenti kaynakli hasari eler). */
    public boolean consumeAttackPacket(int entityId, long nowMs, long maxAgeMs) {
        synchronized (attackPackets) {
            var it = attackPackets.iterator();
            while (it.hasNext()) {
                AttackPacket p = it.next();
                if (p.entityId() == entityId && (nowMs - p.timeMs()) <= maxAgeMs) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tampon sistemi

    public synchronized double addBuffer(String key, double amount, long nowMs, double decayPerSec) {
        Buf b = buffers.computeIfAbsent(key, k -> new Buf());
        decay(b, nowMs, decayPerSec);
        b.value += amount;
        return b.value;
    }

    public synchronized double peekBuffer(String key, long nowMs, double decayPerSec) {
        Buf b = buffers.get(key);
        if (b == null) {
            return 0.0;
        }
        decay(b, nowMs, decayPerSec);
        return b.value;
    }

    public synchronized void resetBuffer(String key) {
        buffers.remove(key);
    }

    private static void decay(Buf b, long nowMs, double decayPerSec) {
        if (b.lastMs > 0L && decayPerSec > 0.0) {
            double dt = Math.max(0L, nowMs - b.lastMs) / 1000.0;
            b.value = Math.max(0.0, b.value - (dt * decayPerSec));
        }
        b.lastMs = nowMs;
    }

    // ------------------------------------------------------------------ kanit

    public synchronized void recordFlag(Category category, long nowMs, boolean hard) {
        lastFlagMs.put(category, nowMs);
        if (hard) {
            lastHardEvidenceMs = nowMs;
        }
    }

    public synchronized int distinctCombatCategories(long nowMs, long windowMs) {
        int n = 0;
        for (Map.Entry<Category, Long> e : lastFlagMs.entrySet()) {
            if (e.getKey().isCombat() && (nowMs - e.getValue()) <= windowMs) {
                n++;
            }
        }
        return n;
    }

    public long getLastHardEvidenceMs() {
        return lastHardEvidenceMs;
    }

    // ------------------------------------------------------------------ VL

    public double getMathVl() {
        return mathVl;
    }

    public double getHeuristicVl() {
        return heuristicVl;
    }

    public synchronized void addMathVl(double amount, long nowMs) {
        if (amount <= 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return;
        }
        this.mathVl += amount;
        this.lastCombatTimeMs = Math.max(this.lastCombatTimeMs, nowMs);
    }

    public synchronized void addHeuristicVl(double amount, long nowMs) {
        if (amount <= 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return;
        }
        this.heuristicVl += amount;
        this.lastCombatTimeMs = Math.max(this.lastCombatTimeMs, nowMs);
    }

    public synchronized void decayVl(double mathDecay, double heuristicDecay, long nowMs) {
        this.mathVl = Math.max(0.0, this.mathVl - mathDecay);
        this.heuristicVl = Math.max(0.0, this.heuristicVl - heuristicDecay);
        this.combatCheatScore = Math.max(0.0, this.combatCheatScore - 0.06);
        this.lastDecayTimeMs = nowMs;
    }

    public long getLastDecayTimeMs() {
        return lastDecayTimeMs;
    }

    public double getCombatCheatScore() {
        return combatCheatScore;
    }

    public void setCombatCheatScore(double combatCheatScore) {
        this.combatCheatScore = Math.max(0.0, Math.min(1.0, combatCheatScore));
    }

    // ------------------------------------------------------------------ zaman damgalari

    public long getLastCombatTimeMs() {
        return lastCombatTimeMs;
    }

    public void setLastCombatTimeMs(long lastCombatTimeMs) {
        this.lastCombatTimeMs = lastCombatTimeMs;
    }

    public long getLastKnockbackTimeMs() {
        return lastKnockbackTimeMs;
    }

    public void setLastKnockbackTimeMs(long lastKnockbackTimeMs) {
        this.lastKnockbackTimeMs = lastKnockbackTimeMs;
    }

    public long getLastFastTurnMs() {
        return lastFastTurnMs;
    }

    public float getLastFastTurnDeg() {
        return lastFastTurnDeg;
    }

    public void recordFastTurn(float deg, long nowMs) {
        this.lastFastTurnDeg = deg;
        this.lastFastTurnMs = nowMs;
    }

    public long getLastKickTimeMs() {
        return lastKickTimeMs;
    }

    public void setLastKickTimeMs(long lastKickTimeMs) {
        this.lastKickTimeMs = lastKickTimeMs;
    }

    public void grace(long untilMs) {
        if (untilMs > this.graceUntilMs) {
            this.graceUntilMs = untilMs;
        }
    }

    public boolean inGrace(long nowMs) {
        return nowMs < graceUntilMs;
    }

    public long getLastVelocityMs() {
        return lastVelocityMs;
    }

    public void setLastVelocityMs(long ms) {
        this.lastVelocityMs = ms;
    }

    public long getLastDamagedMs() {
        return lastDamagedMs;
    }

    public void setLastDamagedMs(long ms) {
        this.lastDamagedMs = ms;
    }

    public void activateAimMitigation(long untilMs) {
        if (untilMs > aimMitigationUntilMs) {
            aimMitigationUntilMs = untilMs;
        }
    }

    public boolean isAimMitigating(long nowMs) {
        return nowMs < aimMitigationUntilMs;
    }

    // ------------------------------------------------------------------ decoy

    public double getDecoyLockScore() {
        return decoyLockScore;
    }

    public void setDecoyLockScore(double decoyLockScore) {
        this.decoyLockScore = Math.max(0.0, Math.min(1.0, decoyLockScore));
    }

    public int getDecoyHits() {
        return decoyHits;
    }

    public void incrementDecoyHits() {
        this.decoyHits++;
    }

    // ------------------------------------------------------------------ ping / lag

    public void updatePing(int pingMs, long nowMs) {
        if (this.lastPingMs >= 0) {
            this.pingDeltaMs = Math.abs(pingMs - this.lastPingMs);
        } else {
            this.pingDeltaMs = 0L;
        }
        this.lastPingMs = pingMs;
        if (this.lastDecayTimeMs == 0L) {
            this.lastDecayTimeMs = nowMs;
        }
    }

    public int getLastPingMs() {
        return Math.max(0, lastPingMs);
    }

    public long getPingDeltaMs() {
        return pingDeltaMs;
    }

    public boolean isLagging() {
        return lagging;
    }

    public void setLagging(boolean lagging) {
        this.lagging = lagging;
    }

    public boolean hasSimulatedVy() {
        return hasSimulatedVy;
    }

    public void setHasSimulatedVy(boolean hasSimulatedVy) {
        this.hasSimulatedVy = hasSimulatedVy;
    }

    public boolean isBedrock() {
        return bedrock;
    }

    public void setBedrock(boolean bedrock) {
        this.bedrock = bedrock;
    }

    public boolean isBypassCached() {
        return bypassCached;
    }

    public void setBypassCached(boolean bypassCached) {
        this.bypassCached = bypassCached;
    }

    // ------------------------------------------------------------------ eski API uyumu (movement mitigation)

    public void activateMitigation(long durationMs) {
        // Hareket kisitlamasi bilincli olarak kapali: yalnizca vurus iptali (aim mitigation) kullanilir.
    }

    public boolean isMitigating() {
        return false;
    }

    public void clearMitigation() {
        // no-op
    }

    public synchronized void resetAll() {
        synchronized (rotations) {
            rotations.clear();
        }
        synchronized (pendingHits) {
            pendingHits.clear();
        }
        synchronized (attackPackets) {
            attackPackets.clear();
        }
        hitSamples.clear();
        buffers.clear();
        lastFlagMs.clear();
        lastHardEvidenceMs = 0L;
        mathVl = 0.0;
        heuristicVl = 0.0;
        combatCheatScore = 0.0;
        lastCombatTimeMs = 0L;
        lastKnockbackTimeMs = 0L;
        lastFastTurnMs = 0L;
        lastFastTurnDeg = 0.0f;
        lastKickTimeMs = 0L;
        lastDecayTimeMs = 0L;
        aimMitigationUntilMs = 0L;
        decoyLockScore = 0.0;
        decoyHits = 0;
        lagging = false;
        hasSimulatedVy = false;
        lastPingMs = -1;
        pingDeltaMs = 0L;
    }
}
