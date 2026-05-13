package com.rakku212.util.persistentdata;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

public final class PersistentDataFlags {

    public static void setByteFlag(PersistentDataHolder holder, NamespacedKey key, boolean enabled) {
        if (holder == null || key == null) {
            return;
        }
        if (enabled) {
            holder.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        } else {
            holder.getPersistentDataContainer().remove(key);
        }
    }

    public static boolean hasByteFlag(PersistentDataHolder holder, NamespacedKey key) {
        if (holder == null || key == null) {
            return false;
        }
        return holder.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public static void setItemByteFlag(ItemStack stack, NamespacedKey key, boolean enabled) {
        if (stack == null || key == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        setByteFlag(meta, key, enabled);
        stack.setItemMeta(meta);
    }

    public static boolean hasItemByteFlag(ItemStack stack, NamespacedKey key) {
        if (stack == null || key == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return hasByteFlag(meta, key);
    }
}
