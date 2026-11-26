package com.zornoely.aimguard.lag;

import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LagCompensationManager {

    public record PositionSnapshot(long timestampMs, Location location) {}

    private final Map<UUID, Deque<PositionSnapshot>> historyByPlayer = new ConcurrentHashMap<>();

    public void recordSample(Player player, PlayerCheckProfile profile, long nowMs, double currentTps) {
        int ping = Math.max(0, player.getPing());
        profile.updatePing(ping, nowMs);

        // False-positive onlemi: ani ping sicramasi (90ms+), yuksek ping (240ms+) veya dusuk TPS (18.5-)
        boolean pingSpike = profile.getPingDeltaMs() >= 90 || ping >= 240;
        boolean serverLag = currentTps < 18.5;
        profile.setLagging(pingSpike || serverLag);
    }

    public void recordPositionSnapshot(UUID playerId, Location loc, long nowMs) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        Deque<PositionSnapshot> queue = historyByPlayer.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        queue.addLast(new PositionSnapshot(nowMs, loc.clone()));

        while (!queue.isEmpty() && (nowMs - queue.peekFirst().timestampMs()) > 1600L) {
            queue.pollFirst();
        }
    }

    public Location getRewoundLocation(UUID targetId, int attackerPingMs, long nowMs) {
        Deque<PositionSnapshot> queue = historyByPlayer.get(targetId);
        if (queue == null || queue.isEmpty()) {
            return null;
        }

        long rewindMs = Math.max(0L, Math.min(600L, (long) attackerPingMs));
        long targetTime = nowMs - rewindMs;

        PositionSnapshot best = queue.peekFirst();
        long bestDiff = Math.abs(best.timestampMs() - targetTime);

        for (PositionSnapshot snap : queue) {
            long diff = Math.abs(snap.timestampMs() - targetTime);
            if (diff <= bestDiff) {
                bestDiff = diff;
                best = snap;
            }
        }
        return best.location().clone();
    }
}
