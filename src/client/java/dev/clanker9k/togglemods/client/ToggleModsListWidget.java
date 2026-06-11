package dev.clanker9k.togglemods.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.clanker9k.togglemods.ManagedMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A scrollable list of mods in the style of Mod Menu: each row shows the mod's
 * own icon (read straight from its jar), its name, and an ON/OFF/LOCKED state
 * badge with the file name. Clicking a row toggles it via the parent screen.
 */
public class ToggleModsListWidget extends ObjectSelectionList<ToggleModsListWidget.ModEntry> {
    static final int ICON = 32;

    private final ToggleModsScreen parent;

    public ToggleModsListWidget(Minecraft mc, int width, int height, int top, int itemHeight, ToggleModsScreen parent) {
        super(mc, width, height, top, itemHeight);
        this.parent = parent;
    }

    public void setMods(List<ManagedMod> mods) {
        this.clearEntries();
        for (ManagedMod m : mods) {
            this.addEntry(new ModEntry(m));
        }
    }

    @Override
    public int getRowWidth() {
        return Math.min(this.width - 20, 380);
    }

    public class ModEntry extends Entry<ModEntry> {
        private final ManagedMod mod;
        private boolean iconTried;
        private Identifier icon;   // mod's own icon, or null -> letter placeholder
        private int iconW, iconH;  // source size, for correct scaling into ICON px

        ModEntry(ManagedMod mod) {
            this.mod = mod;
        }

        @Override
        public Component getNarration() {
            return Component.literal(mod.displayName);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
            ToggleModsListWidget.this.setSelected(this);
            parent.onToggle(mod);
            return true;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            final int x = this.getContentX();
            final int y = this.getContentY();
            final int w = this.getContentWidth();
            final Font font = Minecraft.getInstance().font;

            if (hovered) {
                g.fill(x - 2, y - 2, x + w + 2, y + ICON + 2, 0x33FFFFFF);
            }

            // --- icon ---
            ensureIcon();
            if (icon != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F,
                        ICON, ICON, iconW, iconH, iconW, iconH, ARGB.white(1.0F));
            } else {
                g.fill(x, y, x + ICON, y + ICON, placeholderColor(mod.displayName));
                String letter = mod.displayName.isBlank()
                        ? "?" : mod.displayName.substring(0, 1).toUpperCase(Locale.ROOT);
                g.centeredText(font, Component.literal(letter), x + ICON / 2, y + ICON / 2 - 4, 0xFFFFFFFF);
            }

            // --- text column ---
            final int tx = x + ICON + 6;

            String name = mod.displayName + (mod.isDirty() ? " *" : "");
            int nameColor = mod.isDirty() ? 0xFFFFE08A : 0xFFFFFFFF;
            g.text(font, Component.literal(trim(font, name, w - ICON - 10)), tx, y + 2, nameColor, true);

            String badge;
            int badgeColor;
            if (mod.locked) {
                badge = "[LOCKED]";
                badgeColor = 0xFFAAAAAA;
            } else if (mod.desiredEnabled) {
                badge = "[ON]";
                badgeColor = 0xFF74D680;
            } else {
                badge = "[OFF]";
                badgeColor = 0xFFE07070;
            }
            g.text(font, Component.literal(badge), tx, y + 14, badgeColor, true);

            int fnX = tx + font.width(badge + " ") + 2;
            g.text(font, Component.literal(trim(font, mod.fileName(), w - (fnX - x) - 4)),
                    fnX, y + 14, 0xFF9A9A9A, true);
        }

        private void ensureIcon() {
            if (iconTried) return;
            iconTried = true;
            try (ZipFile zip = new ZipFile(mod.path.toFile())) {
                ZipEntry fmj = zip.getEntry("fabric.mod.json");
                if (fmj == null) return;
                String iconPath;
                try (InputStream in = zip.getInputStream(fmj)) {
                    iconPath = findIconPath(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (iconPath == null) return;
                ZipEntry iconEntry = zip.getEntry(iconPath);
                if (iconEntry == null) return;
                NativeImage img;
                try (InputStream in = zip.getInputStream(iconEntry)) {
                    img = NativeImage.read(in);
                }
                iconW = img.getWidth();
                iconH = img.getHeight();
                Identifier id = Identifier.fromNamespaceAndPath("togglemods",
                        "icon/" + sanitize(mod.fileName()));
                Minecraft.getInstance().getTextureManager()
                        .register(id, new DynamicTexture(() -> "togglemods/" + mod.fileName(), img));
                icon = id;
            } catch (Throwable ignored) {
                icon = null; // fall back to the letter placeholder
            }
        }
    }

    /** Find the first icon path in a fabric.mod.json (handles string or per-size object form). */
    private static String findIconPath(String json) {
        int k = json.indexOf("\"icon\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k + 6);
        if (colon < 0) return null;
        // Scan quoted strings after the colon; pick the first that looks like a file path.
        int i = colon + 1;
        while (i < json.length()) {
            int q1 = json.indexOf('"', i);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            String s = json.substring(q1 + 1, q2);
            if (s.endsWith(".png") || s.contains("/")) return s;
            // otherwise it was a size key like "32"; keep scanning (but stop at end of object)
            if (json.indexOf('}', colon) >= 0 && q2 > json.indexOf('}', colon)) return null;
            i = q2 + 1;
        }
        return null;
    }

    private static String sanitize(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static int placeholderColor(String name) {
        int h = name.toLowerCase(Locale.ROOT).hashCode();
        return ARGB.color(0xFF, 70 + (h & 0x5F), 70 + ((h >> 8) & 0x5F), 70 + ((h >> 16) & 0x5F));
    }

    private static String trim(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("...")), false) + "...";
    }
}
