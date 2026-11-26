package com.zornoely.aimguard.profile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class PlayerCheckProfile {

    public record HitSample(long timestampMs, double distance, double aimErrorDeg, boolean alignedStrafe) {}

    public record RotationSample(
            long timestampMs,
            float deltaYaw,
            float deltaPitch,
            double gcdYaw,
            double gcdPitch,
            boolean snapped
    ) {}

    private final UUID playerId;

    private final Deque<Long> clickTimestamps = new ArrayDeque<>();
    private final Deque<HitSample> hitSamples = new ArrayDeque<>();
    private final Deque<RotationSample> rotationSamples = new ArrayDeque<>();

    private double mathVl;
    private double heuristicVl;
    private double combatCheatScore;

    private long lastCombatTimeMs;
    private long lastKnockbackTimeMs;
    private long lastFastTurnMs;
    private float lastFastTurnDeg;

    private long lastFakeCritMs;
    private int consecutiveFakeCrits;

    private int decoyLockTicks;
    private double decoyLockScore;
    private boolean decoyFlagged;

    private boolean lagging;
    private boolean hasSimulatedVy;

    public PlayerCheckProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Deque<Long> getClickTimestamps() {
        return clickTimestamps;
    }

    public Deque<HitSample> getHitSamples() {
        return hitSamples;
    }

    public Deque<RotationSample> getRotationSamples() {
        return rotationSamples;
    }

    public double getMathVl() {
        return mathVl;
    }

    public double getHeuristicVl() {
        return heuristicVl;
    }

    public void addMathVl(double amount, long nowMs) {
        if (amount <= 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return;
        }
        this.mathVl += amount;
        this.lastCombatTimeMs = Math.max(this.lastCombatTimeMs, nowMs);
    }

    public void addHeuristicVl(double amount, long nowMs) {
        if (amount <= 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return;
        }
        this.heuristicVl += amount;
        this.lastCombatTimeMs = Math.max(this.lastCombatTimeMs, nowMs);
    }

    public void decayVl(double mathDecay, double heuristicDecay) {
        this.mathVl = Math.max(0.0, this.mathVl - mathDecay);
        this.heuristicVl = Math.max(0.0, this.heuristicVl - heuristicDecay);
        this.combatCheatScore = Math.max(0.0, this.combatCheatScore - 0.08);
    }

    public double getCombatCheatScore() {
        return combatCheatScore;
    }

    public void setCombatCheatScore(double combatCheatScore) {
        this.combatCheatScore = Math.max(0.0, Math.min(1.0, combatCheatScore));
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

    public int recordAndGetConsecutiveCrits(long nowMs) {
        if ((nowMs - this.lastFakeCritMs) <= 650L) {
            this.consecutiveFakeCrits++;
        } else {
            this.consecutiveFakeCrits = 1;
        }
        this.lastFakeCritMs = nowMs;
        return this.consecutiveFakeCrits;
    }

    public void resetConsecutiveCrits() {
        this.consecutiveFakeCrits = 0;
        this.lastFakeCritMs = 0L;
    }

    public int getDecoyLockTicks() {
        return decoyLockTicks;
    }

    public void setDecoyLockTicks(int decoyLockTicks) {
        this.decoyLockTicks = Math.max(0, decoyLockTicks);
    }

    public double getDecoyLockScore() {
        return decoyLockScore;
    }

    public void setDecoyLockScore(double decoyLockScore) {
        this.decoyLockScore = Math.max(0.0, Math.min(1.0, decoyLockScore));
    }

    public boolean isDecoyFlagged() {
        return decoyFlagged;
    }

    public void setDecoyFlagged(boolean decoyFlagged) {
        this.decoyFlagged = decoyFlagged;
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

    public void clearMitigation() {
        // Bilerek bos: oyuncu hareketi/ekrani dondurulmaz
    }

    public void resetAll() {
        clickTimestamps.clear();
        hitSamples.clear();
        rotationSamples.clear();
        mathVl = 0.0;
        heuristicVl = 0.0;
        combatCheatScore = 0.0;
        lastCombatTimeMs = 0L;
        lastKnockbackTimeMs = 0L;
        lastFastTurnMs = 0L;
        lastFastTurnDeg = 0.0f;
        resetConsecutiveCrits();
        decoyLockTicks = 0;
        decoyLockScore = 0.0;
        decoyFlagged = false;
        lagging = false;
        hasSimulatedVy = false;
    }
}
