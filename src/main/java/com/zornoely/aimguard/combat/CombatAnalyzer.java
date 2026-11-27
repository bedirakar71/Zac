package com.zornoely.aimguard.combat;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.lag.LagCompensationManager;
import com.zornoely.aimguard.packet.PacketEventsBridge;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.profile.PlayerCheckProfile.Category;
import com.zornoely.aimguard.profile.PlayerCheckProfile.HitSample;
import com.zornoely.aimguard.profile.PlayerCheckProfile.PendingHit;
import com.zornoely.aimguard.profile.PlayerCheckProfile.RotationSample;
import com.zornoely.aimguard.punishment.PunishmentRecordManager.Severity;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Savas dogrulamasi.
 *
 * <p>Iki asamali calisir:
 * <ol>
 *   <li><b>Anlik (vurus olayi):</b> imkansiz vurus = menzil disi ve duvar arkasi. Bu vuruslar iptal edilir; ceza (VL)
 *       ise ancak tampon (buffer) dolunca verilir. Tek bir sinir durumu asla ban sebebi olmaz.</li>
 *   <li><b>Ertelenmis (vurustan ~2 tick sonra):</b> nisan/rotasyon analizi. Istemci vurus paketini rotasyon paketinden
 *       once gonderebildigi icin, kararin dogru verilmesi icin vurus cevresindeki rotasyon paketleri beklenir.</li>
 * </ol>
 */
public final class CombatAnalyzer {

    private final ZornoAimGuardPlugin plugin;

