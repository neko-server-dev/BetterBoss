package com.rakku212.betterboss.betterenderdragon.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.rakku212.betterboss.betterenderdragon.item.EnhancedEndCrystalItem;

public final class EnhancedEndCrystalCraftListener implements Listener {

    private final JavaPlugin plugin;

    public EnhancedEndCrystalCraftListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        if (matrix.length < 9) {
            return;
        }
        if (!EnhancedEndCrystalItem.matchesCraftMatrix(matrix, plugin)) {
            return;
        }
        event.getInventory().setResult(EnhancedEndCrystalItem.create(plugin));
    }
}
