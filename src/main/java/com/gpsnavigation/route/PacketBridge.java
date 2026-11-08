package com.gpsnavigation.route;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Отправка пакетов конкретному игроку без серверных сущностей и без сторонних плагинов.
 * Paper 1.20.5+ работает на mojang-маппингах, поэтому классы сервера находятся по их обычным именам
 * (jar помечен paperweight-mappings-namespace: mojang, Paper не переименовывает строки рефлексии).
 * Если что-то не найдено (другая версия ядра), {@link #isAvailable()} = false и плагин использует запасной режим.
 */
public final class PacketBridge {

    private static final byte FLAG_ON_FIRE = 0x01;
    private static final byte FLAG_SNEAKING = 0x02;
    private static final byte FLAG_SPRINTING = 0x08;
    private static final byte FLAG_SWIMMING = 0x10;
    private static final byte FLAG_INVISIBLE = 0x20;
    public static final byte FLAG_GLOWING = 0x40;
    private static final byte FLAG_GLIDING = (byte) 0x80;

    private final Logger logger;
    private boolean available;
    private String failure;

    private Object shulkerBulletType;
    private Object vec3Zero;
    private Constructor<?> addEntity;
    private Constructor<?> setEntityData;
    private Constructor<?> removeEntities;
    private Method dataValueCreate;
    private Object sharedFlags;
    private Object noGravity;
    private Field connectionField;
    private Method sendMethod;
    private AtomicInteger serverCounter;
    private final AtomicInteger ownCounter = new AtomicInteger(Integer.MAX_VALUE / 2);
    private final Map<Class<?>, Method> handleMethods = new ConcurrentHashMap<>();

    public PacketBridge(Logger logger) {
        this.logger = logger;
        init();
    }

    public boolean isAvailable() {
        return available;
    }

    public String failure() {
        return failure;
    }

    private void init() {
        try {
            Class<?> entityTypeCls = Class.forName("net.minecraft.world.entity.EntityType");
            shulkerBulletType = entityTypeCls.getField("SHULKER_BULLET").get(null);
            Class<?> vec3Cls = Class.forName("net.minecraft.world.phys.Vec3");
            vec3Zero = vec3Cls.getField("ZERO").get(null);

            Class<?> addCls = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            for (Constructor<?> c : addCls.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 11 && p[0] == int.class && p[1] == UUID.class && p[2] == double.class
                        && p[7] == entityTypeCls && p[8] == int.class && p[9] == vec3Cls && p[10] == double.class) {
                    addEntity = c;
                    break;
                }
            }
            if (addEntity == null) throw new NoSuchMethodException("ClientboundAddEntityPacket(int, UUID, ..., EntityType, int, Vec3, double)");

            Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
            sharedFlags = staticField(nmsEntity, "DATA_SHARED_FLAGS_ID");
            noGravity = staticField(nmsEntity, "DATA_NO_GRAVITY");
            try {
                serverCounter = (AtomicInteger) staticField(nmsEntity, "ENTITY_COUNTER");
            } catch (ReflectiveOperationException | ClassCastException e) {
                serverCounter = null; // используем собственный счётчик из далёкого диапазона
            }

            Class<?> accessorCls = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
            Class<?> dataValueCls = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
            dataValueCreate = dataValueCls.getMethod("create", accessorCls, Object.class);
            setEntityData = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                    .getConstructor(int.class, List.class);
            removeEntities = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                    .getConstructor(int[].class);

            Class<?> packetCls = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> serverPlayerCls = Class.forName("net.minecraft.server.level.ServerPlayer");
            connectionField = serverPlayerCls.getField("connection");
            sendMethod = connectionField.getType().getMethod("send", packetCls);
            available = true;
        } catch (Throwable t) {
            available = false;
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            logger.warning("Пакетные указатели недоступны (" + failure + ") — будет использован запасной режим с сущностями.");
        }
    }

    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private int nextId() {
        return serverCounter != null ? serverCounter.incrementAndGet() : ownCounter.incrementAndGet();
    }

    private void send(Player player, Object packet) throws ReflectiveOperationException {
        Method getHandle = handleMethods.get(player.getClass());
        if (getHandle == null) {
            getHandle = player.getClass().getMethod("getHandle");
            handleMethods.put(player.getClass(), getHandle);
        }
        Object handle = getHandle.invoke(player);
        Object connection = connectionField.get(handle);
        if (connection != null) sendMethod.invoke(connection, packet);
    }

    private Object data(Object accessor, Object value) throws ReflectiveOperationException {
        return dataValueCreate.invoke(null, accessor, value);
    }

    /** Создаёт у игрока клиентский снаряд шалкера (без сущности на сервере). Возвращает ID или -1. */
    public int spawnBullet(Player player, double x, double y, double z, boolean glowing) {
        if (!available) return -1;
        try {
            int id = nextId();
            Object add = addEntity.newInstance(id, UUID.randomUUID(), x, y, z, 0f, 0f, shulkerBulletType, 0, vec3Zero, 0d);
            Object meta = setEntityData.newInstance(id, List.of(
                    data(sharedFlags, glowing ? FLAG_GLOWING : (byte) 0),
                    data(noGravity, Boolean.TRUE)));
            send(player, add);
            send(player, meta);
            return id;
        } catch (Throwable t) {
            disable(t);
            return -1;
        }
    }

    public void remove(Player player, int... ids) {
        if (!available || ids.length == 0 || !player.isOnline()) return;
        try {
            send(player, removeEntities.newInstance((Object) ids));
        } catch (Throwable t) {
            disable(t);
        }
    }

    /** Отправляет игроку флаги сущности (например, со свечением). Видит только этот игрок. */
    public void sendFlags(Player player, Entity entity, boolean glowing) {
        if (!available || !player.isOnline()) return;
        byte flags = flagsOf(entity);
        if (glowing) flags |= FLAG_GLOWING;
        try {
            send(player, setEntityData.newInstance(entity.getEntityId(), List.of(data(sharedFlags, flags))));
        } catch (Throwable t) {
            disable(t);
        }
    }

    /** Текущие общие флаги сущности по данным сервера (огонь, присед, бег, плавание, невидимость, свечение, полёт). */
    public static byte flagsOf(Entity e) {
        byte f = 0;
        if (e.getFireTicks() > 0) f |= FLAG_ON_FIRE;
        if (e.isGlowing()) f |= FLAG_GLOWING;
        if (e instanceof Player) {
            Player p = (Player) e;
            if (p.isSneaking()) f |= FLAG_SNEAKING;
            if (p.isSprinting()) f |= FLAG_SPRINTING;
        }
        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            if (le.isSwimming()) f |= FLAG_SWIMMING;
            if (le.isInvisible()) f |= FLAG_INVISIBLE;
            if (le.isGliding()) f |= FLAG_GLIDING;
        }
        return f;
    }

    private void disable(Throwable t) {
        if (!available) return;
        available = false;
        failure = t.getClass().getSimpleName() + ": " + t.getMessage();
        logger.warning("Ошибка отправки пакета (" + failure + ") — переключение на запасной режим с сущностями.");
    }
}