    public CombatAnalyzer(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    // ===================================================================== ANLIK KONTROLLER

    public void processAttackEvent(Player attacker, Entity target, PlayerCheckProfile profile, long nowMs,
                                   EntityDamageByEntityEvent event) {
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        // Sweep (supurme) hasari ve eklenti kaynakli hasar ucuncu kisilere gider: kursor kontrolu uygulanamaz.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        GameMode gm = attacker.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return;
        }
        if (target.getUniqueId().equals(attacker.getUniqueId())) {
            return;
        }

        PacketEventsBridge bridge = plugin.getPacketEventsBridge();
        if (bridge != null && bridge.isHooked()) {
            // Yalnizca gercek bir istemci saldiri paketiyle eslesen vuruslar kontrol edilir.
            // Baska eklentilerin (beceri/AoE) player.damage(...) ile verdigi hasar boylece false flag uretmez.
            if (!profile.consumeAttackPacket(target.getEntityId(), nowMs, 500L)) {
                return;
            }
        }

        profile.setLastCombatTimeMs(nowMs);
        if (profile.inGrace(nowMs)) {
            return;
        }

        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        LagCompensationManager lag = plugin.getLagManager();
        int ping = Math.max(0, attacker.getPing());
        long latencyMs = Math.min(600L, ping + 60L);

        List<Vector> eyes = attackerEyes(attacker, lag, nowMs);
        List<BoundingBox> boxes = targetBoxes(living, lag, nowMs, latencyMs);

        // ---------------------------------------------------------------- REACH
        // Sunucuda esya/efekt ile saldiri menzili artirilmissa (entity_interaction_range) mesru vuruslar iptal edilmesin
        double reachMax = interactionRange(attacker, cfg.getDouble("combat.reach.max-blocks", 3.0));
        double tolerance = cfg.getDouble("combat.reach.tolerance", 0.25);
        double attackerSlack = Math.min(0.6, lag.speedPerTick(attacker.getUniqueId()) + 0.05);
        double mobSlack = 0.0;
        if (!(target instanceof Player)) {
            mobSlack = Math.min(0.9, (living.getVelocity().length() * (latencyMs / 50.0)) + 0.1);
        }
        double kbSlack = (nowMs - profile.getLastKnockbackTimeMs()) < 1200L ? 0.35 : 0.0;
        double glideSlack = attacker.isGliding() ? 0.6 : 0.0;
        double allowed = reachMax + tolerance + attackerSlack + mobSlack + kbSlack + glideSlack;

        double minDist = Double.MAX_VALUE;
        Vector bestEye = eyes.get(0);
        BoundingBox bestBox = boxes.get(0);
        for (Vector eye : eyes) {
            for (BoundingBox b : boxes) {
                double d = HitGeometry.distanceToBox(eye, b);
                if (d < minDist) {
                    minDist = d;
                    bestEye = eye;
                    bestBox = b;
                }
            }
        }

        if (minDist > allowed) {
            double excess = minDist - allowed;
            event.setCancelled(true);
            double buf = profile.addBuffer("reach", 1.0 + Math.min(2.0, excess * 2.0), nowMs, 0.5);
            String detail = String.format(Locale.ROOT, "mesafe=%.2f izin=%.2f fazla=%.2f tampon=%.1f", minDist, allowed, excess, buf);
            if (buf >= cfg.getDouble("combat.reach.buffer-needed", 2.5) && !profile.isLagging()) {
                profile.addMathVl(Math.min(2.5, 0.5 + (excess * 2.0)), nowMs);
                flag(attacker, profile, nowMs, Category.REACH, "Reach", detail, excess > 1.0 ? Severity.HARD : Severity.NORMAL);
            } else {
                flag(attacker, profile, nowMs, Category.REACH, "Reach", detail, Severity.SOFT);
            }
            return;
        }

        // ---------------------------------------------------------------- DUVAR ARKASI
        if (cfg.getBoolean("combat.wall.enabled", true) && minDist >= 0.9) {
            World world = living.getWorld();
            double inflate = cfg.getDouble("combat.wall.box-inflate", 0.12);
            boolean visible = false;
            outer:
            for (Vector eye : eyes) {
                for (BoundingBox b : boxes) {
                    if (HitGeometry.hasLineOfSight(world, eye, b, inflate)) {
                        visible = true;
                        break outer;
                    }
                }
            }
            if (!visible) {
                event.setCancelled(true);
                double buf = profile.addBuffer("wall", 1.0, nowMs, 0.30);
                String detail = String.format(Locale.ROOT, "mesafe=%.2f tampon=%.1f (hicbir gorus hatti yok)", minDist, buf);
                if (buf >= cfg.getDouble("combat.wall.buffer-needed", 2.0) && !profile.isLagging()) {
                    profile.addMathVl(cfg.getDouble("combat.wall.vl", 1.2), nowMs);
                    flag(attacker, profile, nowMs, Category.WALL, "WallHit", detail, Severity.NORMAL);
                } else {
                    flag(attacker, profile, nowMs, Category.WALL, "WallHit", detail, Severity.SOFT);
                }
                return;
            }
        }

        // ---------------------------------------------------------------- DAHA ONCE KANITLANMIS SUPHELILER: vurus hafifletme
        if (profile.isAimMitigating(nowMs)) {
            double immediate = immediateAimError(attacker, profile, eyes, boxes, nowMs);
            double mitigationAngle = cfg.getDouble("combat.aim.mitigation-angle", 45.0);
            if (immediate > mitigationAngle) {
                event.setCancelled(true);
                plugin.getViolationLogger().logInfo(attacker, "AimMitigation",
                        String.format(Locale.ROOT, "vurus iptal, aci=%.1f", immediate));
                return;
            }
        }

        // ---------------------------------------------------------------- ERTELENMIS NISAN ANALIZI ICIN KAYIT
        Location eyeLoc = attacker.getEyeLocation();
        boolean targetMoving = (target instanceof Player tp)
                ? lag.speedPerTick(tp.getUniqueId()) > 0.08
                : living.getVelocity().lengthSquared() > 0.0025;
        profile.addPendingHit(new PendingHit(
                nowMs,
                target.getUniqueId(),
                target.getEntityId(),
                bestEye.getX(), bestEye.getY(), bestEye.getZ(),
                bestBox.clone(),
                minDist,
                targetMoving,
                eyeLoc.getYaw(),
                eyeLoc.getPitch(),
                attackerSlack
        ));
    }

    // ===================================================================== ERTELENMIS ANALIZ (her tick)

    public void processPending(Player player, PlayerCheckProfile profile, long nowMs) {
        PendingHit h;
        int guard = 0;
        while (guard++ < 8 && (h = profile.pollPendingHitOlderThan(nowMs, 110L)) != null) {
            try {
                analyzeHit(player, profile, h, nowMs);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Vurus analizi hatasi: " + ex);
            }
        }
    }

