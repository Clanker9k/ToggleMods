package dev.clanker9k.togglemods;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Almost everything interesting lives on the client side
 * (the toggle screen + keybind), but the file-management helpers live here so
 * they could also be driven from a server command later if desired.
 */
public class ToggleMods implements ModInitializer {
    public static final String MOD_ID = "togglemods";
    public static final Logger LOGGER = LoggerFactory.getLogger("ToggleMods");

    @Override
    public void onInitialize() {
        LOGGER.info("[ToggleMods] Mod manager ready. Disable/enable mods, then restart to apply.");
    }
}
