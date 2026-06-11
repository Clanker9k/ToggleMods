package dev.clanker9k.togglemods;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Persists the user's desired enable/disable choices across menu re-opens and
 * game launches, in {@code config/togglemods.properties}. Keyed by the base
 * jar name (without the {@code .disabled} suffix, which is stable across
 * toggles); value is {@code true} = want enabled, {@code false} = want disabled.
 *
 * <p>This is what makes a change "stick": a disable can't physically rename a
 * loaded jar mid-session, so the intent is stored here and the file is renamed
 * on game exit (see {@link RelaunchHelper#applyOnExit}). Re-opening the screen
 * reads this back so the row still shows the pending state with an asterisk.
 */
public final class PendingStore {
    private PendingStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("togglemods.properties");
    }

    public static Map<String, Boolean> load() {
        Map<String, Boolean> map = new HashMap<>();
        Path f = file();
        if (!Files.isRegularFile(f)) return map;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            p.load(r);
        } catch (Throwable t) {
            ToggleMods.LOGGER.warn("[ToggleMods] Could not read pending state", t);
            return map;
        }
        for (String key : p.stringPropertyNames()) {
            map.put(key, Boolean.parseBoolean(p.getProperty(key)));
        }
        return map;
    }

    public static void save(Map<String, Boolean> desired) {
        Properties p = new Properties();
        for (Map.Entry<String, Boolean> e : desired.entrySet()) {
            p.setProperty(e.getKey(), Boolean.toString(e.getValue()));
        }
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "ToggleMods pending toggle state (baseJarName=desiredEnabled). "
                        + "Applied to the mods folder on game exit.");
            }
        } catch (Throwable t) {
            ToggleMods.LOGGER.error("[ToggleMods] Failed to save pending state", t);
        }
    }
}
