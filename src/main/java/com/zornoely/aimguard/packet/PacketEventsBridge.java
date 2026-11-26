package com.zornoely.aimguard.packet;

import com.zornoely.aimguard.ZornoAimGuardPlugin;
import com.zornoely.aimguard.profile.PlayerCheckProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketEventsBridge {

    private record LastAngle(float yaw, float pitch, double prevDeltaYaw, double prevDeltaPitch) {}

    private static final int MAX_ROTATION_SAMPLES = 64;

    private final ZornoAimGuardPlugin plugin;
    private final Map<UUID, LastAngle> lastAngles = new ConcurrentHashMap<>();
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
        lastAngles.clear();
        hooked = false;
    }

    public void clearPlayer(UUID uuid) {
        lastAngles.remove(uuid);
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
                extractAndRecordRotation(uuid, eventObj, profile, now);
            } else if (packetName.contains("INTERACT_ENTITY")) {
                extractAndRecordInteract(uuid, eventObj, profile, now);
            }
        } catch (Throwable ignored) {
        }
    }

    private void extractAndRecordRotation(UUID uuid, Object eventObj, PlayerCheckProfile profile, long now) {
        try {
            Object wrapper = eventObj.getClass()
                    .getConstructor(eventObj.getClass())
                    .newInstance(eventObj);

            float yaw = readFloat(wrapper, "getYaw");
            float pitch = readFloat(wrapper, "getPitch");

            LastAngle prev = lastAngles.get(uuid);
            if (prev == null) {
                lastAngles.put(uuid, new LastAngle(yaw, pitch, 0.0, 0.0));
                return;
            }

            double deltaYaw = Math.abs(yaw - prev.yaw());
            if (deltaYaw > 180.0) {
                deltaYaw = 360.0 - deltaYaw;
            }
            double deltaPitch = Math.abs(pitch - prev.pitch());

            double gcdYaw = computeGcd(deltaYaw, prev.prevDeltaYaw());
            double gcdPitch = computeGcd(deltaPitch, prev.prevDeltaPitch());

            double snapThreshold = plugin.getPunishmentManager().getPunishmentsConfig()
                    .getDouble("combat.snap-turn-min-deg", 52.0);
            boolean snapped = deltaYaw >= snapThreshold || deltaPitch >= (snapThreshold * 0.85);

            if (snapped) {
                profile.recordFastTurn((float) Math.max(deltaYaw, deltaPitch), now);
            }

            Deque<PlayerCheckProfile.RotationSample> samples = profile.getRotationSamples();
            samples.addLast(new PlayerCheckProfile.RotationSample(
                    now,
                    (float) deltaYaw,
                    (float) deltaPitch,
                    gcdYaw,
                    gcdPitch,
                    snapped
            ));
            while (samples.size() > MAX_ROTATION_SAMPLES) {
                samples.pollFirst();
            }

            lastAngles.put(uuid, new LastAngle(yaw, pitch, deltaYaw, deltaPitch));
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

    private double computeGcd(double current, double previous) {
        double a = Math.abs(current);
        double b = Math.abs(previous);
        if (a < 1e-4) {
            return b;
        }
        if (b < 1e-4) {
            return a;
        }
        int guard = 0;
        while (b > 1e-4 && guard++ < 16) {
            double temp = a % b;
            a = b;
            b = temp;
        }
        return a;
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
