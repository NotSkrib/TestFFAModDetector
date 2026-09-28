package xyz.nim.modDetectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class Msg {
    public static final TextColor ACCENT = TextColor.color((int)7259903);
    public static final String BRAND = "ErrorSMP Mod Detector";

    private Msg() {
    }

    public static Component prefix() {
        return ((TextComponent)Component.text((String)"[", (TextColor)NamedTextColor.DARK_GRAY).append((Component)Component.text((String)BRAND, (TextColor)ACCENT, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)"] ", (TextColor)NamedTextColor.DARK_GRAY));
    }

    public static Component prefixed(Component component) {
        return Msg.prefix().append(component);
    }

    public static Component error(String string) {
        return Msg.prefixed((Component)Component.text((String)string, (TextColor)NamedTextColor.RED));
    }

    public static Component divider() {
        return Component.text((String)"\u25ac".repeat(32), (TextColor)NamedTextColor.DARK_GRAY);
    }
}


