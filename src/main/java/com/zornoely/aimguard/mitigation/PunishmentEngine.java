package com.zornoely.aimguard.mitigation;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public final class PunishmentEngine {

    private static final long DECAY_INTERVAL_MS = 2500L;

    private final ZornoAimGuardPlugin plugin;

    public PunishmentEngine(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyDecay(PlayerCheckProfile profile, long nowMs) {
        long elapsed = nowMs - profile.getLastDecayTimeMs();
        if (elapsed < DECAY_INTERVAL_MS) {
            return;
        }
        double steps = elapsed / (double) DECAY_INTERVAL_MS;
        profile.decayVl(0.22 * steps, 0.20 * steps, nowMs);
    }

    public void applyMovementMitigation(Player player, PlayerCheckProfile profile, PlayerMoveEvent event) {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        boolean freezeLowSignals = cfg.getBoolean("environment.freeze-on-low-signals", false);
        boolean elytraSafe = cfg.getBoolean("environment.elytra-safe-mode", true);

        if (!freezeLowSignals) {
            return;
        }
        if (profile.isLagging() || (elytraSafe && player.isGliding())) {
            profile.clearMitigation();
            return;
        }
        if (!profile.isMitigating()) {
            return;
        }

        // Yalnizca cok yuksek ve dogrulanmis ihlallerde hafif hareket sinirlamasi
        if ((profile.getMathVl() + profile.getHeuristicVl()) < 7.0) {
            return;
        }

        if (event.getTo() != null && event.getFrom() != null) {
            double dx = event.getTo().getX() - event.getFrom().getX();
            double dz = event.getTo().getZ() - event.getFrom().getZ();
            double dist = Math.hypot(dx, dz);
            if (dist > 0.42) {
                double scale = 0.42 / dist;
                event.setTo(event.getFrom().clone().add(dx * scale, event.getTo().getY() - event.getFrom().getY(), dz * scale));
            }
        }
    }

    public void applyCombatMitigation(Player attacker, Entity target, PlayerCheckProfile profile, EntityDamageByEntityEvent event) {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        boolean freezeLowSignals = cfg.getBoolean("environment.freeze-on-low-signals", false);
        boolean elytraSafe = cfg.getBoolean("environment.elytra-safe-mode", true);

        if (!freezeLowSignals) {
            return;
        }
        if (profile.isLagging() || (elytraSafe && attacker.isGliding())) {
            return;
        }
        if (!profile.isMitigating()) {
            return;
        }

        if (profile.getCombatCheatScore() >= 0.75) {
            event.setDamage(event.getDamage() * 0.75);
        }
    }
}
