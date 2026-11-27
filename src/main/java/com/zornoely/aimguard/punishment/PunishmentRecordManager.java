package com.zornoely.aimguard.punishment;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.profile.PlayerCheckProfile.Category;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PunishmentRecordManager {

    public record BanEntry(
            String playerName,
            UUID uuid,
            String reason,
            String bannedBy,
            long bannedAtMs,
            long expiresAtMs,
            boolean active
    ) {}

    /** SOFT: yalnizca log/uyari. NORMAL: kanit sayilir. HARD: kesin (imkansiz) kanit. */
    public enum Severity { SOFT, NORMAL, HARD }

    private static final int CONFIG_VERSION = 3;

    private final ZornoAimGuardPlugin plugin;
    private final File punishmentsFile;
    private final File bansFile;
    private final Map<UUID, Long> lastPunishmentByPlayer = new ConcurrentHashMap<>();

    private FileConfiguration punishmentsConfig;
    private FileConfiguration bansConfig;

    public PunishmentRecordManager(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Eklenti veri klasoru olusturulamadi: " + plugin.getDataFolder());
        }
        this.punishmentsFile = new File(plugin.getDataFolder(), "punishments.yml");
        this.bansFile = new File(plugin.getDataFolder(), "bans.yml");
        reloadConfigs();
    }

    public synchronized void reloadConfigs() {
        if (punishmentsFile.exists()) {
            int oldVersion = YamlConfiguration.loadConfiguration(punishmentsFile).getInt("config-version", 0);
            if (oldVersion < CONFIG_VERSION) {
                // Eski surumdeki agresif esikler (ornegin duvar vurusu VL=2.05) yeni mantikla uyumsuz: yedekle ve yenile.
                File backup = new File(plugin.getDataFolder(), "punishments.yml.eski-v" + oldVersion);
                if (backup.exists() && !backup.delete()) {
                    plugin.getLogger().warning("Eski yedek silinemedi: " + backup.getName());
                }
                if (punishmentsFile.renameTo(backup)) {
                    plugin.getLogger().info("Eski punishments.yml yedeklendi (" + backup.getName()
                            + "), yeni v" + CONFIG_VERSION + " ayarlari olusturuluyor.");
                }
            }
        }
        if (!punishmentsFile.exists()) {
            plugin.saveResource("punishments.yml", false);
        }
        this.punishmentsConfig = YamlConfiguration.loadConfiguration(punishmentsFile);

        if (!bansFile.exists()) {
            try {
                if (!bansFile.createNewFile()) {
                    plugin.getLogger().warning("bans.yml dosyasi olusturulamadi.");
                }
            } catch (IOException ex) {
                plugin.getLogger().severe("bans.yml olusturulurken hata: " + ex.getMessage());
            }
        }
        this.bansConfig = YamlConfiguration.loadConfiguration(bansFile);
        if (!bansConfig.isConfigurationSection("bans")) {
            bansConfig.createSection("bans");
        }
        if (!bansConfig.isConfigurationSection("history")) {
            bansConfig.createSection("history");
        }
        saveBansFile();
    }

    public FileConfiguration getPunishmentsConfig() {
        return punishmentsConfig;
    }

    public synchronized void saveBansFile() {
        try {
            bansConfig.save(bansFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("bans.yml kaydedilirken hata: " + ex.getMessage());
        }
    }

    public synchronized BanEntry getActiveBan(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        ConfigurationSection bansSec = bansConfig.getConfigurationSection("bans");
        if (bansSec == null) {
            return null;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        ConfigurationSection entry = bansSec.getConfigurationSection(key);
        if (entry == null || !entry.getBoolean("active", false)) {
            return null;
        }
        long expiresAt = entry.getLong("expires-at-ms", -1L);
        long now = System.currentTimeMillis();
        if (expiresAt > 0L && now >= expiresAt) {
            entry.set("active", false);
            entry.set("unbanned-at-ms", now);
            entry.set("unbanned-by", "SYSTEM_EXPIRED");
            saveBansFile();
            return null;
        }

        UUID uuid = null;
        String uuidRaw = entry.getString("uuid", "");
        if (!uuidRaw.isBlank()) {
            try {
                uuid = UUID.fromString(uuidRaw);
            } catch (IllegalArgumentException ignored) {
            }
        }

        return new BanEntry(
                entry.getString("player-name", playerName),
                uuid,
                entry.getString("reason", "Hile tespit edildi"),
                entry.getString("banned-by", "ZornoAimGuard"),
                entry.getLong("banned-at-ms", now),
                expiresAt,
                true
        );
    }

    public synchronized void banPlayer(String playerName, UUID uuid, String reason, String bannedBy, long durationDays) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = (durationDays > 0L) ? now + (durationDays * 86_400_000L) : -1L;
        String key = playerName.toLowerCase(Locale.ROOT);

        ConfigurationSection bansSec = bansConfig.getConfigurationSection("bans");
        if (bansSec == null) {
            bansSec = bansConfig.createSection("bans");
        }
        ConfigurationSection entry = bansSec.createSection(key);
        entry.set("player-name", playerName);
        entry.set("uuid", uuid != null ? uuid.toString() : "");
        entry.set("reason", reason);
        entry.set("banned-by", bannedBy);
        entry.set("banned-at-ms", now);
        entry.set("expires-at-ms", expiresAt);
        entry.set("active", true);

        appendHistoryRecord(playerName, uuid, "BAN", reason, bannedBy, now);
        saveBansFile();

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> online.kick(
                    Component.text("§c§lZornoAimGuard §8» §7Sunucudan banlandiniz.\n§7Sebep: §f" + reason)
            ));
        }
    }

    public synchronized boolean unbanPlayer(String playerName, String unbannedBy) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        ConfigurationSection bansSec = bansConfig.getConfigurationSection("bans");
        if (bansSec == null) {
            return false;
        }
        ConfigurationSection entry = bansSec.getConfigurationSection(key);
        if (entry == null || !entry.getBoolean("active", false)) {
            return false;
        }
        long now = System.currentTimeMillis();
        entry.set("active", false);
        entry.set("unbanned-at-ms", now);
        entry.set("unbanned-by", unbannedBy);

        UUID uuid = null;
        String uuidRaw = entry.getString("uuid", "");
        if (!uuidRaw.isBlank()) {
            try {
                uuid = UUID.fromString(uuidRaw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        appendHistoryRecord(entry.getString("player-name", playerName), uuid, "UNBAN", "Ban kaldirildi", unbannedBy, now);
        saveBansFile();
        return true;
    }

    public synchronized int recordKick(Player player, String reason) {
        String key = player.getName().toLowerCase(Locale.ROOT);
        ConfigurationSection histSec = bansConfig.getConfigurationSection("history");
        if (histSec == null) {
            histSec = bansConfig.createSection("history");
        }
        ConfigurationSection playerHist = histSec.getConfigurationSection(key);
        if (playerHist == null) {
            playerHist = histSec.createSection(key);
        }
        int kicks = playerHist.getInt("kick-count", 0) + 1;
        playerHist.set("player-name", player.getName());
        playerHist.set("uuid", player.getUniqueId().toString());
        playerHist.set("kick-count", kicks);
        playerHist.set("last-kick-reason", reason);
        playerHist.set("last-kick-at-ms", System.currentTimeMillis());

        appendHistoryRecord(player.getName(), player.getUniqueId(), "KICK", reason, "ZornoAimGuard", System.currentTimeMillis());
        saveBansFile();
        return kicks;
    }

    public synchronized int getKickCount(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return 0;
        }
        ConfigurationSection histSec = bansConfig.getConfigurationSection("history");
        if (histSec == null) {
            return 0;
        }
        ConfigurationSection playerHist = histSec.getConfigurationSection(playerName.toLowerCase(Locale.ROOT));
        return playerHist != null ? playerHist.getInt("kick-count", 0) : 0;
    }

    public synchronized List<String> getKnownPlayerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        ConfigurationSection bansSec = bansConfig.getConfigurationSection("bans");
        if (bansSec != null) {
            for (String k : bansSec.getKeys(false)) {
                ConfigurationSection s = bansSec.getConfigurationSection(k);
                if (s != null && s.getString("player-name") != null) {
                    names.add(s.getString("player-name"));
                }
            }
        }
        ConfigurationSection histSec = bansConfig.getConfigurationSection("history");
        if (histSec != null) {
            for (String k : histSec.getKeys(false)) {
                ConfigurationSection s = histSec.getConfigurationSection(k);
                if (s != null && s.getString("player-name") != null) {
                    names.add(s.getString("player-name"));
                }
            }
        }
        return new ArrayList<>(names);
    }

    private void appendHistoryRecord(String playerName, UUID uuid, String action, String reason, String actor, long timestampMs) {
        String key = playerName.toLowerCase(Locale.ROOT);
        ConfigurationSection histSec = bansConfig.getConfigurationSection("history");
        if (histSec == null) {
            histSec = bansConfig.createSection("history");
        }
        ConfigurationSection playerHist = histSec.getConfigurationSection(key);
        if (playerHist == null) {
            playerHist = histSec.createSection(key);
        }
        List<String> entries = playerHist.getStringList("events");
        entries.add(timestampMs + " | " + action + " | by=" + actor + " | uuid=" + (uuid != null ? uuid : "-") + " | " + reason);
        playerHist.set("events", entries);
    }

    /**
     * Bir tespiti isler: HER ZAMAN loglar (konsol + dosya + yetkili uyarisi), sonra ceza gerekip gerekmedigine karar verir.
     * Ceza kurallari: test modu / giris-isinlanma toleransi / lag durumunda ceza yok; kick/ban icin ayrica
     * "yeterli kanit" (en az 2 farkli savas kategorisi veya sert kanit) aranir. VL'yi cagiran taraf onceden ekler.
     */
    public void flag(Player player, PlayerCheckProfile profile, long nowMs, Category category,
                     String check, String detail, Severity severity) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (plugin.isPlayerBypassed(player)) {
            return;
        }

        boolean testOnly = plugin.isTestLogOnly(player.getUniqueId());
        boolean focused = plugin.isPlayerFocused(player.getUniqueId(), nowMs);
        if (severity != Severity.SOFT) {
            profile.recordFlag(category, nowMs, severity == Severity.HARD);
        }

        double totalVl = profile.getMathVl() + profile.getHeuristicVl();
        boolean hard = severity == Severity.HARD;
        String action = "LOG";

        if (severity == Severity.SOFT) {
            action = "BUFFER";
        } else if (testOnly) {
            action = "TEST";
        } else if (profile.inGrace(nowMs)) {
            action = "GRACE";
        } else if (!hard && profile.isLagging()) {
            action = "LAG";
        } else {
            long windowMs = punishmentsConfig.getLong("punishments.evidence-window-seconds", 90L) * 1000L;
            boolean requireEvidence = punishmentsConfig.getBoolean("punishments.require-evidence", true);
            boolean hardRecent = (nowMs - profile.getLastHardEvidenceMs()) <= Math.max(windowMs, 120_000L)
                    && profile.getLastHardEvidenceMs() > 0L;
            int categories = profile.distinctCombatCategories(nowMs, windowMs);
            boolean evidenceOk = !requireEvidence || hardRecent || categories >= 2;

            long lastPunishmentMs = lastPunishmentByPlayer.getOrDefault(player.getUniqueId(), 0L);
            long cooldown = punishmentsConfig.getLong("punishments.punish-cooldown-ms", 2500L);

            double kickAtVl = punishmentsConfig.getDouble("punishments.kick-at-vl", 12.0);
            double banAtVl = punishmentsConfig.getDouble("punishments.ban-at-vl", 20.0);
            boolean escalate = punishmentsConfig.getBoolean("punishments.escalate-kick-to-ban", true);
            int maxKicksBeforeBan = punishmentsConfig.getInt("punishments.max-kicks-before-ban", 2);
            long banDays = punishmentsConfig.getLong("punishments.ban-duration-days", 7L);

            if (totalVl >= kickAtVl && !evidenceOk) {
                action = "NEED-EVIDENCE";
            } else if (totalVl >= kickAtVl && (nowMs - lastPunishmentMs) >= cooldown) {
                lastPunishmentByPlayer.put(player.getUniqueId(), nowMs);
                String reasonDetail = check + ": " + detail;
                if (totalVl >= banAtVl) {
                    action = "BAN";
                    banPlayer(player.getName(), player.getUniqueId(), "Hile tespiti (" + reasonDetail + ")",
                            "ZornoAimGuard", banDays);
                } else {
                    int kicks = recordKick(player, reasonDetail);
                    if (escalate && kicks >= maxKicksBeforeBan) {
                        action = "BAN";
                        banPlayer(player.getName(), player.getUniqueId(),
                                "Tekrarlanan hile tespiti (" + kicks + " kick)", "ZornoAimGuard", banDays);
                    } else {
                        action = "KICK";
                        Bukkit.getScheduler().runTask(plugin, () -> player.kick(
                                Component.text("§c§lZornoAimGuard §8» §7Supheli vurus/hareket tespiti (" + check + ").")
                        ));
                    }
                }
            }
        }

        plugin.getViolationLogger().record(player, profile, category, check, detail, action);
        if (focused) {
            plugin.notifyFocusTelemetry(player, profile, check + " " + detail);
        }
    }
}
