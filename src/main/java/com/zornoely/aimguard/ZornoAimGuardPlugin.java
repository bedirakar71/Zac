package com.zornoely.aimguard;

import com.zornoely.aimguard.combat.CombatAnalyzer;
import com.zornoely.aimguard.decoy.DecoyNpcManager;
import com.zornoely.aimguard.lag.LagCompensationManager;
import com.zornoely.aimguard.mitigation.PunishmentEngine;
import com.zornoely.aimguard.movement.MovementSimulationChecker;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.punishment.PunishmentRecordManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ZornoAimGuardPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final Map<UUID, PlayerCheckProfile> profiles = new ConcurrentHashMap<>();

    private PunishmentRecordManager punishmentManager;
    private LagCompensationManager lagManager;
    private MovementSimulationChecker movementChecker;
    private CombatAnalyzer combatAnalyzer;
    private DecoyNpcManager decoyManager;
    private PunishmentEngine punishmentEngine;

    @Override
    public void onEnable() {
        this.punishmentManager = new PunishmentRecordManager(this);
        this.lagManager = new LagCompensationManager();
        this.movementChecker = new MovementSimulationChecker(this);
        this.combatAnalyzer = new CombatAnalyzer(this);
        this.decoyManager = new DecoyNpcManager(this);
        this.punishmentEngine = new PunishmentEngine(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("zornoaimguard") != null) {
            getCommand("zornoaimguard").setTabCompleter(this);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            profiles.put(online.getUniqueId(), new PlayerCheckProfile(online.getUniqueId()));
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            double tps = Bukkit.getTPS()[0];

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("zornoaimguard.bypass")) {
                    continue;
                }
                PlayerCheckProfile profile = getOrCreateProfile(player);
                lagManager.recordSample(player, profile, now, tps);
                punishmentEngine.applyDecay(profile, now);
                decoyManager.evaluateAutoDecoy(player, profile, now);
            }
        }, 20L, 20L);

        Bukkit.getScheduler().runTaskTimer(this, () -> decoyManager.tickActiveDecoys(), 5L, 5L);

        getLogger().info("ZornoAimGuard v2.2.0 baslatildi.");
    }

    @Override
    public void onDisable() {
        if (decoyManager != null) {
            decoyManager.clearAll();
        }
        profiles.clear();
    }

    public PlayerCheckProfile getOrCreateProfile(Player player) {
        return profiles.computeIfAbsent(player.getUniqueId(), PlayerCheckProfile::new);
    }

    public PunishmentRecordManager getPunishmentManager() {
        return punishmentManager;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerCheckProfile profile = new PlayerCheckProfile(player.getUniqueId());
        profiles.put(player.getUniqueId(), profile);
        lagManager.recordSample(player, profile, System.currentTimeMillis(), Bukkit.getTPS()[0]);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        decoyManager.removeDecoy(id);
        profiles.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("zornoaimguard.bypass")) {
            return;
        }
        PlayerCheckProfile profile = getOrCreateProfile(player);
        long now = System.currentTimeMillis();

        lagManager.recordPositionSnapshot(player.getUniqueId(), event.getTo(), now);
        movementChecker.checkMovement(player, profile, event.getFrom(), event.getTo(), now);
        decoyManager.checkAimLockOnDecoy(player, profile, event.getTo(), now);
        punishmentEngine.applyMovementMitigation(player, profile, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (attacker.hasPermission("zornoaimguard.bypass")) {
            return;
        }
        PlayerCheckProfile profile = getOrCreateProfile(attacker);
        long now = System.currentTimeMillis();

        combatAnalyzer.processAttackEvent(attacker, event.getEntity(), profile, now, event);
        if (!event.isCancelled()) {
            punishmentEngine.applyCombatMitigation(attacker, event.getEntity(), profile, event);
        }
    }

    /**
     * Duvara carpma / cevresel hasar veren diger pluginlerin hasarini engellemez;
     * cevresel hasar sirasinda oyuncunun duvara carpma ivmesini guvenli sayar.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL
                || cause == EntityDamageEvent.DamageCause.FLY_INTO_WALL
                || cause == EntityDamageEvent.DamageCause.CONTACT
                || cause == EntityDamageEvent.DamageCause.CUSTOM) {
            if (event.isCancelled()) {
                event.setCancelled(false);
            }
            PlayerCheckProfile profile = getOrCreateProfile(player);
            profile.setLastKnockbackTimeMs(System.currentTimeMillis());
            profile.setHasSimulatedVy(false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("zornoaimguard.admin")) {
            sender.sendMessage(Component.text("§cBu komut icin yetkin yok."));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §f/" + label + " <stats|decoy|reset|reload|ban>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            punishmentManager.reloadConfigs();
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §apunishments.yml ve bans.yml yeniden yuklendi."));
            return true;
        }

        if (args[0].equalsIgnoreCase("ban")) {
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
                    sender.sendMessage(Component.text("§d[ZornoAimGuard] §a" + targetName + " §fban durumu kaldirildi (gecmis kaydi silinmedi)."));
                } else {
                    sender.sendMessage(Component.text("§c" + targetName + " icin aktif ban kaydi bulunamadi."));
                }
                return true;
            }

            sender.sendMessage(Component.text("§cKullanim: /" + label + " ban <at|kaldır> <oyuncu> [sebep]"));
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

        if (args[0].equalsIgnoreCase("stats")) {
            sender.sendMessage(Component.text("§d=== ZornoAimGuard: " + target.getName() + " ==="));
            sender.sendMessage(Component.text("§fMath/Reach VL: §e" + String.format("%.2f", profile.getMathVl())));
            sender.sendMessage(Component.text("§fKillAura/Aim VL: §e" + String.format("%.2f", profile.getHeuristicVl())));
            sender.sendMessage(Component.text("§fKillAura Skoru: §c" + String.format("%.1f%%", profile.getCombatCheatScore() * 100.0)));
            sender.sendMessage(Component.text("§fDecoy Kilitlenme: §c" + String.format("%.1f%%", profile.getDecoyLockScore() * 100.0)));
            sender.sendMessage(Component.text("§fKick Sayisi: §6" + punishmentManager.getKickCount(target.getName())));
            sender.sendMessage(Component.text("§fPing / Lag: §b" + target.getPing() + "ms §7(Lag=" + profile.isLagging() + ")"));
            return true;
        }

        if (args[0].equalsIgnoreCase("decoy")) {
            decoyManager.spawnDecoyForPlayer(target, profile, System.currentTimeMillis());
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §f" + target.getName() + " icin decoy NPC olusturuldu."));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
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
            return filterPrefix(List.of("stats", "decoy", "reset", "reload", "ban"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("ban")) {
            return filterPrefix(List.of("at", "kaldır"), args[1]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("stats") || args[0].equalsIgnoreCase("decoy") || args[0].equalsIgnoreCase("reset"))) {
            return filterPrefix(punishmentManager.getKnownPlayerNames(), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("ban")) {
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