    private void analyzeHit(Player player, PlayerCheckProfile profile, PendingHit h, long nowMs) {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        if (!cfg.getBoolean("combat.aim.enabled", true) || profile.isBedrock()) {
            return;
        }

        Vector eye = new Vector(h.eyeX(), h.eyeY(), h.eyeZ());
        BoundingBox box = h.box();

        List<float[]> candidates = rotationCandidates(profile, h);
        double bestErr = 180.0;
        float[] bestRot = null;
        for (float[] c : candidates) {
            Vector dir = HitGeometry.direction(c[0], c[1]);
            double err = HitGeometry.angleToBoxDeg(eye, dir, box);
            if (err < bestErr) {
                bestErr = err;
                bestRot = c;
            }
        }
        if (bestRot == null) {
            return;
        }
        Vector bestDir = HitGeometry.direction(bestRot[0], bestRot[1]);
        double centerErr = HitGeometry.angleToPointDeg(eye, bestDir, box.getCenter());
        double halfAng = HitGeometry.halfAngularWidthDeg(box.getWidthX(), h.distance());
        double norm = centerErr / Math.max(1.0, halfAng);

        boolean rotationDataOk = profile.rotationCount() >= 4;
        boolean lagging = profile.isLagging();

        // ---------------------------------------------------------------- 1) Bakmadan vurma (AimMismatch)
        double slackAngle = Math.toDegrees(Math.atan2(0.6 + h.slackBlocks(), Math.max(0.5, h.distance())));
        double threshold = cfg.getDouble("combat.aim.max-hit-error-deg", 10.0)
                + slackAngle + Math.min(15.0, profile.getLastPingMs() / 20.0);

        if (rotationDataOk && bestErr > threshold) {
            double ratio = bestErr / threshold;
            double buf = profile.addBuffer("aimmiss", ratio >= 2.0 ? 2.0 : 1.0, nowMs, 0.35);
            String detail = String.format(Locale.ROOT, "hata=%.1f esik=%.1f mesafe=%.2f tampon=%.1f",
                    bestErr, threshold, h.distance(), buf);
            if (buf >= cfg.getDouble("combat.aim.mismatch-buffer-needed", 3.0) && !lagging) {
                profile.addHeuristicVl(Math.min(2.5, 0.8 * Math.min(3.0, ratio)), nowMs);
                boolean hard = bestErr >= 75.0 && buf >= 4.0;
                flag(player, profile, nowMs, Category.KILLAURA, "AimMismatch", detail, hard ? Severity.HARD : Severity.NORMAL);
                profile.activateAimMitigation(nowMs + 6000L);
            } else {
                flag(player, profile, nowMs, Category.KILLAURA, "AimMismatch", detail, Severity.SOFT);
            }
        }

        // ---------------------------------------------------------------- 2) Merkeze kilitlenme istatistigi (AimLock)
        Deque<HitSample> hits = profile.getHitSamples();
        hits.addLast(new HitSample(h.attackMs(), h.distance(), bestErr, norm, h.targetMoving()));
        while (!hits.isEmpty() && (nowMs - hits.peekFirst().timestampMs()) > 12_000L) {
            hits.pollFirst();
        }

        int minLockHits = cfg.getInt("combat.aim.lock-min-hits", 8);
        int n = 0;
        double sum = 0.0;
        double sumSq = 0.0;
        for (HitSample s : hits) {
            // Duran hedefe veya cok yakin mesafeye yapilan vuruslar dogal olarak "merkeze yakin" olur: sayma
            if (!s.targetMoving() || s.distance() < 1.8 || s.aimErrorDeg() > threshold) {
                continue;
            }
            n++;
            sum += s.normCenterError();
            sumSq += s.normCenterError() * s.normCenterError();
        }
        if (n >= minLockHits) {
            double mean = sum / n;
            double std = Math.sqrt(Math.max(0.0, (sumSq / n) - (mean * mean)));
            boolean locked = (mean <= 0.20 && std <= 0.12) || (mean <= 0.30 && std <= 0.08);
            if (locked) {
                double buf = profile.addBuffer("aimlock", 1.0, nowMs, 0.20);
                String detail = String.format(Locale.ROOT, "ort=%.2f sapma=%.2f vurus=%d tampon=%.1f", mean, std, n, buf);
                if (buf >= 2.0 && !lagging) {
                    profile.addHeuristicVl(1.2, nowMs);
                    flag(player, profile, nowMs, Category.AIM, "AimLock", detail, Severity.NORMAL);
                    profile.activateAimMitigation(nowMs + 6000L);
                } else {
                    flag(player, profile, nowMs, Category.AIM, "AimLock", detail, Severity.SOFT);
                }
            }
        }

        // ---------------------------------------------------------------- 3) Yumusatilmis (ussel azalan) nisan yardimi
        List<RotationSample> recent = profile.rotationsBetween(h.attackMs() - 700L, h.attackMs() + 110L);
        int run = Math.max(smoothRun(recent, true), smoothRun(recent, false));
        if (run >= cfg.getInt("killaura.smooth-run-min", 4)) {
            double buf = profile.addBuffer("aimsmooth", 1.0, nowMs, 0.25);
            String detail = "duzgun-oran-serisi=" + run + " tampon=" + String.format(Locale.ROOT, "%.1f", buf);
            if (buf >= 2.0 && !lagging) {
                profile.addHeuristicVl(1.5, nowMs);
                flag(player, profile, nowMs, Category.AIM, "AimSmooth", detail, Severity.NORMAL);
                profile.activateAimMitigation(nowMs + 6000L);
            } else {
                flag(player, profile, nowMs, Category.AIM, "AimSmooth", detail, Severity.SOFT);
            }
        }

        // ---------------------------------------------------------------- 4) Vur-ve-geri-don rotasyonu (sessiz kill aura)
        double snapMin = cfg.getDouble("killaura.snap-return-min-deg", 35.0);
        int snaps = snapReturns(profile.rotationsBetween(h.attackMs() - 130L, h.attackMs() + 130L), snapMin);
        if (snaps > 0) {
            double buf = profile.addBuffer("snapret", snaps, nowMs, 0.30);
            String detail = "donus=" + snaps + " tampon=" + String.format(Locale.ROOT, "%.1f", buf);
            if (buf >= 2.0 && !lagging) {
                profile.addHeuristicVl(1.5, nowMs);
                flag(player, profile, nowMs, Category.KILLAURA, "SnapReturn", detail, Severity.NORMAL);
                profile.activateAimMitigation(nowMs + 6000L);
            } else {
                flag(player, profile, nowMs, Category.KILLAURA, "SnapReturn", detail, Severity.SOFT);
            }
        }

        // ---------------------------------------------------------------- supheli skoru (analiz/decoy icin)
        double score = (profile.peekBuffer("aimmiss", nowMs, 0.35) / 4.0)
                + (profile.peekBuffer("aimlock", nowMs, 0.20) / 3.0)
                + (profile.peekBuffer("aimsmooth", nowMs, 0.25) / 3.0)
                + (profile.peekBuffer("snapret", nowMs, 0.30) / 3.0)
                + (profile.peekBuffer("reach", nowMs, 0.5) / 4.0)
                + (profile.peekBuffer("wall", nowMs, 0.30) / 3.0);
        profile.setCombatCheatScore(Math.min(1.0, score / 2.0));
    }

