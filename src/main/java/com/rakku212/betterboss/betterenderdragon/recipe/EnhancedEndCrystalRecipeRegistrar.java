package com.rakku212.betterboss.betterenderdragon.recipe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import com.rakku212.betterboss.betterenderdragon.item.EnhancedEndCrystalItem;

public final class EnhancedEndCrystalRecipeRegistrar {

    public static final String RECIPE_KEY = "enhanced_end_crystal";

    public static void register(JavaPlugin plugin) {
        NamespacedKey key = new NamespacedKey(plugin, RECIPE_KEY);
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, EnhancedEndCrystalItem.create(plugin));
        recipe.shape("ABA", "CDC", "ACA");
        recipe.setIngredient('A', Material.NETHER_STAR);
        recipe.setIngredient('B', Material.ENDER_EYE);
        recipe.setIngredient('C', Material.DRAGON_BREATH);
        recipe.setIngredient('D', Material.END_CRYSTAL);
        Bukkit.addRecipe(recipe);
    }
}
