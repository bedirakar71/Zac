package com.zornoely.aimguard.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import com.zornoely.aimguard.profile.PlayerCheckProfile.Category;
import com.zornoely.aimguard.profile.PlayerCheckProfile.RotationSample;
import com.zornoely.aimguard.punishment.PunishmentRecordManager.Severity;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty thread'inde calisir: BUKKIT API'SI KULLANILMAZ (yalnizca saf hesap + thread-guvenli profil yapilari).
 * Ceza/log gerektiren her sey ana thread'e aktarilir.
 *
 * <p>Gorevleri:
 * <ul>
 *   <li>Zararli/gecersiz paketleri engeller: NaN/sonsuz konum-rotasyon, pitch &gt; 90, devasa koordinat,
 *       kendine saldiri, hareket paketi seli, saldiri paketi seli.</li>
 *   <li>Rotasyon paketlerini kaydeder (nisan analizi icin) ve saldiri paketlerini kaydeder (vurus eslemesi icin).</li>
 *   <li>Cok-hedefli aura (ayni anda 3+ hedef) ve kolsuz vurus (NoSwing) izlerini yakalar.</li>
 *   <li>Tuzak NPC'ye gelen saldirilari tuzak yoneticisine iletir.</li>
 * </ul>
 */
public final class AimGuardPacketListener extends PacketListenerAbstract {

    private static final class PacketState {
        long flyWindowStart;
        int flyCount;
        int floodHits;
        final long[] attackTimes = new long[64];
        int attackIdx;
        long lastAttackMs;
        final int[] burstTargets = new int[4];
        int burstCount;
        long lastSwingMs;
        long noSwingDeadline;
        double noSwingBuf;
        long noSwingBufMs;
        double multiBuf;
        long multiBufMs;
        long pitchWindowStart;
        int pitchViolations;
        boolean nameChecked;
    }

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, PacketState> states = new ConcurrentHashMap<>();

    private volatile boolean packetChecksEnabled = true;
    private volatile boolean kickOnInvalid = true;
    private volatile int maxFlyingPerSecond = 120;
    private volatile int maxAttacksPerSecond = 30;
    private volatile int attackFloodFlagAt = 45;
    private volatile double noSwingBufferNeeded = 4.0;
    private volatile double snapTurnMin = 40.0;

    public AimGuardPacketListener(ZornoAimGuardPlugin plugin) {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
        refreshConfig();
    }

    public void refreshConfig() {
        FileConfiguration cfg = plugin.getPunishmentManager().getPunishmentsConfig();
        packetChecksEnabled = cfg.getBoolean("packets.enabled", true);
        kickOnInvalid = cfg.getBoolean("packets.kick-on-invalid", true);
        maxFlyingPerSecond = cfg.getInt("packets.max-flying-per-second", 120);
        maxAttacksPerSecond = cfg.getInt("packets.max-attacks-per-second", 30);
        attackFloodFlagAt = cfg.getInt("packets.attack-flood-flag-at", 45);
        noSwingBufferNeeded = cfg.getDouble("packets.noswing-buffer-needed", 4.0);
        snapTurnMin = cfg.getDouble("killaura.snap-turn-min-deg", 40.0);
    }

