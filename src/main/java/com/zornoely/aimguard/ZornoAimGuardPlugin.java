package com.zornoely.aimguard;

import com.zornoely.aimguard.combat.CombatAnalyzer;
import com.zornoely.aimguard.decoy.DecoyNpcManager;
import com.zornoely.aimguard.lag.LagCompensationManager;
import com.zornoely.aimguard.mitigation.PunishmentEngine;
import com.zornoely.aimguard.movement.MovementSimulationChecker;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ZornoAimGuardPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerCheckProfile> profiles = new ConcurrentHashMap<>();

    private LagCompensationManager lagManager;
    private MovementSimulationChecker movementChecker;
    private CombatAnalyzer combatAnalyzer;
    private DecoyNpcManager decoyManager;
    private PunishmentEngine punishmentEngine;

    @Override
    public void onEnable() {
        this.lagManager = new LagCompensationManager();
        this.movementChecker = new MovementSimulationChecker(this);
        this.combatAnalyzer = new CombatAnalyzer(this);
        this.decoyManager = new DecoyNpcManager(this);
        this.punishmentEngine = new PunishmentEngine(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player online : Bukkit.getOnlinePlayers()) {
            profiles.put(online.getUniqueId(), new PlayerCheckProfile(online.getUniqueId()));
        }

        // Her 20 tick (1 saniye): Ping/Lag kaydi, VL erimesi (decay) ve decoy/cross-check taramasi
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

        // Her 5 tick: Aktif decoy NPC'lerin hareketini guncelle
        Bukkit.getScheduler().runTaskTimer(this, () -> decoyManager.tickActiveDecoys(), 5L, 5L);

        getLogger().info("ZornoAimGuard v2.0.0 baslatildi.");
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

    public LagCompensationManager getLagManager() {
        return lagManager;
    }

    public PunishmentEngine getPunishmentEngine() {
        return punishmentEngine;
    }

    public DecoyNpcManager getDecoyManager() {
        return decoyManager;
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

        combatAnalyzer.processHit(attacker, event.getEntity(), profile, now);
        punishmentEngine.applyCombatMitigation(attacker, event.getEntity(), profile, event);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §fKullanim: /" + label + " <stats|decoy|reset> <oyuncu>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("§cOyuncu bulunamadi."));
            return true;
        }

        PlayerCheckProfile profile = getOrCreateProfile(target);

        if (args[0].equalsIgnoreCase("stats")) {
            sender.sendMessage(Component.text("§d=== ZornoAimGuard: " + target.getName() + " ==="));
            sender.sendMessage(Component.text("§fMath/Packet VL: §e" + String.format("%.2f", profile.getMathVl())));
            sender.sendMessage(Component.text("§fAim/Combat VL: §e" + String.format("%.2f", profile.getHeuristicVl())));
            sender.sendMessage(Component.text("§fCombat Hile Skoru: §c" + String.format("%.1f%%", profile.getCombatCheatScore() * 100.0)));
            sender.sendMessage(Component.text("§fDecoy Kilitlenme Skoru: §c" + String.format("%.1f%%", profile.getDecoyLockScore() * 100.0)));
            sender.sendMessage(Component.text("§fPing / Lag: §b" + target.getPing() + "ms §7(LagSpike=" + profile.isLagging() + ")"));
            sender.sendMessage(Component.text("§fMitigation Aktif: §d" + profile.isMitigating()));
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
            sender.sendMessage(Component.text("§d[ZornoAimGuard] §f" + target.getName() + " verileri sifirlandi."));
            return true;
        }

        return false;
    }
}
