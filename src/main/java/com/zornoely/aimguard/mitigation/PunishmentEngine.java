package com.zornoely.aimguard.mitigation;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.Date;

public final class PunishmentEngine {

    private final ZornoAimGuardPlugin plugin;

    public PunishmentEngine(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyDecay(PlayerCheckProfile profile, long nowMs) {
        if ((nowMs - profile.getLastFlagTimeMs()) < 3500L) {
            return;
        }
        profile.decayVl(0.18, 0.40, nowMs);
    }

    public void evaluateCrossCheck(Player player, PlayerCheckProfile profile, long nowMs, String reason) {
        if (profile.isLagging()) {
            return;
        }

        double mathVl = profile.getMathVl();
        double heuristicVl = profile.getHeuristicVl();
        double combatScore = profile.getCombatCheatScore();
        double decoyScore = profile.getDecoyLockScore();

        // Once Silent Mitigation ile avantaj kesilir (ban atmadan once kanit biriktirme)
        if (heuristicVl >= 3.2 || combatScore >= 0.55 || decoyScore >= 0.55) {
            profile.activateMitigation(7000L);
        }

        // Cross-Check Ban: tek bir sinyalle degil, birlesik veya yuksek kesinlikle ban
        boolean hardMathBan = mathVl >= 10.5;
        boolean strongAimCrossCheck = heuristicVl >= 7.5
                && combatScore >= 0.72
                && (decoyScore >= 0.55 || profile.getRotationSamples().size() >= 40);

        if (hardMathBan || strongAimCrossCheck) {
            executeBan(player, profile, reason);
        }
    }

    public void applyCombatMitigation(Player attacker, Entity victim, PlayerCheckProfile profile, EntityDamageByEntityEvent event) {
        if (!profile.isMitigating() || profile.isLagging()) {
            return;
        }
        event.setDamage(event.getDamage() * 0.78);
        Vector pushBack = attacker.getLocation().getDirection().multiply(-0.15).setY(0.06);
        attacker.setVelocity(attacker.getVelocity().add(pushBack));
    }

    public void applyMovementMitigation(Player player, PlayerCheckProfile profile, PlayerMoveEvent event) {
        if (!profile.isMitigating() || profile.isLagging() || event.getTo() == null) {
            return;
        }
        Vector delta = event.getTo().toVector().subtract(event.getFrom().toVector());
        delta.setX(delta.getX() * 0.95);
        delta.setZ(delta.getZ() * 0.95);
        event.setTo(event.getFrom().clone().add(delta));
    }

    private void executeBan(Player player, PlayerCheckProfile profile, String reason) {
        Date expiry = new Date(System.currentTimeMillis() + (7L * 24L * 60L * 60L * 1000L));
        String message = "§c§lZornoAimGuard §8» §7Supheli hareket/aim davranisi tespit edildi (§f" + reason + "§7).";

        Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), message, expiry, "ZornoAimGuard");
        player.kick(Component.text(message));
        profile.resetAll();
        plugin.getDecoyManager().removeDecoy(player.getUniqueId());
    }
}
