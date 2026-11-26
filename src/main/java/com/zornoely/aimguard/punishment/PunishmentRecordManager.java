package com.zornoely.aimguard.punishment;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PunishmentRecordManager {

    public record BanEntry(
            String playerName,
            UUID playerId,
            String reason,
            String source,
            long bannedAtMs,
            long expiresAtMs,
            boolean active
    ) {}

    private final ZornoAimGuardPlugin plugin;
    private final File punishmentsFile;
    private final File bansFile;
    private final File historyLogFile;

    private FileConfiguration punishmentsConfig;
    private FileConfiguration bansConfig;

    private final Map<String, BanEntry> bansByLowerName = new ConcurrentHashMap<>();
    private final Map<String, Integer> kickCountByLowerName = new ConcurrentHashMap<>();

    public PunishmentRecordManager(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.punishmentsFile = new File(plugin.getDataFolder(), "punishments.yml");
        this.bansFile = new File(plugin.getDataFolder(), "bans.yml");
        this.historyLogFile = new File(plugin.getDataFolder(), "punishment-history.log");

        reloadConfigs();
        loadPersistedBans();
    }

    public void reloadConfigs() {
        if (!punishmentsFile.exists()) {
            plugin.saveResource("punishments.yml", false);
        }
        this.punishmentsConfig = YamlConfiguration.loadConfiguration(punishmentsFile);

        if (!bansFile.exists()) {
            try {
                bansFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("bans.yml olusturulamadi: " + e.getMessage());
            }
        }
        this.bansConfig = YamlConfiguration.loadConfiguration(bansFile);
    }

    private void loadPersistedBans() {
        bansByLowerName.clear();
        kickCountByLowerName.clear();

        if (bansConfig.isConfigurationSection("bans")) {
            for (String key : bansConfig.getConfigurationSection("bans").getKeys(false)) {
                String path = "bans." + key;
                String name = bansConfig.getString(path + ".name", key);
                String uuidStr = bansConfig.getString(path + ".uuid", null);
                UUID uuid = null;
                if (uuidStr != null) {
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                String reason = bansConfig.getString(path + ".reason", "Anti-Cheat");
                String source = bansConfig.getString(path + ".source", "ZornoAimGuard");
                long bannedAt = bansConfig.getLong(path + ".bannedAt", 0L);
                long expiresAt = bansConfig.getLong(path + ".expiresAt", -1L);
                boolean active = bansConfig.getBoolean(path + ".active", true);

                bansByLowerName.put(name.toLowerCase(Locale.ROOT),
                        new BanEntry(name, uuid, reason, source, bannedAt, expiresAt, active));
            }
        }

        if (bansConfig.isConfigurationSection("kicks")) {
            for (String key : bansConfig.getConfigurationSection("kicks").getKeys(false)) {
                kickCountByLowerName.put(key.toLowerCase(Locale.ROOT), bansConfig.getInt("kicks." + key, 0));
            }
        }
    }

    private synchronized void savePersistedData() {
        bansConfig.set("bans", null);
        for (Map.Entry<String, BanEntry> entry : bansByLowerName.entrySet()) {
            BanEntry b = entry.getValue();
            String path = "bans." + entry.getKey();
            bansConfig.set(path + ".name", b.playerName());
            bansConfig.set(path + ".uuid", b.playerId() != null ? b.playerId().toString() : null);
            bansConfig.set(path + ".reason", b.reason());
            bansConfig.set(path + ".source", b.source());
            bansConfig.set(path + ".bannedAt", b.bannedAtMs());
            bansConfig.set(path + ".expiresAt", b.expiresAtMs());
            bansConfig.set(path + ".active", b.active());
        }

        bansConfig.set("kicks", null);
        for (Map.Entry<String, Integer> entry : kickCountByLowerName.entrySet()) {
            bansConfig.set("kicks." + entry.getKey(), entry.getValue());
        }

        try {
            bansConfig.save(bansFile);
        } catch (IOException e) {
            plugin.getLogger().warning("bans.yml kaydedilemedi: " + e.getMessage());
        }
    }

    public synchronized void appendPermanentLog(String line) {
        try (PrintWriter out = new PrintWriter(new FileWriter(historyLogFile, StandardCharsets.UTF_8, true))) {
            out.println("[" + Instant.now() + "] " + line);
        } catch (IOException e) {
            plugin.getLogger().warning("punishment-history.log yazilamadi: " + e.getMessage());
        }
    }

    public BanEntry getActiveBan(String playerName) {
        if (playerName == null) {
            return null;
        }
        BanEntry entry = bansByLowerName.get(playerName.toLowerCase(Locale.ROOT));
        if (entry == null || !entry.active()) {
            return null;
        }
        if (entry.expiresAtMs() > 0L && System.currentTimeMillis() > entry.expiresAtMs()) {
            BanEntry expired = new BanEntry(
                    entry.playerName(), entry.playerId(), entry.reason(), entry.source(),
                    entry.bannedAtMs(), entry.expiresAtMs(), false
            );
            bansByLowerName.put(playerName.toLowerCase(Locale.ROOT), expired);
            savePersistedData();
            return null;
        }
        return entry;
    }

    public boolean banPlayer(String playerName, UUID playerId, String reason, String source, long durationDays) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expiresAt = durationDays > 0
                ? now + (durationDays * 24L * 60L * 60L * 1000L)
                : -1L;

        String cleanReason = (reason == null || reason.isBlank()) ? "Supheli hareket/aim davranisi" : reason;
        BanEntry entry = new BanEntry(playerName, playerId, cleanReason, source, now, expiresAt, true);
        bansByLowerName.put(playerName.toLowerCase(Locale.ROOT), entry);
        savePersistedData();
        appendPermanentLog("BAN_ADD | player=" + playerName + " | source=" + source + " | expires=" + expiresAt + " | reason=" + cleanReason);

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    online.kick(Component.text("§c§lZornoAimGuard §8» §7Banlandiniz: §f" + cleanReason)));
        }
        return true;
    }

    public boolean unbanPlayer(String playerName, String source) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        BanEntry current = bansByLowerName.get(key);
        if (current == null || !current.active()) {
            return false;
        }
        // Kayit silinmez, active=false yapilarak kalici gecmiste tutulur
        BanEntry updated = new BanEntry(
                current.playerName(), current.playerId(), current.reason(), current.source(),
                current.bannedAtMs(), current.expiresAtMs(), false
        );
        bansByLowerName.put(key, updated);
        savePersistedData();
        appendPermanentLog("BAN_REMOVE | player=" + current.playerName() + " | source=" + source);
        return true;
    }

    public int incrementKickCount(String playerName) {
        String key = playerName.toLowerCase(Locale.ROOT);
        int next = kickCountByLowerName.getOrDefault(key, 0) + 1;
        kickCountByLowerName.put(key, next);
        savePersistedData();
        return next;
    }

    public int getKickCount(String playerName) {
        return kickCountByLowerName.getOrDefault(playerName.toLowerCase(Locale.ROOT), 0);
    }

    public List<String> getKnownPlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        for (BanEntry b : bansByLowerName.values()) {
            if (!names.contains(b.playerName())) {
                names.add(b.playerName());
            }
        }
        return names;
    }

    public void evaluateAndPunish(Player player, PlayerCheckProfile profile, long nowMs, String reason) {
        if (profile.isLagging()) {
            return;
        }

        double kickAtVl = punishmentsConfig.getDouble("punishments.kick-at-vl", 7.5);
        double banAtVl = punishmentsConfig.getDouble("punishments.ban-at-vl", 12.0);
        boolean escalate = punishmentsConfig.getBoolean("punishments.escalate-kick-to-ban", true);
        int maxKicks = punishmentsConfig.getInt("punishments.max-kicks-before-ban", 2);
        long banDays = punishmentsConfig.getLong("punishments.ban-duration-days", 7L);

        double totalVl = profile.getMathVl() + profile.getHeuristicVl();

        // Once Silent Mitigation ile haksiz avantaji sinirla
        if (totalVl >= 3.5 || profile.getCombatCheatScore() >= 0.55 || profile.getDecoyLockScore() >= 0.55) {
            profile.activateMitigation(7000L);
        }

        boolean strongCrossCheck = profile.getCombatCheatScore() >= punishmentsConfig.getDouble("killaura.score-threshold", 0.72)
                && (profile.getDecoyLockScore() >= 0.55 || profile.getRotationSamples().size() >= 36);

        int kicksSoFar = getKickCount(player.getName());

        if (totalVl >= banAtVl || (escalate && kicksSoFar >= maxKicks && totalVl >= kickAtVl)) {
            banPlayer(player.getName(), player.getUniqueId(), reason, "ZornoAimGuard-Auto", banDays);
            profile.resetAll();
            plugin.getDecoyManager().removeDecoy(player.getUniqueId());
            return;
        }

        if ((totalVl >= kickAtVl || strongCrossCheck) && (nowMs - profile.getLastKickTimeMs()) > 15000L) {
            int newKickCount = incrementKickCount(player.getName());
            profile.setLastKickTimeMs(nowMs);
            appendPermanentLog("KICK | player=" + player.getName() + " | count=" + newKickCount + " | vl=" + String.format("%.2f", totalVl) + " | reason=" + reason);

            String kickMsg = "§c§lZornoAimGuard §8» §7Uyari/Kick (" + newKickCount + "/" + maxKicks + "): §f" + reason;
            Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(kickMsg)));
        }
    }

    public FileConfiguration getPunishmentsConfig() {
        return punishmentsConfig;
    }
}
