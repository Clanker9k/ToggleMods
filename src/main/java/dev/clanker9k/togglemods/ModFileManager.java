package dev.clanker9k.togglemods;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Enumerates jars in the mods folder and enables/disables them by renaming
 * between {@code foo.jar} and {@code foo.jar.disabled} - the de-facto standard
 * that Fabric Loader (and most launchers) honour: only {@code *.jar} is loaded.
 *
 * Nothing here unloads a running mod. Changes are written to disk and take
 * effect on the next JVM start (see {@link RelaunchHelper}).
 */
public final class ModFileManager {
    public static final String DISABLED_SUFFIX = ".disabled";

    private ModFileManager() {}

    public static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    /** Jars we refuse to disable because doing so would break the toggler itself. */
    private static final String[] LOCKED_NAME_HINTS = {
            "togglemods", "relauncher", "fabric-api", "fabric-loader", "fabricloader"
    };

    private static boolean isLockedName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (String hint : LOCKED_NAME_HINTS) {
            if (n.contains(hint)) return true;
        }
        return false;
    }

    /** filename(without .disabled) -> "Pretty Name" for jars Fabric already loaded. */
    private static Map<String, String> loadedJarNames() {
        Map<String, String> byFile = new HashMap<>();
        Path mods = modsDir().toAbsolutePath().normalize();
        for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
            try {
                for (Path p : mc.getOrigin().getPaths()) {
                    Path abs = p.toAbsolutePath().normalize();
                    if (abs.startsWith(mods)) {
                        String file = abs.getFileName().toString();
                        String pretty = mc.getMetadata().getName();
                        byFile.merge(file, pretty, (a, b) -> a + ", " + b);
                    }
                }
            } catch (Throwable ignored) {
                // Some origins (e.g. nested/builtin) don't expose usable paths.
            }
        }
        return byFile;
    }

    /** Read mod name + id straight from a (possibly disabled) jar's fabric.mod.json. */
    private static String[] readJarMeta(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                String json = sb.toString();
                String name = crudeJsonValue(json, "name");
                String id = crudeJsonValue(json, "id");
                return new String[]{ name, id };
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Tiny dependency-free extractor: finds "key" : "value". Good enough for display.
    private static String crudeJsonValue(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** Scan the mods folder. Returns one entry per top-level jar (enabled or disabled). */
    public static List<ManagedMod> scan() {
        List<ManagedMod> out = new ArrayList<>();
        Path mods = modsDir();
        if (!Files.isDirectory(mods)) return out;

        Map<String, String> loaded = loadedJarNames();
        Map<String, Boolean> pending = PendingStore.load();

        try (Stream<Path> stream = Files.list(mods)) {
            List<Path> jars = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jar") || n.endsWith(".jar" + DISABLED_SUFFIX)) {
                    jars.add(p);
                }
            });

            for (Path jar : jars) {
                String file = jar.getFileName().toString();
                boolean enabled = file.toLowerCase(Locale.ROOT).endsWith(".jar");
                String baseFile = enabled ? file : file.substring(0, file.length() - DISABLED_SUFFIX.length());

                String name = loaded.get(baseFile);
                String ids = null;
                if (name == null) {
                    String[] meta = readJarMeta(jar);
                    if (meta != null) {
                        name = meta[0];
                        ids = meta[1];
                    }
                }
                if (name == null || name.isBlank()) name = baseFile;

                boolean active = loaded.containsKey(baseFile);
                ManagedMod mm = new ManagedMod(jar, name, ids == null ? "" : ids,
                        enabled, active, isLockedName(baseFile));

                // Restore the user's saved choice so toggles stick across re-opens.
                Boolean want = pending.get(baseFile);
                if (want != null && !mm.locked) {
                    mm.desiredEnabled = want;
                }
                out.add(mm);
            }
        } catch (Throwable t) {
            ToggleMods.LOGGER.error("[ToggleMods] Failed to scan mods folder", t);
        }

        fillDependencyInfo(out);

        // Unlocked mods first (alphabetical), then locked/essential jars pinned
        // to the bottom (also alphabetical).
        out.sort(Comparator.comparing((ManagedMod m) -> m.locked)
                .thenComparing(m -> m.displayName.toLowerCase(Locale.ROOT)));
        return out;
    }

    /**
     * Compute the {@code [from, to]} rename for every pending, unlocked toggle,
     * without touching the disk. The actual moves are performed later (in
     * process on POSIX, or by a post-exit helper on Windows where loaded jars
     * are locked) - see {@link RelaunchHelper}.
     */
    public static List<Path[]> computePendingRenames(List<ManagedMod> mods) {
        List<Path[]> ops = new ArrayList<>();
        for (ManagedMod m : mods) {
            // Compare to the on-disk state: only jars whose file actually needs
            // renaming. (A mod can be "dirty" yet need no rename - e.g. enabled
            // on disk but not loaded this session - in which case a restart alone
            // realises it.)
            if (m.locked || m.desiredEnabled == m.enabledOnDisk) continue;

            Path current = m.path;
            String file = current.getFileName().toString();
            Path target;
            if (m.desiredEnabled) {
                // enable: strip .disabled
                String enabledName = file.endsWith(DISABLED_SUFFIX)
                        ? file.substring(0, file.length() - DISABLED_SUFFIX.length())
                        : file;
                target = current.resolveSibling(enabledName);
            } else {
                // disable: append .disabled
                target = current.resolveSibling(file + DISABLED_SUFFIX);
            }
            if (!target.equals(current)) ops.add(new Path[]{ current, target });
        }
        return ops;
    }

    /**
     * Write all pending toggles to disk immediately. Returns the number of jars
     * renamed. Throws if any rename fails. Safe on POSIX (renaming open files is
     * fine); on Windows a currently-loaded {@code .jar} is locked and this will
     * throw - use the deferred path in {@link RelaunchHelper} there instead.
     */
    public static int applyChanges(List<ManagedMod> mods) throws Exception {
        int changed = 0;
        for (Path[] op : computePendingRenames(mods)) {
            Files.move(op[0], op[1], StandardCopyOption.ATOMIC_MOVE);
            changed++;
            ToggleMods.LOGGER.info("[ToggleMods] {} -> {}",
                    op[0].getFileName(), op[1].getFileName());
        }
        return changed;
    }

    public static int pendingCount(List<ManagedMod> mods) {
        int c = 0;
        for (ManagedMod m : mods) if (!m.locked && m.isDirty()) c++;
        return c;
    }

    /**
     * Persist the user's current choices so they survive menu re-opens and take
     * effect on the next launch. Only entries that differ from the running state
     * are stored (the file is fully rewritten each call).
     */
    public static void savePending(List<ManagedMod> mods) {
        Map<String, Boolean> map = new HashMap<>();
        for (ManagedMod m : mods) {
            if (!m.locked && m.desiredEnabled != m.activeNow) {
                map.put(m.baseFileName(), m.desiredEnabled);
            }
        }
        PendingStore.save(map);
    }

    // ------------------------------------------------------- dependency awareness

    /**
     * Fill {@link ManagedMod#providedIds}/{@link ManagedMod#requiredIds} for the
     * currently-loaded jars from Fabric's metadata (we can only read deps for
     * mods Fabric actually loaded this session).
     */
    private static void fillDependencyInfo(List<ManagedMod> mods) {
        Map<String, ManagedMod> byBase = new HashMap<>();
        for (ManagedMod m : mods) {
            byBase.put(m.baseFileName().toLowerCase(Locale.ROOT), m);
        }
        Path modsAbs = modsDir().toAbsolutePath().normalize();
        for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
            try {
                for (Path p : mc.getOrigin().getPaths()) {
                    Path abs = p.toAbsolutePath().normalize();
                    if (!abs.startsWith(modsAbs)) continue;
                    ManagedMod m = byBase.get(abs.getFileName().toString().toLowerCase(Locale.ROOT));
                    if (m == null) continue;
                    m.providedIds.add(mc.getMetadata().getId());
                    for (ModDependency d : mc.getMetadata().getDependencies()) {
                        if (d.getKind() == ModDependency.Kind.DEPENDS) {
                            m.requiredIds.add(d.getModId());
                        }
                    }
                }
            } catch (Throwable ignored) {
                // some origins (nested/builtin) don't expose usable paths
            }
        }
    }

    /**
     * The set of currently desired-enabled mods that would lose a required
     * dependency if {@code target} were disabled, computed transitively (a mod
     * that breaks can in turn break its own dependents). Excludes {@code target}.
     */
    public static Set<ManagedMod> dependentsBrokenByDisabling(ManagedMod target, List<ManagedMod> all) {
        Set<ManagedMod> off = new LinkedHashSet<>();
        off.add(target);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ManagedMod y : all) {
                if (off.contains(y) || y.locked || !y.desiredEnabled) continue;
                for (String req : y.requiredIds) {
                    boolean removedByOff = off.stream().anyMatch(o -> o.providedIds.contains(req));
                    if (!removedByOff) continue;
                    boolean stillProvided = all.stream()
                            .anyMatch(o -> !off.contains(o) && o.desiredEnabled && o.providedIds.contains(req));
                    if (!stillProvided) {
                        off.add(y);
                        changed = true;
                        break;
                    }
                }
            }
        }
        off.remove(target);
        return off;
    }
}
