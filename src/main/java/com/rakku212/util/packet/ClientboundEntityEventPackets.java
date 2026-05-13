package com.rakku212.util.packet;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;

import java.lang.reflect.Constructor;

public final class ClientboundEntityEventPackets {

    private static volatile Constructor<ClientboundEntityEventPacket> intByteCtor;

    private static Constructor<ClientboundEntityEventPacket> intByteCtor() {
        Constructor<ClientboundEntityEventPacket> c = intByteCtor;
        if (c != null) {
            return c;
        }
        synchronized (ClientboundEntityEventPackets.class) {
            c = intByteCtor;
            if (c != null) {
                return c;
            }
            for (Constructor<?> raw : ClientboundEntityEventPacket.class.getDeclaredConstructors()) {
                Class<?>[] p = raw.getParameterTypes();
                if (p.length == 2 && p[0] == int.class && p[1] == byte.class) {
                    @SuppressWarnings("unchecked")
                    Constructor<ClientboundEntityEventPacket> typed =
                            (Constructor<ClientboundEntityEventPacket>) raw;
                    typed.setAccessible(true);
                    intByteCtor = typed;
                    return typed;
                }
            }
            throw new IllegalStateException(
                    "ClientboundEntityEventPacket(int,byte) constructor not found; Paper/Minecraft version mismatch"
            );
        }
    }

    public static ClientboundEntityEventPacket forEntityId(int entityId, byte status) {
        try {
            return intByteCtor().newInstance(entityId, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ClientboundEntityEventPacket(int,byte)", e);
        }
    }
}
