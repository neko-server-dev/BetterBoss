package com.rakku212.betterboss.betterenderdragon.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import com.rakku212.util.persistentdata.PersistentDataFlags;

public final class EnhancedEndCrystalItem {

    public static final String ENHANCED_ITEM_KEY = "enhanced_end_crystal_item";

    public static NamespacedKey itemTagKey(JavaPlugin plugin) {
        return new NamespacedKey(plugin, ENHANCED_ITEM_KEY);
    }

    public static boolean isEnhanced(ItemStack stack, JavaPlugin plugin) {
        if (stack == null || stack.getType() != Material.END_CRYSTAL || !stack.hasItemMeta()) {
            return false;
        }
        return PersistentDataFlags.hasItemByteFlag(stack, itemTagKey(plugin));
    }

    public static ItemStack create(JavaPlugin plugin) {
        ItemStack stack = new ItemStack(Material.END_CRYSTAL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("強化されたエンドクリスタル", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        PersistentDataFlags.setByteFlag(meta, itemTagKey(plugin), true);
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean matchesCraftMatrix(ItemStack[] matrix, JavaPlugin plugin) {
        if (matrix == null || matrix.length < 9) {
            return false;
        }
        if (matrix[1] == null || matrix[1].getType() != Material.ENDER_EYE) {
            return false;
        }
        if (matrix[4] == null || matrix[4].getType() != Material.END_CRYSTAL || isEnhanced(matrix[4], plugin)) {
            return false;
        }
        int[] netherStars = {0, 2, 6, 8};
        for (int i : netherStars) {
            if (matrix[i] == null || matrix[i].getType() != Material.NETHER_STAR) {
                return false;
            }
        }
        int[] breath = {3, 5, 7};
        for (int i : breath) {
            if (matrix[i] == null || matrix[i].getType() != Material.DRAGON_BREATH) {
                return false;
            }
        }
        return true;
    }
}
