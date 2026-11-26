package com.zornoely.aimguard.profile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class PlayerCheckProfile {

    public record RotationSample(
            long timestampMs,
            float deltaYaw,
            float deltaPitch,
            double gcdYaw,
            double gcdPitch,
            boolean snapped
    ) {}

    public record HitSample(
            long timestampMs,
            double distance,
            double aimErrorDeg,
            boolean alignedStrafe
    ) {}

    private final UUID playerId;

    private double mathVl;
    private double heuristicVl;

    private long lastFlagTimeMs;
    private long lastDecayTimeMs = System.currentTimeMillis();
    private long lastCombatTimeMs;
    private long lastKnockbackTimeMs;
    private long lastKickTimeMs;
    private long mitigationUntilMs;

    private int lastPingMs;
    private int pingDeltaMs;
    private boolean lagging;
    private long lastTransactionAckMs;

    private final Deque<Long> clickTimestamps = new ArrayDeque<>();
    private final Deque<RotationSample> rotationSamples = new ArrayDeque<>();
    private final Deque<HitSample> hitSamples = new ArrayDeque<>();

    private double combatCheatScore;
    private double decoyLockScore;
    private int decoyLockTicks;
    private boolean decoyFlagged;

    private int consecutiveCritHits;
    private long lastCritHitMs;

    private double lastSimulatedVy;
    private boolean hasSimulatedVy;

    public PlayerCheckProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public double getMathVl() {
        return mathVl;
    }

    public double getHeuristicVl() {
        return heuristicVl;
    }

    public void addMathVl(double amount, long nowMs) {
        this.mathVl = Math.max(0.0, this.mathVl + amount);
        this.lastFlagTimeMs = nowMs;
    }

    public void addHeuristicVl(double amount, long nowMs) {
        this.heuristicVl = Math.max(0.0, this.heuristicVl + amount);
        this.lastFlagTimeMs = nowMs;
    }

    public void decayVl(double mathDecay, double heuristicDecay, long nowMs) {
        this.mathVl = Math.max(0.0, this.mathVl - mathDecay);
        this.heuristicVl = Math.max(0.0, this.heuristicVl - heuristicDecay);
        this.lastDecayTimeMs = nowMs;
    }

    public long getLastFlagTimeMs() {
        return lastFlagTimeMs;
    }

    public long getLastDecayTimeMs() {
        return lastDecayTimeMs;
    }

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

    public long getLastKickTimeMs() {
        return lastKickTimeMs;
    }

    public void setLastKickTimeMs(long lastKickTimeMs) {
        this.lastKickTimeMs = lastKickTimeMs;
    }

    public boolean isMitigating() {
        return System.currentTimeMillis() < mitigationUntilMs;
    }

    public void activateMitigation(long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        if (until > this.mitigationUntilMs) {
            this.mitigationUntilMs = until;
        }
    }

    public int getLastPingMs() {
        return lastPingMs;
    }

    public int getPingDeltaMs() {
        return pingDeltaMs;
    }

    public void updatePing(int newPingMs, long ackTimestampMs) {
        this.pingDeltaMs = Math.abs(newPingMs - this.lastPingMs);
        this.lastPingMs = newPingMs;
        this.lastTransactionAckMs = ackTimestampMs;
    }

    public long getLastTransactionAckMs() {
        return lastTransactionAckMs;
    }

    public boolean isLagging() {
        return lagging;
    }

    public void setLagging(boolean lagging) {
        this.lagging = lagging;
    }

    public Deque<Long> getClickTimestamps() {
        return clickTimestamps;
    }

    public Deque<RotationSample> getRotationSamples() {
        return rotationSamples;
    }

    public Deque<HitSample> getHitSamples() {
        return hitSamples;
    }

    public double getCombatCheatScore() {
        return combatCheatScore;
    }

    public void setCombatCheatScore(double combatCheatScore) {
        this.combatCheatScore = Math.max(0.0, Math.min(1.0, combatCheatScore));
    }

    public double getDecoyLockScore() {
        return decoyLockScore;
    }

    public void setDecoyLockScore(double decoyLockScore) {
        this.decoyLockScore = Math.max(0.0, Math.min(1.0, decoyLockScore));
    }

    public int getDecoyLockTicks() {
        return decoyLockTicks;
    }

    public void setDecoyLockTicks(int decoyLockTicks) {
        this.decoyLockTicks = Math.max(0, decoyLockTicks);
    }

    public boolean isDecoyFlagged() {
        return decoyFlagged;
    }

    public void setDecoyFlagged(boolean decoyFlagged) {
        this.decoyFlagged = decoyFlagged;
    }

    public int recordAndGetConsecutiveCrits(long nowMs) {
        if ((nowMs - lastCritHitMs) <= 2200L) {
            consecutiveCritHits++;
        } else {
            consecutiveCritHits = 1;
        }
        lastCritHitMs = nowMs;
        return consecutiveCritHits;
    }

    public void resetConsecutiveCrits() {
        consecutiveCritHits = 0;
    }

    public double getLastSimulatedVy() {
        return lastSimulatedVy;
    }

    public void setLastSimulatedVy(double lastSimulatedVy) {
        this.lastSimulatedVy = lastSimulatedVy;
    }

    public boolean hasSimulatedVy() {
        return hasSimulatedVy;
    }

    public void setHasSimulatedVy(boolean hasSimulatedVy) {
        this.hasSimulatedVy = hasSimulatedVy;
    }

    public void resetAll() {
        mathVl = 0.0;
        heuristicVl = 0.0;
        combatCheatScore = 0.0;
        decoyLockScore = 0.0;
        decoyLockTicks = 0;
        decoyFlagged = false;
        consecutiveCritHits = 0;
        mitigationUntilMs = 0L;
        clickTimestamps.clear();
        rotationSamples.clear();
        hitSamples.clear();
        hasSimulatedVy = false;
    }
}