    public void clearPlayer(UUID uuid) {
        states.remove(uuid);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            handle(event);
        } catch (Throwable ignored) {
            // Paket hattini asla bozma: hatali bir kontrol oyuncuyu dusurmemeli
        }
    }

    private void handle(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean flying = WrapperPlayClientPlayerFlying.isFlying(type);
        boolean interact = type == PacketType.Play.Client.INTERACT_ENTITY;
        boolean swing = type == PacketType.Play.Client.ANIMATION;
        if (!flying && !interact && !swing) {
            return;
        }

        User user = event.getUser();
        if (user == null) {
            return;
        }
        UUID uuid = user.getUUID();
        if (uuid == null) {
            return;
        }

        long now = System.currentTimeMillis();
        PacketState st = states.computeIfAbsent(uuid, k -> new PacketState());
        PlayerCheckProfile profile = plugin.getOrCreateProfile(uuid);
        if (!st.nameChecked) {
            st.nameChecked = true;
            String name = user.getName();
            if (name != null && plugin.isBedrockName(name)) {
                profile.setBedrock(true);
            }
        }

        if (flying) {
            handleFlying(event, user, uuid, st, profile, now);
        } else if (interact) {
            handleInteract(event, user, uuid, st, profile, now);
        } else {
            st.lastSwingMs = now;
            st.noSwingDeadline = 0L;
        }
    }

    // ===================================================================== HAREKET / ROTASYON PAKETLERI

    private void handleFlying(PacketReceiveEvent event, User user, UUID uuid, PacketState st,
                              PlayerCheckProfile profile, long now) {
        WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
        Location loc = w.getLocation();
        boolean pos = w.hasPositionChanged();
        boolean rot = w.hasRotationChanged();

        // --- 1) Kesin gecersiz degerler (herkes icin gecerli; cokertme / exploit paketleri) ---
        if (pos) {
            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                event.setCancelled(true);
                disconnect(user, "InvalidPosition", "NaN/sonsuz konum");
                return;
            }
            if (Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7 || Math.abs(y) > 3.0E7) {
                event.setCancelled(true);
                flagAsync(uuid, Category.PACKET, "InvalidPosition", "asiri koordinat", 2.0, Severity.HARD);
                return;
            }
        }
        if (rot) {
            float yaw = loc.getYaw();
            float pitch = loc.getPitch();
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                event.setCancelled(true);
                disconnect(user, "InvalidRotation", "NaN/sonsuz rotasyon");
                return;
            }
            if (Math.abs(pitch) > 90.0f) {
                // Gercek istemci pitch'i +-90'a sinirlar. Paketi duzeltip yolla, hileyi logla.
                loc.setPitch(pitch > 0.0f ? 90.0f : -90.0f);
                w.setLocation(loc);
                event.markForReEncode(true);
                if ((now - st.pitchWindowStart) > 5000L) {
                    st.pitchWindowStart = now;
                    st.pitchViolations = 0;
                }
                st.pitchViolations++;
                if (st.pitchViolations == 1 || st.pitchViolations % 20 == 0) {
                    flagAsync(uuid, Category.PACKET, "InvalidPitch",
                            "pitch=" + String.format(java.util.Locale.ROOT, "%.1f", pitch) + " (>90, duzeltildi)", 1.0, Severity.NORMAL);
                }
            }
        }

        if (!packetChecksEnabled || plugin.isUuidBypassed(uuid) || profile.isBedrock()) {
            return;
        }

        checkNoSwing(uuid, st, now);

        // --- 2) Hareket paketi seli (zamanlayici / paket patlatma) ---
        if ((now - st.flyWindowStart) >= 1000L) {
            st.flyWindowStart = now;
            st.flyCount = 0;
        }
        st.flyCount++;
        if (st.flyCount > maxFlyingPerSecond) {
            event.setCancelled(true);
            st.floodHits++;
            if (st.floodHits == 25) {
                st.floodHits = 0;
                flagAsync(uuid, Category.PACKET, "PacketFlood", "saniyede " + st.flyCount + " hareket paketi", 1.5, Severity.NORMAL);
            }
            if (st.flyCount > maxFlyingPerSecond * 4) {
                disconnect(user, "PacketFlood", "saniyede " + st.flyCount + " hareket paketi");
            }
            return;
        }

        // --- 3) Rotasyon ornegi ---
        if (rot) {
            float yaw = loc.getYaw();
            float pitch = loc.getPitch();
            if (!profile.isPacketRotationInit()) {
                profile.addRotation(new RotationSample(now, yaw, pitch, 0.0f, 0.0f));
            } else {
                float dy = wrapDegrees(yaw - profile.getPacketYaw());
                float dp = pitch - profile.getPacketPitch();
                if (profile.inGrace(now)) {
                    // Sunucu kaynakli donus/isinlanma: ani sicrama sahte "snap" uretmesin
                    dy = 0.0f;
                    dp = 0.0f;
                }
                profile.addRotation(new RotationSample(now, yaw, pitch, dy, dp));
                float mag = (float) Math.hypot(dy, dp);
                if (mag >= snapTurnMin) {
                    profile.recordFastTurn(mag, now);
                }
            }
            profile.setPacketRotation(yaw, pitch);
        }
    }

    // ===================================================================== SALDIRI PAKETLERI

    private void handleInteract(PacketReceiveEvent event, User user, UUID uuid, PacketState st,
                                PlayerCheckProfile profile, long now) {
        WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
        if (w.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }
        int id = w.getEntityId();

        // Kendine saldiri: gecersiz paket (exploit)
        int selfId = user.getEntityId();
        if (selfId > 0 && id == selfId) {
            event.setCancelled(true);
            disconnect(user, "SelfAttack", "kendi varligina saldiri paketi");
            return;
        }

        if (!packetChecksEnabled || plugin.isUuidBypassed(uuid) || profile.isBedrock()) {
            return;
        }

        // Tuzak NPC'ye saldiri: sunucu bu varligi tanimaz, paketi dusur ve ana thread'de degerlendir
        if (plugin.getDecoyManager() != null && plugin.getDecoyManager().isDecoyEntityId(id)) {
            event.setCancelled(true);
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.getDecoyManager().onDecoyAttackPacket(uuid, id, now));
            }
            return;
        }

        checkNoSwing(uuid, st, now);

        // --- Saldiri paketi seli ---
        st.attackTimes[st.attackIdx & 63] = now;
        st.attackIdx++;
        int count = 0;
        int limit = Math.min(64, st.attackIdx);
        for (int i = 0; i < limit; i++) {
            if ((now - st.attackTimes[i]) <= 1000L) {
                count++;
            }
        }
        if (count > maxAttacksPerSecond) {
            event.setCancelled(true);
            if (count == attackFloodFlagAt) {
                flagAsync(uuid, Category.PACKET, "AttackFlood", "saniyede " + count + " saldiri paketi", 1.0, Severity.NORMAL);
            }
            return;
        }

        // --- Ayni anda birden fazla farkli hedef (multi-aura) ---
        boolean newBurst = (now - st.lastAttackMs) > 12L;
        if (newBurst) {
            st.burstCount = 0;
        }
        boolean known = false;
        for (int i = 0; i < st.burstCount; i++) {
            if (st.burstTargets[i] == id) {
                known = true;
                break;
            }
        }
        if (!known && st.burstCount < st.burstTargets.length) {
            st.burstTargets[st.burstCount++] = id;
        }
        st.lastAttackMs = now;
        if (st.burstCount >= 3) {
            event.setCancelled(true);
            double decayed = Math.max(0.0, st.multiBuf - (((now - st.multiBufMs) / 1000.0) * 0.3));
            st.multiBuf = decayed + 1.0;
            st.multiBufMs = now;
            if (st.multiBuf >= 2.0) {
                st.multiBuf = 0.0;
                flagAsync(uuid, Category.KILLAURA, "MultiAura", "ayni anda 3+ farkli hedefe saldiri", 2.0, Severity.NORMAL);
            }
            return;
        }

        profile.recordAttackPacket(id, now);

        // Kolsuz vurus: 1.9+ istemcide salinma paketi saldiridan hemen SONRA, 1.8'de hemen ONCE gelir
        st.noSwingDeadline = ((now - st.lastSwingMs) > 150L) ? (now + 150L) : 0L;
    }

    private void checkNoSwing(UUID uuid, PacketState st, long now) {
        if (st.noSwingDeadline != 0L && now > st.noSwingDeadline) {
            st.noSwingDeadline = 0L;
            double decayed = Math.max(0.0, st.noSwingBuf - (((now - st.noSwingBufMs) / 1000.0) * 0.3));
            st.noSwingBuf = decayed + 1.0;
            st.noSwingBufMs = now;
            if (st.noSwingBuf >= noSwingBufferNeeded) {
                st.noSwingBuf = 0.0;
                flagAsync(uuid, Category.KILLAURA, "NoSwing", "kol sallamadan saldiri", 1.5, Severity.NORMAL);
            }
        }
    }

    // ===================================================================== YARDIMCILAR

    private void disconnect(User user, String check, String detail) {
        plugin.getViolationLogger().logRaw(String.valueOf(user.getName()), "PACKET", check, detail,
                kickOnInvalid ? "KICK" : "LOG", 0);
        if (kickOnInvalid) {
            try {
                user.closeConnection();
            } catch (Throwable ignored) {
                // baglanti zaten kapali
            }
        }
    }

    /** Netty thread'inden ana thread'e gecip VL ekler ve loglar/cezalandirir. */
    private void flagAsync(UUID uuid, Category category, String check, String detail, double vl, Severity severity) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || plugin.isPlayerBypassed(p)) {
                return;
            }
            PlayerCheckProfile prof = plugin.getOrCreateProfile(p);
            long now = System.currentTimeMillis();
            if (severity != Severity.SOFT) {
                prof.addHeuristicVl(vl, now);
            }
            plugin.getPunishmentManager().flag(p, prof, now, category, check, detail, severity);
        });
    }

    private static float wrapDegrees(float value) {
        float v = value % 360.0f;
        if (v >= 180.0f) {
            v -= 360.0f;
        }
        if (v < -180.0f) {
            v += 360.0f;
        }
        return v;
    }
}
