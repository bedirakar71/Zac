package com.zornoely.aimguard.packet;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

public final class PacketEventsBridge {

    private final ZornoAimGuardPlugin plugin;
    private boolean hooked;

    public PacketEventsBridge(ZornoAimGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hook() {
        if (hooked) {
            return true;
        }
        if (Bukkit.getPluginManager().getPlugin("PacketEvents") == null) {
            plugin.getLogger().info("PacketEvents bulunamadi; Bukkit olaylariyla devam ediliyor.");
            return false;
        }

        try {
            Class<?> peClass = Class.forName("com.github.retrooper.packetevents.PacketEvents");
            Object api = peClass.getMethod("getAPI").invoke(null);
            if (api == null) {
                return false;
            }

            Object eventManager = api.getClass().getMethod("getEventManager").invoke(api);
            Class<?> listenerClass = Class.forName("com.github.retrooper.packetevents.event.PacketListener");
            Class<?> priorityClass = Class.forName("com.github.retrooper.packetevents.event.PacketListenerPriority");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object normalPriority = Enum.valueOf((Class<Enum>) priorityClass, "NORMAL");

            Object proxyListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    new PacketInvocationHandler()
            );

            eventManager.getClass()
                    .getMethod("registerListener", listenerClass, priorityClass)
                    .invoke(eventManager, proxyListener, normalPriority);

            hooked = true;
            plugin.getLogger().info("PacketEvents koprusu aktif edildi.");
            return true;
        } catch (Throwable ex) {
            plugin.getLogger().warning("PacketEvents baglantisi kurulurken fallback moduna gecildi: " + ex.getMessage());
            return false;
        }
    }

    public void unhook() {
        hooked = false;
    }

    public boolean isHooked() {
        return hooked;
    }

    private final class PacketInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("onPacketReceive".equals(name) && args != null && args.length >= 1) {
                handlePacketReceive(args[0]);
            }
            return null;
        }
    }

    private void handlePacketReceive(Object eventObj) {
        if (eventObj == null) {
            return;
        }
        try {
            Object user = eventObj.getClass().getMethod("getUser").invoke(eventObj);
            if (user == null) {
                return;
            }
            UUID uuid = (UUID) user.getClass().getMethod("getUUID").invoke(user);
            if (uuid == null) {
                return;
            }

            long now = System.currentTimeMillis();
            PlayerCheckProfile profile = plugin.getProfileIfPresent(uuid);
            if (profile == null) {
                return;
            }

            Object packetTypeObj = eventObj.getClass().getMethod("getPacketType").invoke(eventObj);
            String packetName = packetTypeObj != null ? packetTypeObj.toString() : "";

            if (packetName.contains("ROTATION") || packetName.contains("POSITION_ROTATION") || packetName.contains("PLAYER_ROTATION")) {
                extractAndRecordRotation(eventObj, profile, now);
            } else if (packetName.contains("INTERACT_ENTITY")) {
                extractAndRecordInteract(uuid, eventObj, profile, now);
            }
        } catch (Throwable ignored) {
        }
    }

    private void extractAndRecordRotation(Object eventObj, PlayerCheckProfile profile, long now) {
        try {
            Object wrapper = eventObj.getClass()
                    .getConstructor(eventObj.getClass())
                    .newInstance(eventObj);

            float yaw = readFloat(wrapper, "getYaw");
            readFloat(wrapper, "getPitch");

            var samples = profile.getRotationSamples();
            if (!samples.isEmpty()) {
                PlayerCheckProfile.RotationSample prev = samples.peekLast();
                float dYaw = Math.abs(yaw - prev.deltaYaw());
                if (dYaw > 180.0f) {
                    dYaw = 360.0f - dYaw;
                }
                if (dYaw >= 50.0f) {
                    profile.recordFastTurn(dYaw, now);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void extractAndRecordInteract(UUID uuid, Object eventObj, PlayerCheckProfile profile, long now) {
        try {
            Object wrapper = eventObj.getClass()
                    .getConstructor(eventObj.getClass())
                    .newInstance(eventObj);
            Object action = wrapper.getClass().getMethod("getAction").invoke(wrapper);
            if (action != null && "ATTACK".equalsIgnoreCase(action.toString())) {
                profile.setLastCombatTimeMs(now);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline() && !p.hasPermission("zornoaimguard.bypass")) {
                        plugin.getDecoyManager().evaluateAutoDecoy(p, profile, System.currentTimeMillis());
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private float readFloat(Object target, String methodName) {
        try {
            Object val = target.getClass().getMethod(methodName).invoke(target);
            if (val instanceof Number num) {
                return num.floatValue();
            }
        } catch (Throwable ignored) {
        }
        return 0.0f;
    }
}
