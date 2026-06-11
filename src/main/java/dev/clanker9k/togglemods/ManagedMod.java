package dev.clanker9k.togglemods;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** One jar in the mods folder, plus the toggle state the user is editing. */
public final class ManagedMod {
    /** Current file on disk (may end in .jar or .jar.disabled). */
    public Path path;
    /** Mod id(s) this jar provides (populated for loaded jars; for dependency analysis). */
    public final Set<String> providedIds = new HashSet<>();
    /** Mod id(s) this jar hard-depends on (DEPENDS kind), for dependency analysis. */
    public final Set<String> requiredIds = new HashSet<>();
    /** Human-readable name pulled from fabric.mod.json when available. */
    public final String displayName;
    /** Mod id(s) contained in the jar, for display. */
    public final String modIds;
    /** Whether the jar is currently enabled on disk (.jar, not .jar.disabled). */
    public final boolean enabledOnDisk;
    /** Whether Fabric actually has this jar loaded in the running game right now. */
    public final boolean activeNow;
    /** The state the user wants. Starts equal to activeNow, or the saved choice. */
    public boolean desiredEnabled;
    /** Protected jars (this manager, relauncher, fabric loader/api) can't be toggled. */
    public final boolean locked;

    public ManagedMod(Path path, String displayName, String modIds,
                      boolean enabledOnDisk, boolean activeNow, boolean locked) {
        this.path = path;
        this.displayName = displayName;
        this.modIds = modIds;
        this.enabledOnDisk = enabledOnDisk;
        this.activeNow = activeNow;
        this.desiredEnabled = activeNow;
        this.locked = locked;
    }

    /**
     * True if the desired state differs from what's actually running this
     * session - i.e. a restart is needed to reach it. This stays true (asterisk
     * shown) for a toggled-but-not-yet-restarted mod, even after the choice has
     * been saved to disk/the pending store.
     */
    public boolean isDirty() {
        return desiredEnabled != activeNow;
    }

    public String fileName() {
        return path.getFileName().toString();
    }

    /** Jar name without the {@code .disabled} suffix - stable across toggles. */
    public String baseFileName() {
        String f = fileName();
        return f.endsWith(ModFileManager.DISABLED_SUFFIX)
                ? f.substring(0, f.length() - ModFileManager.DISABLED_SUFFIX.length())
                : f;
    }
}
