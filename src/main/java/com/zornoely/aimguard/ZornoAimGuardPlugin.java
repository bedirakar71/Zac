package com.zornoely.aimguard;

import com.zornoely.aimguard.combat.CombatAnalyzer;
import com.zornoely.aimguard.decoy.DecoyNpcManager;
import com.zornoely.aimguard.lag.LagCompensationManager;
import com.zornoely.aimguard.log.ViolationLogger;
import com.zornoely.aimguard.mitigation.PunishmentEngine;
import com.zornoely.aimguard.movement.MovementSimulationChecker;
import com.zornoely.aimguard.packet.PacketEventsBridge;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.punishment.PunishmentRecordManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ZornoAimGuardPlugin extends JavaPlugin implements Listener, TabCompleter {

    private record FocusSession(UUID targetId, UUID watcherId, long expiresAtMs, long lastReportMs) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Map<UUID, PlayerCheckProfile> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> testLogOnlyPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> manualBypassPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, FocusSession> focusByTarget = new ConcurrentHashMap<>();

    private PunishmentRecordManager punishmentManager;
    private ViolationLogger violationLogger;
    private LagCompensationManager lagManager;
    private MovementSimulationChecker movementChecker;
    private CombatAnalyzer combatAnalyzer;
    private DecoyNpcManager decoyManager;
    private PunishmentEngine punishmentEngine;
    private PacketEventsBridge packetEventsBridge;

    @Override
    public void onLoad() {
        this.packetEventsBridge = new PacketEventsBridge(this);
        this.packetEventsBridge.load();
    }

    @Override
    public void onEnable() {
        this.punishmentManager = new PunishmentRecordManager(this);
        this.violationLogger = new ViolationLogger(this);
        this.lagManager = new LagCompensationManager();
        this.movementChecker = new MovementSimulationChecker(this);
        this.combatAnalyzer = new CombatAnalyzer(this);
        this.decoyManager = new DecoyNpcManager(this);
        this.punishmentEngine = new PunishmentEngine(this);
        if (this.packetEventsBridge == null) {
            this.packetEventsBridge = new PacketEventsBridge(this);
            this.packetEventsBridge.load();
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("zornoaimguard") != null) {
            getCommand("zornoaimguard").setTabCompleter(this);
        }

        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            PlayerCheckProfile profile = getOrCreateProfile(online);
            profile.setBedrock(isBedrockName(online.getName()));
            profile.grace(now + 3000L);
        }

        boolean hooked = packetEventsBridge.hook();

        Bukkit.getScheduler().runTaskTimer(this, this::tickEveryServerTick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickEverySecond, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> decoyManager.tickActiveDecoys(), 5L, 5L);

        getLogger().info("ZornoAimGuard v" + getPluginMeta().getVersion() + " baslatildi. PacketEvents: "
                + (hooked ? "AKTIF" : "KAPALI (Bukkit yedek modu)")
                + " | Loglar: " + violationLogger.getLogDir().getPath()
                + " | OP otomatik bypass: kapali.");
    }

    @Override
    public void onDisable() {
        if (packetEventsBridge != null) {
            packetEventsBridge.unhook();
        }
        if (decoyManager != null) {
            decoyManager.clearAll();
        }
        if (violationLogger != null) {
            violationLogger.shutdown();
        }
        focusByTarget.clear();
        testLogOnlyPlayers.clear();
        manualBypassPlayers.clear();
        profiles.clear();
        if (lagManager != null) {
            lagManager.clear();
        }
    }

    // ===================================================================== ZAMANLAYICILAR

    /** Her tick: konum gecmisi, sunucu tick izleme ve ertelenmis vurus analizi. */
    private void tickEveryServerTick() {
        long now = System.currentTimeMillis();
        lagManager.onServerTick(now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            lagManager.recordTick(player, now);
            PlayerCheckProfile profile = profiles.get(player.getUniqueId());
            if (profile != null && profile.hasPendingHits()) {
                combatAnalyzer.processPending(player, profile, now);
            }
        }
    }

    /** Her saniye: ping/lag durumu, VL azalmasi, otomatik tuzak, analiz oturumlari, bayat profil temizligi. */
    private void tickEverySecond() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerCheckProfile profile = getOrCreateProfile(player);
            boolean bypassed = isPlayerBypassed(player);
            profile.setBypassCached(bypassed);
            if (bypassed) {
                continue;
            }
            lagManager.recordSample(player, profile, now);
            punishmentEngine.applyDecay(profile, now);
            decoyManager.evaluateAutoDecoy(player, profile, now);
            tickFocusSession(player, profile, now);
        }
        profiles.entrySet().removeIf(e -> {
            boolean stale = Bukkit.getPlayer(e.getKey()) == null && (now - e.getValue().getCreatedMs()) > 120_000L;
            if (stale) {
                packetEventsBridge.clearPlayer(e.getKey());
            }
            return stale;
        });
    }

    // ===================================================================== ERISIM YARDIMCILARI

    public boolean isPlayerBypassed(Player player) {
        if (player == null) {
            return true;
        }
        if (manualBypassPlayers.contains(player.getUniqueId())) {
            return true;
        }
        boolean permBypassAllowed = punishmentManager != null
                && punishmentManager.getPunishmentsConfig().getBoolean("bypass.allow-permission-bypass", false);
        return permBypassAllowed && player.hasPermission("zornoaimguard.bypass");
    }

    /** Paket thread'i icin guvenli (izin kontrolu onbellekten). */
    public boolean isUuidBypassed(UUID uuid) {
        if (uuid == null) {
            return true;
        }
        if (manualBypassPlayers.contains(uuid)) {
            return true;
        }
        PlayerCheckProfile p = profiles.get(uuid);
        return p != null && p.isBypassCached();
    }

    /** Floodgate/Geyser (Bedrock) oyunculari: dokunmatik/kumanda nisan yardimi dogal oldugundan nisan sezgisinden muaf. */
    public boolean isBedrockName(String name) {
        if (name == null || punishmentManager == null) {
            return false;
        }
        FileConfiguration cfg = punishmentManager.getPunishmentsConfig();
        List<String> prefixes = cfg.isList("bypass.name-prefixes") ? cfg.getStringList("bypass.name-prefixes") : List.of(".");
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public PlayerCheckProfile getOrCreateProfile(Player player) {
        return getOrCreateProfile(player.getUniqueId());
    }

    public PlayerCheckProfile getOrCreateProfile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, PlayerCheckProfile::new);
    }

    public PlayerCheckProfile getProfileIfPresent(UUID uuid) {
        return profiles.get(uuid);
    }

    public boolean isTestLogOnly(UUID uuid) {
        return uuid != null && testLogOnlyPlayers.contains(uuid);
    }

    public boolean isPlayerFocused(UUID uuid, long nowMs) {
        if (uuid == null) {
            return false;
        }
        FocusSession session = focusByTarget.get(uuid);
        return session != null && nowMs < session.expiresAtMs();
    }

    public void notifyFocusTelemetry(Player target, PlayerCheckProfile profile, String detail) {
        long now = System.currentTimeMillis();
        FocusSession session = focusByTarget.get(target.getUniqueId());
        if (session == null || now >= session.expiresAtMs()) {
            return;
        }
        if ((now - session.lastReportMs()) < 900L) {
            return;
        }
        focusByTarget.put(target.getUniqueId(), new FocusSession(
                session.targetId(), session.watcherId(), session.expiresAtMs(), now));

        String msg = "§d[Analiz] §f" + target.getName() + " §8» §e" + detail
                + " §7(MathVL=" + String.format(Locale.ROOT, "%.2f", profile.getMathVl())
                + ", AimVL=" + String.format(Locale.ROOT, "%.2f", profile.getHeuristicVl()) + ")";
        sendFocusMessage(session.watcherId(), msg);
    }

    private void tickFocusSession(Player player, PlayerCheckProfile profile, long nowMs) {
        FocusSession session = focusByTarget.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (nowMs >= session.expiresAtMs()) {
            focusByTarget.remove(player.getUniqueId());
            sendFocusMessage(session.watcherId(), buildFocusSummary(player.getName(), profile, "Süre doldu"));
        }
    }

    private String buildFocusSummary(String playerName, PlayerCheckProfile profile, String reason) {
        return "§d[ZornoAimGuard Analiz Ozeti - " + playerName + "] §7(" + reason + ") §8» "
                + "§fMathVL: §e" + String.format(Locale.ROOT, "%.2f", profile.getMathVl())
                + " §7| §fAimVL: §e" + String.format(Locale.ROOT, "%.2f", profile.getHeuristicVl())
                + " §7| §fSkor: §c" + String.format(Locale.ROOT, "%.1f%%", profile.getCombatCheatScore() * 100.0)
                + " §7| §fTuzak: §c" + String.format(Locale.ROOT, "%.1f%%", profile.getDecoyLockScore() * 100.0);
    }

    private void sendFocusMessage(UUID watcherId, String message) {
        getLogger().info(message.replaceAll("§[0-9a-fk-or]", ""));
        if (watcherId != null) {
            Player watcher = Bukkit.getPlayer(watcherId);
            if (watcher != null && watcher.isOnline()) {
                watcher.sendMessage(Component.text(message));
            }
        }
    }

    public PunishmentRecordManager getPunishmentManager() {
        return punishmentManager;
    }

    public ViolationLogger getViolationLogger() {
        return violationLogger;
    }

    public LagCompensationManager getLagManager() {
        return lagManager;
    }

    public PunishmentEngine getPunishmentEngine() {
        return punishmentEngine;
    }

    public DecoyNpcManager getDecoyManager() {
        return decoyManager;
    }

    public PacketEventsBridge getPacketEventsBridge() {
        return packetEventsBridge;
    }

    // ===================================================================== OLAYLAR

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        PunishmentRecordManager.BanEntry active = punishmentManager.getActiveBan(event.getName());
        if (active != null) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text("§c§lZornoAimGuard §8» §7Sunucudan banlandiniz.\n§7Sebep: §f" + active.reason())
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        PlayerCheckProfile profile = getOrCreateProfile(player);
        profile.resetAll();
        profile.setBedrock(isBedrockName(player.getName()));
        profile.grace(now + punishmentManager.getPunishmentsConfig().getLong("grace.join-ms", 4000L));
        lagManager.recordSample(player, profile, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        decoyManager.removeDecoy(id);
        packetEventsBridge.clearPlayer(id);
        movementChecker.clearPlayer(id);
        lagManager.remove(id);
        focusByTarget.remove(id);
        testLogOnlyPlayers.remove(id);
        manualBypassPlayers.remove(id);
        profiles.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        graceFor(event.getPlayer(), "grace.teleport-ms", 1500L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        graceFor(event.getPlayer(), "grace.respawn-ms", 2000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        graceFor(event.getPlayer(), "grace.world-change-ms", 2000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        graceFor(event.getPlayer(), "grace.teleport-ms", 1500L);
    }

    private void graceFor(Player player, String path, long defaultMs) {
        PlayerCheckProfile profile = getOrCreateProfile(player);
        profile.grace(System.currentTimeMillis() + punishmentManager.getPunishmentsConfig().getLong(path, defaultMs));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerCheckProfile profile = getOrCreateProfile(event.getPlayer());
        long now = System.currentTimeMillis();
        profile.setLastVelocityMs(now);
        profile.setLastKnockbackTimeMs(now);
    }

    /** Hasar alan oyuncuda sonraki kisa surede konum/menzil sapmasi dogaldir (geri tepme). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        PlayerCheckProfile profile = getOrCreateProfile(victim);
        long now = System.currentTimeMillis();
        profile.setLastDamagedMs(now);
        profile.setLastKnockbackTimeMs(now);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isPlayerBypassed(player)) {
            return;
        }
        PlayerCheckProfile profile = getOrCreateProfile(player);
        long now = System.currentTimeMillis();
        movementChecker.checkMovement(player, profile, event.getFrom(), event.getTo(), now);
        punishmentEngine.applyMovementMitigation(player, profile, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (isPlayerBypassed(attacker)) {
            return;
        }
        PlayerCheckProfile profile = getOrCreateProfile(attacker);
        long now = System.currentTimeMillis();

        combatAnalyzer.processAttackEvent(attacker, event.getEntity(), profile, now, event);
        decoyManager.evaluateAutoDecoy(attacker, profile, now);
        if (!event.isCancelled()) {
            punishmentEngine.applyCombatMitigation(attacker, event.getEntity(), profile, event);
        }
    }

    // ===================================================================== KOMUTLAR

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("zornoaimguard.admin")) {
            sender.sendMessage(Component.text("§cBu komut icin yetkin yok."));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §f/" + label
                    + " <status|stats|logs|alerts|decoy|reset|reload|ban|analiz|test|bypass>"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            punishmentManager.reloadConfigs();
            packetEventsBridge.refreshConfig();
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §apunishments.yml ve bans.yml yeniden yuklendi."));
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage(Component.text("§d=== ZornoAimGuard Durum ==="));
            sender.sendMessage(Component.text("§fSurum: §e" + getPluginMeta().getVersion()));
            sender.sendMessage(Component.text("§fPacketEvents: " + (packetEventsBridge.isHooked()
                    ? "§aAKTIF" : "§cKAPALI §7(" + packetEventsBridge.getLastError() + ")")));
            sender.sendMessage(Component.text("§fTuzak NPC: " + (decoyManager.isAvailable() ? "§aKullanilabilir" : "§cKapali")));
            sender.sendMessage(Component.text("§fOlculen TPS: §e" + String.format(Locale.ROOT, "%.1f", lagManager.getRecentTps())));
            sender.sendMessage(Component.text("§fLog klasoru: §e" + violationLogger.getLogDir().getPath()));
            sender.sendMessage(Component.text("§fToplam kayit: §e" + violationLogger.getTotalLogged()
                    + (violationLogger.getLastFileError().isEmpty() ? "" : " §c(dosya hatasi: " + violationLogger.getLastFileError() + ")")));
            if (sender instanceof Player p) {
                sender.sendMessage(Component.text("§fSenin uyarilarin: " + (violationLogger.areAlertsEnabled(p.getUniqueId()) ? "§aACIK" : "§cKAPALI")));
            }
            return true;
        }

        if (sub.equals("alerts")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("§cBu komut yalnizca oyunculara ozeldir."));
                return true;
            }
            boolean enabled = violationLogger.toggleAlerts(p.getUniqueId());
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §fOyun ici uyarilar: " + (enabled ? "§aACIK" : "§cKAPALI")));
            return true;
        }

        if (sub.equals("logs")) {
            String who = args.length >= 2 && !args[1].equalsIgnoreCase("hepsi") ? args[1] : null;
            int limit = 10;
            if (args.length >= 3) {
                try {
                    limit = Math.max(1, Math.min(40, Integer.parseInt(args[2])));
                } catch (NumberFormatException ignored) {
                    // varsayilan
                }
            }
            List<ViolationLogger.Entry> entries = violationLogger.recentFor(who, limit);
            sender.sendMessage(Component.text("§d=== Son kayitlar" + (who != null ? " (" + who + ")" : "") + " ==="));
            if (entries.isEmpty()) {
                sender.sendMessage(Component.text("§7Kayit yok. Dosya: " + violationLogger.getLogDir().getPath()));
            }
            for (ViolationLogger.Entry e : entries) {
                sender.sendMessage(Component.text("§8" + CLOCK.format(Instant.ofEpochMilli(e.timeMs())) + " §f" + e.player()
                        + " §e" + e.check() + " §8(§7" + e.detail() + "§8) §c" + String.format(Locale.ROOT, "%.1f", e.totalVl())
                        + " §8[" + e.action() + "]"));
            }
            return true;
        }

        if (sub.equals("ban")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("§cKullanim: /" + label + " ban <at|kaldır> <oyuncu> [sebep]"));
                return true;
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            String targetName = args[2];

            if (action.equals("at")) {
                String reason = args.length >= 4
                        ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                        : "Yetkili tarafindan banlandi";
                long days = punishmentManager.getPunishmentsConfig().getLong("punishments.ban-duration-days", 7L);
                Player online = Bukkit.getPlayerExact(targetName);
                UUID uuid = online != null ? online.getUniqueId() : null;

                punishmentManager.banPlayer(targetName, uuid, reason, sender.getName(), days);
                sender.sendMessage(Component.text("§d[ZornoAimGuard] §a" + targetName + " §fkalici kayitlara banlandi (§7" + reason + "§f)."));
                return true;
            }

            if (action.equals("kaldır") || action.equals("kaldir")) {
                boolean removed = punishmentManager.unbanPlayer(targetName, sender.getName());
                if (removed) {
                    sender.sendMessage(Component.text("§d[ZornoAimGuard] §a" + targetName + " §fban durumu kaldirildi."));
                } else {
                    sender.sendMessage(Component.text("§c" + targetName + " icin aktif ban kaydi bulunamadi."));
                }
                return true;
            }

            sender.sendMessage(Component.text("§cKullanim: /" + label + " ban <at|kaldır> <oyuncu> [sebep]"));
            return true;
        }

        if (sub.equals("bypass")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("§cKullanim: /" + label + " bypass <ac|kapat> <oyuncu>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("§cOyuncu aktif degil."));
                return true;
            }
            String mode = args[1].toLowerCase(Locale.ROOT);
            if (mode.equals("ac") || mode.equals("on")) {
                manualBypassPlayers.add(target.getUniqueId());
                sender.sendMessage(Component.text("§d[ZornoAimGuard] §a" + target.getName() + " §fiçin manuel bypass açıldı."));
                return true;
            }
            if (mode.equals("kapat") || mode.equals("off")) {
                manualBypassPlayers.remove(target.getUniqueId());
                sender.sendMessage(Component.text("§d[ZornoAimGuard] §e" + target.getName() + " §fiçin manuel bypass kapatıldı."));
                return true;
            }
            sender.sendMessage(Component.text("§cKullanim: /" + label + " bypass <ac|kapat> <oyuncu>"));
            return true;
        }

        if (sub.equals("test")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("§cKullanim: /" + label + " test <ac|kapat> <oyuncu>"));
                return true;
            }
            String mode = args[1].toLowerCase(Locale.ROOT);
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("§cOyuncu aktif degil."));
                return true;
            }
            if (mode.equals("ac") || mode.equals("on")) {
                testLogOnlyPlayers.add(target.getUniqueId());
                sender.sendMessage(Component.text("§d[ZornoAimGuard] §a" + target.getName() + " §fiçin TEST modu açıldı: Kick ve ban uygulanmaz, yalnızca log düşer."));
                return true;
            }
            if (mode.equals("kapat") || mode.equals("off")) {
                testLogOnlyPlayers.remove(target.getUniqueId());
                sender.sendMessage(Component.text("§d[ZornoAimGuard] §e" + target.getName() + " §fiçin TEST modu kapatıldı."));
                return true;
            }
            sender.sendMessage(Component.text("§cKullanim: /" + label + " test <ac|kapat> <oyuncu>"));
            return true;
        }

        if (sub.equals("analiz")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("§cKullanim: /" + label + " analiz <oyuncu> [süre_sn]"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("§cOyuncu aktif degil."));
                return true;
            }

            long now = System.currentTimeMillis();
            FocusSession existing = focusByTarget.get(target.getUniqueId());
            if (existing != null && now < existing.expiresAtMs() && args.length == 2) {
                focusByTarget.remove(target.getUniqueId());
                PlayerCheckProfile p = getOrCreateProfile(target);
                sender.sendMessage(Component.text(buildFocusSummary(target.getName(), p, "Manuel kapatıldı")));
                return true;
            }

            int seconds = 45;
            if (args.length >= 3) {
                try {
                    seconds = Math.max(10, Math.min(300, Integer.parseInt(args[2])));
                } catch (NumberFormatException ignored) {
                    // varsayilan
                }
            }

            UUID watcherId = (sender instanceof Player pSender) ? pSender.getUniqueId() : null;
            focusByTarget.put(target.getUniqueId(), new FocusSession(target.getUniqueId(), watcherId, now + (seconds * 1000L), 0L));

            PlayerCheckProfile p = getOrCreateProfile(target);
            decoyManager.spawnDecoyForPlayer(target, p, now);
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §a" + target.getName() + " §f" + seconds
                    + " saniye boyunca özel analiz moduna alındı" + (decoyManager.isAvailable() ? " (Tuzak aktif)." : " (Tuzak kapali: PacketEvents yok).")));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("§cOyuncu adi gir: /" + label + " " + args[0] + " <oyuncu>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("§cOyuncu aktif degil."));
            return true;
        }

        PlayerCheckProfile profile = getOrCreateProfile(target);

        if (sub.equals("stats")) {
            sender.sendMessage(Component.text("§d=== ZornoAimGuard: " + target.getName() + " ==="));
            sender.sendMessage(Component.text("§fMath/Reach/Duvar VL: §e" + String.format(Locale.ROOT, "%.2f", profile.getMathVl())));
            sender.sendMessage(Component.text("§fKillAura/Aim VL: §e" + String.format(Locale.ROOT, "%.2f", profile.getHeuristicVl())));
            sender.sendMessage(Component.text("§fSupheli Skoru: §c" + String.format(Locale.ROOT, "%.1f%%", profile.getCombatCheatScore() * 100.0)));
            sender.sendMessage(Component.text("§fTuzak Skoru: §c" + String.format(Locale.ROOT, "%.1f%%", profile.getDecoyLockScore() * 100.0)));
            sender.sendMessage(Component.text("§fTest Modu: §b" + (isTestLogOnly(target.getUniqueId()) ? "ACIK (Log Only)" : "KAPALI")));
            sender.sendMessage(Component.text("§fBypass: §b" + (isPlayerBypassed(target) ? "ACIK" : "KAPALI")
                    + (profile.isBedrock() ? " §7(Bedrock: nisan sezgisi muaf)" : "")));
            sender.sendMessage(Component.text("§fPing / Lag: §b" + target.getPing() + "ms §7(Lag=" + profile.isLagging() + ")"));
            sender.sendMessage(Component.text("§fRotasyon paketi ornegi: §b" + profile.rotationCount()
                    + (profile.rotationCount() == 0 && !packetEventsBridge.isHooked() ? " §c(PacketEvents kapali)" : "")));
            return true;
        }

        if (sub.equals("decoy")) {
            if (!decoyManager.isAvailable()) {
                sender.sendMessage(Component.text("§c[ZornoAimGuard] Tuzak NPC icin PacketEvents gerekli ve su an kapali. /" + label + " status"));
                return true;
            }
            decoyManager.spawnDecoyForPlayer(target, profile, System.currentTimeMillis());
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §f" + target.getName() + " icin tuzak NPC olusturuldu."));
            return true;
        }

        if (sub.equals("reset")) {
            profile.resetAll();
            decoyManager.removeDecoy(target.getUniqueId());
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §f" + target.getName() + " anlik analiz verileri sifirlandi."));
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("zornoaimguard.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filterPrefix(List.of("status", "stats", "logs", "alerts", "decoy", "reset", "reload", "ban", "analiz", "test", "bypass"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("ban")) {
            return filterPrefix(List.of("at", "kaldır"), args[1]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("bypass"))) {
            return filterPrefix(List.of("ac", "kapat"), args[1]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("stats")
                || args[0].equalsIgnoreCase("decoy")
                || args[0].equalsIgnoreCase("reset")
                || args[0].equalsIgnoreCase("analiz"))) {
            return filterPrefix(punishmentManager.getKnownPlayerNames(), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("logs")) {
            List<String> names = new ArrayList<>(punishmentManager.getKnownPlayerNames());
            names.add("hepsi");
            return filterPrefix(names, args[1]);
        }

        if (args.length == 3 && (args[0].equalsIgnoreCase("ban") || args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("bypass"))) {
            return filterPrefix(punishmentManager.getKnownPlayerNames(), args[2]);
        }

        return List.of();
    }

    private List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(c);
            }
        }
        return out;
    }
}
