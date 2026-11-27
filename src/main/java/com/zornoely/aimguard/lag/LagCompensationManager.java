package com.zornoely.aimguard.lag;

import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Her tick'te oyuncu konum anlik goruntusu tutar (lag telafisi icin gecmis konumlar) ve sunucunun gercek
 * tick akisini izler. Eski surumdeki "1 dakikalik TPS < 18.5 ise her seyi kapat" mantigi kaldirildi:
 * artik yalnizca gercek bir tick duraklamasi veya oyuncunun ani ping sicramasi "lag" sayilir ve bu durumda
 * bile log yazilir, sadece ceza/VL durdurulur.
 */
public final class LagCompensationManager {

    public record Snapshot(long timeMs, double x, double y, double z, float yaw, float pitch, double eyeHeight) {}

    private static final long KEEP_MS = 2200L;

    private final Map<UUID, ArrayDeque<Snapshot>> history = new ConcurrentHashMap<>();

    private volatile long lastTickMs;
    private volatile long stallUntilMs;
    private volatile double recentTps = 20.0;
    private long tpsWindowStartMs;
    private int tpsWindowTicks;

    /** Ana thread'de her tick cagrilir. */
    public void onServerTick(long nowMs) {
        long last = lastTickMs;
        if (last != 0L && (nowMs - last) >= 160L) {
            stallUntilMs = Math.max(stallUntilMs, nowMs + 2000L);
        }
        lastTickMs = nowMs;

        if (tpsWindowStartMs == 0L) {
            tpsWindowStartMs = nowMs;
            tpsWindowTicks = 0;
        }
        tpsWindowTicks++;
        long span = nowMs - tpsWindowStartMs;
        if (span >= 5000L) {
            recentTps = Math.min(20.0, (tpsWindowTicks * 1000.0) / span);
            tpsWindowStartMs = nowMs;
            tpsWindowTicks = 0;
            if (recentTps < 17.0) {
                stallUntilMs = Math.max(stallUntilMs, nowMs + 3000L);
            }
        }
    }

    public boolean isServerStalling(long nowMs) {
        return nowMs < stallUntilMs;
    }

    public double getRecentTps() {
        return recentTps;
    }

    /** Her saniye: ping ornegi alir ve oyuncunun lag durumunu gunceller. */
    public void recordSample(Player player, PlayerCheckProfile profile, long nowMs) {
        int ping = Math.max(0, player.getPing());
        profile.updatePing(ping, nowMs);

        boolean pingSpike = profile.getPingDeltaMs() >= 120L;
        boolean highPing = ping >= 350;
        profile.setLagging(pingSpike || highPing || isServerStalling(nowMs));
    }

    /** Her tick: oyuncunun konumunu gecmise ekler. */
    public void recordTick(Player player, long nowMs) {
        Location l = player.getLocation();
        if (l.getWorld() == null) {
            return;
        }
        ArrayDeque<Snapshot> q = history.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Snapshot(nowMs, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), player.getEyeHeight()));
            while (!q.isEmpty() && (nowMs - q.peekFirst().timeMs()) > KEEP_MS) {
                q.pollFirst();
            }
        }
    }

    public List<Snapshot> window(UUID playerId, long fromMs, long toMs) {
        ArrayDeque<Snapshot> q = history.get(playerId);
        List<Snapshot> out = new ArrayList<>();
        if (q == null) {
            return out;
        }
        synchronized (q) {
            for (Snapshot s : q) {
                if (s.timeMs() >= fromMs && s.timeMs() <= toMs) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Son iki goruntu arasindaki tick basina hiz (blok/tick). */
    public double speedPerTick(UUID playerId) {
        ArrayDeque<Snapshot> q = history.get(playerId);
        if (q == null) {
            return 0.0;
        }
        Snapshot a = null;
        Snapshot b = null;
        synchronized (q) {
            if (q.size() < 2) {
                return 0.0;
            }
            var it = q.descendingIterator();
            b = it.next();
            a = it.next();
        }
        long dt = Math.max(1L, b.timeMs() - a.timeMs());
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();
        double dist = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
        return Math.min(1.5, dist / Math.max(1.0, dt / 50.0));
    }

    public void remove(UUID playerId) {
        history.remove(playerId);
    }

    public void clear() {
        history.clear();
    }
}
