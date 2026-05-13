package com.rakku212.util.i18n;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class LocaleComponents {

    public static Component jaOrDefault(Player player, Component japanese, Component fallback) {
        Locale locale = player.locale();
        if (locale != null && "ja".equalsIgnoreCase(locale.getLanguage())) {
            return japanese;
        }
        return fallback;
    }
}