    // ===================================================================== YARDIMCILAR

    private void flag(Player p, PlayerCheckProfile prof, long now, Category cat, String check, String detail, Severity sev) {
        plugin.getPunishmentManager().flag(p, prof, now, cat, check, detail, sev);
    }

    /** Oyuncunun gercek saldiri menzili: ayar degeri ile sunucudaki entity_interaction_range ozniteliginin buyugu. */
    private static double interactionRange(Player player, double fallback) {
        try {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("entity_interaction_range"));
            if (attribute == null) {
                attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("player.entity_interaction_range"));
            }
            if (attribute != null) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    return Math.max(fallback, instance.getValue());
                }
            }
        } catch (Throwable ignored) {
            // surum farki: varsayilan menzil kullanilir
        }
        return fallback;
    }

    /** Vurus anindaki kabul edilebilir goz konumlari (guncel + son birkac tick + ayakta goz yuksekligi). */
    private List<Vector> attackerEyes(Player attacker, LagCompensationManager lag, long nowMs) {
        List<Vector> out = new ArrayList<>(4);
        Location eye = attacker.getEyeLocation();
        out.add(eye.toVector());
        List<LagCompensationManager.Snapshot> snaps = lag.window(attacker.getUniqueId(), nowMs - 160L, nowMs);
        if (!snaps.isEmpty()) {
            LagCompensationManager.Snapshot oldest = snaps.get(0);
            out.add(new Vector(oldest.x(), oldest.y() + oldest.eyeHeight(), oldest.z()));
            if (snaps.size() > 2) {
                LagCompensationManager.Snapshot mid = snaps.get(snaps.size() / 2);
                out.add(new Vector(mid.x(), mid.y() + mid.eyeHeight(), mid.z()));
            }
        }
        double standing = attacker.getLocation().getY() + 1.62;
        if (Math.abs(standing - eye.getY()) > 0.05) {
            out.add(new Vector(eye.getX(), standing, eye.getZ()));
        }
        return out;
    }

    /** Hedefin istemci tarafinda gorulmus olabilecek kutulari (guncel + ping kadar geriye alinmis konumlar). */
    private List<BoundingBox> targetBoxes(LivingEntity target, LagCompensationManager lag, long nowMs, long latencyMs) {
        BoundingBox cur = target.getBoundingBox();
        List<BoundingBox> out = new ArrayList<>(4);
        out.add(cur);
        if (target instanceof Player tp) {
            List<LagCompensationManager.Snapshot> snaps = lag.window(tp.getUniqueId(), nowMs - latencyMs - 100L, nowMs - 25L);
            if (!snaps.isEmpty()) {
                Location now = target.getLocation();
                addShifted(out, cur, snaps.get(0), now);
                if (snaps.size() > 2) {
                    addShifted(out, cur, snaps.get(snaps.size() / 2), now);
                }
                if (snaps.size() > 1) {
                    addShifted(out, cur, snaps.get(snaps.size() - 1), now);
                }
            }
        }
        return out;
    }

    private static void addShifted(List<BoundingBox> out, BoundingBox cur, LagCompensationManager.Snapshot s, Location now) {
        out.add(cur.clone().shift(s.x() - now.getX(), s.y() - now.getY(), s.z() - now.getZ()));
    }

    private double immediateAimError(Player attacker, PlayerCheckProfile profile, List<Vector> eyes,
                                     List<BoundingBox> boxes, long nowMs) {
        List<float[]> rots = new ArrayList<>();
        Location loc = attacker.getLocation();
        rots.add(new float[]{loc.getYaw(), loc.getPitch()});
        for (RotationSample s : profile.rotationsBetween(nowMs - 200L, nowMs)) {
            rots.add(new float[]{s.yaw(), s.pitch()});
        }
        double best = 180.0;
        for (float[] r : rots) {
            Vector dir = HitGeometry.direction(r[0], r[1]);
            for (Vector eye : eyes) {
                for (BoundingBox b : boxes) {
                    best = Math.min(best, HitGeometry.angleToBoxDeg(eye, dir, b));
                }
            }
        }
        return best;
    }

    /** Vurus cevresindeki tum rotasyon paketleri + vurustan hemen onceki son rotasyon + olay anindaki rotasyon. */
    private List<float[]> rotationCandidates(PlayerCheckProfile profile, PendingHit h) {
        List<float[]> out = new ArrayList<>();
        for (RotationSample s : profile.rotationsBetween(h.attackMs() - 140L, h.attackMs() + 110L)) {
            out.add(new float[]{s.yaw(), s.pitch()});
        }
        RotationSample before = profile.lastRotationBefore(h.attackMs() - 140L);
        if (before != null) {
            out.add(new float[]{before.yaw(), before.pitch()});
        }
        out.add(new float[]{h.eventYaw(), h.eventPitch()});
        return out;
    }

    /**
     * Ardisik tick'lerde orani neredeyse sabit (±%3.5) azalan en az 5 rotasyon adimi: ussel yumusatma yapan
     * nisan yardimcilarinin imzasi. Insan fare hareketi bu kadar duzgun azalmaz.
     */
    private static int smoothRun(List<RotationSample> samples, boolean yawAxis) {
        int best = 0;
        int run = 0;
        double ratioSum = 0.0;
        for (int i = 1; i < samples.size(); i++) {
            RotationSample prev = samples.get(i - 1);
            RotationSample cur = samples.get(i);
            double a = yawAxis ? prev.deltaYaw() : prev.deltaPitch();
            double b = yawAxis ? cur.deltaYaw() : cur.deltaPitch();
            boolean consecutive = (cur.timestampMs() - prev.timestampMs()) <= 80L;
            if (consecutive && Math.abs(a) >= 1.5 && Math.abs(b) >= 1.0 && (a * b) > 0.0) {
                double r = b / a;
                if (r >= 0.35 && r <= 0.92) {
                    if (run == 0) {
                        run = 1;
                        ratioSum = r;
                    } else {
                        double mean = ratioSum / run;
                        if (Math.abs(r - mean) <= 0.035) {
                            run++;
                            ratioSum += r;
                        } else {
                            run = 1;
                            ratioSum = r;
                        }
                    }
                    best = Math.max(best, run);
                    continue;
                }
            }
            run = 0;
            ratioSum = 0.0;
        }
        return best;
    }

    /** Buyuk bir donusun hemen ardindan ters yonde (>= %70) donus: rotasyon paketiyle sessiz hedefleme izi. */
    private static int snapReturns(List<RotationSample> samples, double minMag) {
        int count = 0;
        for (int i = 0; i + 1 < samples.size(); i++) {
            RotationSample s = samples.get(i);
            RotationSample n = samples.get(i + 1);
            double mag = Math.hypot(s.deltaYaw(), s.deltaPitch());
            if (mag < minMag || (n.timestampMs() - s.timestampMs()) > 120L) {
                continue;
            }
            double dot = (s.deltaYaw() * n.deltaYaw()) + (s.deltaPitch() * n.deltaPitch());
            double nMag = Math.hypot(n.deltaYaw(), n.deltaPitch());
            if (dot < 0.0 && nMag >= (mag * 0.7)) {
                count++;
            }
        }
        return count;
    }
}
