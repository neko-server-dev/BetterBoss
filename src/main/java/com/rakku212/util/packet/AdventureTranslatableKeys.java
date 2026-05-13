package com.rakku212.util.packet;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

public final class AdventureTranslatableKeys {

    public static boolean matchesTranslatableKey(Component title, String expectedKey) {
        if (title == null) {
            return false;
        }
        if (!(title instanceof TranslatableComponent tc)) {
            return false;
        }
        Object rawKey = tc.key();
        if (rawKey instanceof String s) {
            return expectedKey.equals(s);
        }
        if (rawKey instanceof Key k) {
            return expectedKey.equals(k.asString());
        }
        return false;
    }
}
