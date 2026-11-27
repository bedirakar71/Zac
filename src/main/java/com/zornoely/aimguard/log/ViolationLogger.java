package com.zornoely.aimguard.log;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tum tespitleri uc yere yazar: konsol, gunluk dosya (plugins/ZornoAimGuard/logs/) ve yetkililere oyun ici uyari.
 * Dosya yazimi ayri bir thread'de yapilir, sunucuyu yormaz. Lag/hosgoru nedeniyle ceza verilmeyen tespitler de
 * burada gorunur (action alaninda nedeni yazar).
 */
public final class ViolationLogger {

    public record Entry(long timeMs, String player, String category, String check, String detail,
                        double totalVl, int ping, String action) {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final ZornoAimGuardPlugin plugin;
    private final File logDir;
    private final ExecutorService io;
    private final Deque<Entry> recent = new ConcurrentLinkedDeque<>();
    private final Set<UUID> alertsMuted = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastAlertByKey = new ConcurrentHashMap<>();
    private final AtomicLong totalLogged = new AtomicLong();
    private volatile String lastFileError = "";

    public ViolationLogger(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
        this.logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists() && !logDir.mkdirs()) {
            plugin.getLogger().warning("Log klasoru olusturulamadi: " + logDir);
        }
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ZornoAimGuard-LogWriter");
            t.setDaemon(true);
            return t;
        });
    }

    public File getLogDir() {
        return logDir;
    }

    public long getTotalLogged() {
        return totalLogged.get();
    }

    public String getLastFileError() {
        return lastFileError;
    }

    public boolean toggleAlerts(UUID uuid) {
        if (alertsMuted.remove(uuid)) {
            return true;
        }
        alertsMuted.add(uuid);
        return false;
    }

    public boolean areAlertsEnabled(UUID uuid) {
        return !alertsMuted.contains(uuid);
    }

    /**
     * Bir tespiti kaydeder (konsol + dosya + yetkili uyarisi).
     *
     * @param action LOG, BUFFER, LAG, GRACE, TEST, NEED-EVIDENCE, KICK, BAN gibi sonuc etiketi
     */
    public void record(Player player, PlayerCheckProfile profile, PlayerCheckProfile.Category category,
                       String check, String detail, String action) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> record(player, profile, category, check, detail, action));
            }
            return;
        }
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        long now = System.currentTimeMillis();
        double totalVl = profile.getMathVl() + profile.getHeuristicVl();
        int ping = Math.max(0, player.getPing());

        Entry entry = new Entry(now, player.getName(), category.name(), check, detail, totalVl, ping, action);
        remember(entry, cfg.getInt("logging.keep-in-memory", 800));
        totalLogged.incrementAndGet();

        String plain = "[" + category.name() + "] " + player.getName() + " » " + check + " (" + detail + ") VL="
                + String.format(Locale.ROOT, "%.2f", totalVl) + " ping=" + ping + "ms [" + action + "]";

        if (cfg.getBoolean("logging.console", true)) {
            plugin.getLogger().info(plain);
        }
        if (cfg.getBoolean("logging.file", true)) {
            writeFile(entry);
        }
        boolean softHidden = "BUFFER".equals(action) && !cfg.getBoolean("logging.alert-soft", true);
        if (cfg.getBoolean("logging.staff-alerts", true) && !softHidden) {
            sendStaffAlert(player, entry, cfg.getLong("logging.alert-cooldown-ms", 800L));
        }
    }

    /** Sadece dosya + bellek (+ istege bagli konsol): VL'ye etkisi olmayan bilgi satirlari. */
    public void logInfo(Player player, String check, String detail) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> logInfo(player, check, detail));
            }
            return;
        }
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        PlayerCheckProfile profile = plugin.getProfileIfPresent(player.getUniqueId());
        double totalVl = profile != null ? profile.getMathVl() + profile.getHeuristicVl() : 0.0;
        Entry entry = new Entry(System.currentTimeMillis(), player.getName(), "INFO", check, detail,
                totalVl, Math.max(0, player.getPing()), "INFO");
        remember(entry, cfg.getInt("logging.keep-in-memory", 800));
        totalLogged.incrementAndGet();
        if (cfg.getBoolean("logging.console-info", false)) {
            plugin.getLogger().info("[INFO] " + player.getName() + " » " + check + " (" + detail + ")");
        }
        if (cfg.getBoolean("logging.file", true)) {
            writeFile(entry);
        }
    }

    /** Thread-guvenli, Player nesnesi gerektirmeyen kayit (paket thread'inden, oyuncu atilmadan once). */
    public void logRaw(String playerName, String category, String check, String detail, String action, int ping) {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        Entry entry = new Entry(System.currentTimeMillis(), playerName, category, check, detail, 0.0, ping, action);
        remember(entry, cfg.getInt("logging.keep-in-memory", 800));
        totalLogged.incrementAndGet();
        if (cfg.getBoolean("logging.console", true)) {
            plugin.getLogger().info("[" + category + "] " + playerName + " » " + check + " (" + detail + ") [" + action + "]");
        }
        if (cfg.getBoolean("logging.file", true)) {
            writeFile(entry);
        }
    }

    public List<Entry> recentFor(String playerName, int limit) {
        List<Entry> out = new ArrayList<>();
        for (var it = recent.descendingIterator(); it.hasNext() && out.size() < limit; ) {
            Entry e = it.next();
            if (playerName == null || e.player().equalsIgnoreCase(playerName)) {
                out.add(e);
            }
        }
        return out;
    }

    public void shutdown() {
        io.shutdown();
        try {
            io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void remember(Entry entry, int keep) {
        recent.addLast(entry);
        int max = Math.max(50, keep);
        while (recent.size() > max) {
            recent.pollFirst();
        }
    }

    private void writeFile(Entry e) {
        String line = LocalDateTime.now().format(TS) + " | " + e.player() + " | " + e.category() + " | " + e.check()
                + " | " + e.detail() + " | VL=" + String.format(Locale.ROOT, "%.2f", e.totalVl())
                + " | ping=" + e.ping() + "ms | " + e.action() + System.lineSeparator();
        File file = new File(logDir, "violations-" + LocalDateTime.now().format(DAY) + ".log");
        try {
            io.execute(() -> {
                try {
                    Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    lastFileError = ex.getMessage();
                }
            });
        } catch (RuntimeException ex) {
            lastFileError = String.valueOf(ex.getMessage());
        }
    }

    private void sendStaffAlert(Player flagged, Entry e, long cooldownMs) {
        String key = flagged.getUniqueId() + ":" + e.check();
        long last = lastAlertByKey.getOrDefault(key, 0L);
        if ((e.timeMs() - last) < cooldownMs) {
            return;
        }
        lastAlertByKey.put(key, e.timeMs());
        if (lastAlertByKey.size() > 2000) {
            lastAlertByKey.clear();
        }

        String actionTag = switch (e.action()) {
            case "KICK" -> " §4[KICK]";
            case "BAN" -> " §4[BAN]";
            case "LOG" -> "";
            default -> " §8[" + e.action() + "]";
        };
        String msg = "§d[ZAG] §f" + e.player() + " §8» §e" + e.check() + " §8(§7" + e.detail() + "§8) §7VL§8: §c"
                + String.format(Locale.ROOT, "%.1f", e.totalVl()) + " §7Ping§8: §a" + e.ping() + "ms" + actionTag;
        var component = LEGACY.deserialize(msg);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (alertsMuted.contains(staff.getUniqueId())) {
                continue;
            }
            if (staff.hasPermission("zornoaimguard.alerts") || staff.hasPermission("zornoaimguard.admin")) {
                staff.sendMessage(component);
            }
        }
    }
}
