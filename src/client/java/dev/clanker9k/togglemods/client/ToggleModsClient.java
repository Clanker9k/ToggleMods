package dev.clanker9k.togglemods.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.clanker9k.togglemods.ToggleMods;
import dev.clanker9k.togglemods.ModFileManager;
import dev.clanker9k.togglemods.RelaunchHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class ToggleModsClient implements ClientModInitializer {
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        // 26.1: keybind categories are a KeyMapping.Category (a registered
        // Identifier), no longer a raw translation-key String.
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(ToggleMods.MOD_ID, "main"));

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.togglemods.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,                 // default: O (rebindable in Controls)
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new ToggleModsScreen(null));
                }
            }
        });

        // On a normal quit, apply any saved choices to the mods folder (renaming
        // .jar <-> .jar.disabled) so the next launch reflects them. Enables happen
        // in-process; disables of still-loaded jars are handed to a tiny helper
        // that finishes the rename once this process exits. (Apply & Restart uses
        // its own path and never reaches here because it hard-exits.)
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {
                var renames = ModFileManager.computePendingRenames(ModFileManager.scan());
                if (!renames.isEmpty()) {
                    ToggleMods.LOGGER.info("[ToggleMods] Applying {} saved change(s) on exit.", renames.size());
                    RelaunchHelper.applyOnExit(renames);
                }
            } catch (Throwable t) {
                ToggleMods.LOGGER.error("[ToggleMods] Failed to apply changes on exit.", t);
            }
        });

        // Add a folder icon button to the title screen so you can toggle mods
        // without joining a world/server first (like ReplayMod / account switchers).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                TitleFolderButton btn = new TitleFolderButton(6, 6, 20,
                        b -> client.setScreen(new ToggleModsScreen(screen)));
                btn.setTooltip(Tooltip.create(Component.literal("ToggleMods")));
                Screens.getWidgets(screen).add(btn);
            }
        });

        ToggleMods.LOGGER.info("[ToggleMods] Client ready. Press O (default) to open the mod toggler.");
    }

    public static void open(Minecraft client) {
        client.setScreen(new ToggleModsScreen(client.screen));
    }
}
