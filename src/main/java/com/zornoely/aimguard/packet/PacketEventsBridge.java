package com.zornoely.aimguard.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.zornoely.aimguard.ZornoAimGuardPlugin;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

import java.util.UUID;

/**
 * PacketEvents yasam dongusu. Onceki surum yalnizca "PacketEvents" adli AYRI bir eklenti arar ve yansima ile
 * baglanmaya calisirdi; jar'a gomulu (shade) kopya ise hic baslatilmadigi icin paket dinleyici hicbir zaman
 * calismiyor (rotasyon/saldiri paketleri kaydedilmiyor, NPC paketleri gonderilemiyordu).
 * Simdi gomulu kopya onLoad/onEnable/onDisable'da duzgun baslatilir.
 */
public final class PacketEventsBridge {

    private final ZornoAimGuardPlugin plugin;
    private AimGuardPacketListener listener;
    private volatile boolean loaded;
    private volatile boolean hooked;
    private volatile String lastError = "";

    public PacketEventsBridge(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** onLoad: API'yi olusturur ve yukler. */
    public boolean load() {
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
            PacketEvents.getAPI().getSettings()
                    .reEncodeByDefault(false)
                    .checkForUpdates(false)
                    .bStats(false);
            PacketEvents.getAPI().load();
            loaded = true;
            return true;
        } catch (Throwable ex) {
            lastError = String.valueOf(ex);
            plugin.getLogger().severe("PacketEvents yuklenemedi, Bukkit (paketsiz) moduna geciliyor: " + ex);
            return false;
        }
    }

    /** onEnable: baslatir ve dinleyiciyi kaydeder. */
    public boolean hook() {
        if (hooked) {
            return true;
        }
        if (!loaded) {
            return false;
        }
        try {
            PacketEvents.getAPI().init();
            listener = new AimGuardPacketListener(plugin);
            PacketEvents.getAPI().getEventManager().registerListener(listener);
            hooked = true;
            plugin.getLogger().info("PacketEvents aktif: paket dogrulama, rotasyon analizi ve tuzak NPC calisiyor.");
            return true;
        } catch (Throwable ex) {
            lastError = String.valueOf(ex);
            plugin.getLogger().severe("PacketEvents baslatilamadi, Bukkit (paketsiz) moduna geciliyor: " + ex);
            return false;
        }
    }

    public void unhook() {
        try {
            if (hooked && PacketEvents.getAPI() != null) {
                if (listener != null) {
                    PacketEvents.getAPI().getEventManager().unregisterListener(listener);
                }
                PacketEvents.getAPI().terminate();
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("PacketEvents kapatilirken hata: " + ex);
        }
        hooked = false;
        loaded = false;
    }

    public boolean isHooked() {
        return hooked;
    }

    public String getLastError() {
        return lastError;
    }

    public void refreshConfig() {
        if (listener != null) {
            listener.refreshConfig();
        }
    }

    public void clearPlayer(UUID uuid) {
        if (listener != null) {
            listener.clearPlayer(uuid);
        }
    }
}
