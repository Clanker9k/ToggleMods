package dev.clanker9k.togglemods;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Applies the pending jar renames and restarts the JVM so a fresh Fabric Loader
 * re-scans the (now edited) mods folder. There is no way to truly hot-unload a
 * mod, so a relaunch is the honest mechanism behind the "few clicks" experience.
 *
 * <p>The tricky part is Windows: while the game runs, Fabric keeps every loaded
 * mod jar <b>open</b>, so the OS refuses to rename a {@code .jar} that is
 * currently active. You therefore cannot disable a running mod in-process. The
 * fix is to <b>defer</b> those renames to a tiny helper script that waits for
 * this JVM to exit (releasing the locks), performs the renames, then relaunches.
 *
 * <p>POSIX has no such restriction (renaming open files is fine), so there we
 * rename in-process and use an in-place {@code exec}-style relaunch.
 */
public final class RelaunchHelper {
    private RelaunchHelper() {}

    /**
     * Apply {@code renames} ({@code [from, to]} pairs) and restart the game.
     * On success this never returns (the process is replaced/halted). Returns
     * {@code false} only if the restart could not be started, in which case any
     * renames that were safe to do in-process have still been applied.
     */
    public static boolean applyAndRelaunch(List<Path[]> renames) {
        return isWindows() ? windowsApplyAndRelaunch(renames) : posixApplyAndRelaunch(renames);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ------------------------------------------------------------------ POSIX

    private static boolean posixApplyAndRelaunch(List<Path[]> renames) {
        try {
            for (Path[] op : renames) {
                Files.move(op[0], op[1], StandardCopyOption.ATOMIC_MOVE);
                ToggleMods.LOGGER.info("[ToggleMods] {} -> {}",
                        op[0].getFileName(), op[1].getFileName());
            }
        } catch (Throwable t) {
            ToggleMods.LOGGER.error("[ToggleMods] Failed to apply changes; NOT restarting.", t);
            return false;
        }
        if (tryRelauncherLibrary()) return true;
        return fallbackSpawnAndHalt(buildCommand());
    }

    // ---------------------------------------------------------------- Windows

    /**
     * On Windows, renaming a currently-loaded {@code .jar} fails (the file is
     * locked). So: do the renames whose source is NOT loaded ("enable" ops,
     * source ends in {@code .jar.disabled}) right now - guaranteed - and defer
     * the rest ("disable" ops on loaded jars) plus the relaunch to a helper
     * {@code .bat} that runs after this JVM exits.
     */
    private static boolean windowsApplyAndRelaunch(List<Path[]> renames) {
        List<Path[]> deferred = applyUnlockedNowAndCollectDeferred(renames);
        // Schedule the locked (disable) renames + a relaunch for after we exit.
        if (!spawnHiddenHelper(deferred, windowsRelaunchCommand())) {
            ToggleMods.LOGGER.error("[ToggleMods] Could not schedule Windows relaunch helper. "
                    + "Any enable changes were applied; restart the game manually.");
            return false;
        }
        // Hard-exit so the jar locks release for the helper to do its renames.
        Runtime.getRuntime().halt(0);
        return true;
    }

    /**
     * Persist the pending changes so they take effect on the <b>next</b> launch,
     * without restarting now. Enables apply immediately; on Windows the locked
     * (disable) renames are deferred to a hidden helper that waits for this game
     * to exit normally, then renames - so the next launch reflects them.
     */
    public static boolean applyOnExit(List<Path[]> renames) {
        if (renames.isEmpty()) return true;
        if (!isWindows()) {
            try {
                for (Path[] op : renames) {
                    Files.move(op[0], op[1], StandardCopyOption.ATOMIC_MOVE);
                    ToggleMods.LOGGER.info("[ToggleMods] {} -> {}",
                            op[0].getFileName(), op[1].getFileName());
                }
                return true;
            } catch (Throwable t) {
                ToggleMods.LOGGER.error("[ToggleMods] Failed to save changes for next launch.", t);
                return false;
            }
        }
        List<Path[]> deferred = applyUnlockedNowAndCollectDeferred(renames);
        if (deferred.isEmpty()) return true; // all were enables, already done
        // Empty relaunch command -> helper only renames, no restart.
        boolean ok = spawnHiddenHelper(deferred, List.of());
        if (!ok) {
            ToggleMods.LOGGER.error("[ToggleMods] Could not schedule the on-exit rename helper.");
        }
        return ok;
    }

    /** Do the renames whose source isn't loaded (enables) now; return the rest to defer. */
    private static List<Path[]> applyUnlockedNowAndCollectDeferred(List<Path[]> renames) {
        List<Path[]> deferred = new ArrayList<>();
        for (Path[] op : renames) {
            boolean sourceLoaded = op[0].getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".jar");
            if (sourceLoaded) {
                deferred.add(op); // disabling a live jar - locked, must wait for exit
            } else {
                try { // enabling a .disabled jar - safe to do now
                    Files.move(op[0], op[1], StandardCopyOption.ATOMIC_MOVE);
                    ToggleMods.LOGGER.info("[ToggleMods] {} -> {}",
                            op[0].getFileName(), op[1].getFileName());
                } catch (Throwable t) {
                    deferred.add(op); // fall back to deferring if it somehow fails
                }
            }
        }
        return deferred;
    }

    /**
     * Write the hidden helper (waits for this PID to exit, does {@code deferred}
     * renames, then runs {@code command} if non-empty) and launch it detached
     * with no console window. Does NOT halt the JVM.
     */
    private static boolean spawnHiddenHelper(List<Path[]> deferred, List<String> command) {
        try {
            File vbs = File.createTempFile("togglemods-", ".vbs");
            File bat = writeWindowsHelper(deferred, command, vbs);

            // WScript.Shell.Run with window-style 0 = hidden, wait = false.
            String vbsScript =
                    "Set s = CreateObject(\"WScript.Shell\")\r\n"
                  + "s.Run \"cmd /c \" & Chr(34) & \"" + bat.getAbsolutePath() + "\" & Chr(34), 0, False\r\n";
            Files.writeString(vbs.toPath(), vbsScript);

            new ProcessBuilder("wscript.exe", vbs.getAbsolutePath())
                    .directory(new File(System.getProperty("java.io.tmpdir")))
                    .start();
            ToggleMods.LOGGER.info("[ToggleMods] Scheduled {} deferred rename(s) via hidden helper {}",
                    deferred.size(), bat.getName());
            return true;
        } catch (Throwable t) {
            ToggleMods.LOGGER.error("[ToggleMods] Failed to spawn hidden helper.", t);
            return false;
        }
    }

    /**
     * Pick how to relaunch on Windows.
     *
     * <p>Prism/MultiMC launch Minecraft through a wrapper ({@code
     * org.prismlauncher.EntryPoint}) and feed the real launch parameters over
     * <b>stdin</b>, which we cannot replay - so reconstructing the Java command
     * relaunches a wrapper that just hangs waiting for input. Instead, when we
     * detect that launcher (via its {@code INST_ID} env var), we ask it to
     * relaunch the instance for us: {@code prismlauncher.exe --launch <id>}.
     * The launcher's single-instance handling forwards that to the running GUI.
     *
     * <p>For any other launcher we fall back to reconstructing the Java command.
     */
    private static List<String> windowsRelaunchCommand() {
        String instId = System.getenv("INST_ID"); // set by Prism Launcher / MultiMC
        if (instId == null || instId.isBlank()) {
            return buildCommand();
        }
        String launcher = findLauncherExe();
        if (launcher == null) {
            ToggleMods.LOGGER.warn("[ToggleMods] Running under a Prism/MultiMC instance "
                    + "(INST_ID={}) but could not locate the launcher exe; changes will be applied "
                    + "but you'll need to relaunch the instance yourself.", instId);
            return List.of(); // empty -> helper applies renames, then prompts a manual relaunch
        }
        ToggleMods.LOGGER.info("[ToggleMods] Detected Prism/MultiMC instance '{}'; "
                + "relaunching via {}", instId, launcher);
        return List.of(launcher, "--launch", instId);
    }

    /** Find the Prism/MultiMC executable: prefer our parent process, then defaults. */
    private static String findLauncherExe() {
        try {
            var parent = ProcessHandle.current().parent();
            if (parent.isPresent()) {
                var cmd = parent.get().info().command();
                if (cmd.isPresent()) {
                    String c = cmd.get();
                    String lc = c.toLowerCase(Locale.ROOT);
                    if (lc.endsWith(".exe") && (lc.contains("prism") || lc.contains("multimc"))) {
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {
            // parent info isn't always available; fall through to defaults
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            File f = new File(local, "Programs\\PrismLauncher\\prismlauncher.exe");
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private static File writeWindowsHelper(List<Path[]> deferred, List<String> command, File vbs) throws Exception {
        long pid = ProcessHandle.current().pid();
        String workdir = System.getProperty("user.dir");

        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("setlocal\r\n");
        // 1) Wait for this game process to fully exit so the jar locks release.
        //    Tight poll (no sleep): tasklist itself takes ~0.3s, so this catches
        //    the exit within a fraction of a second of halt() without idling.
        sb.append("set \"PID=").append(pid).append("\"\r\n");
        sb.append(":wait\r\n");
        sb.append("tasklist /FI \"PID eq %PID%\" /NH 2>nul | findstr /C:\"%PID%\" >nul && goto wait\r\n");
        // 2) Perform the deferred renames now that the files are unlocked.
        for (Path[] op : deferred) {
            sb.append("move /Y ").append(q(op[0].toAbsolutePath().toString()))
              .append(' ').append(q(op[1].toAbsolutePath().toString())).append(" >nul\r\n");
        }
        // 3) Relaunch the game, detached, in the original working directory.
        //    (If command is empty we couldn't determine how to relaunch, so we
        //    just leave the applied changes on disk for a manual restart.)
        if (!command.isEmpty()) {
            sb.append("start \"\" /D ").append(q(workdir));
            for (String a : command) sb.append(' ').append(q(a));
            sb.append("\r\n");
        }
        // 4) Clean up both temp files (the vbs shim and this script itself).
        sb.append("endlocal\r\n");
        sb.append("del ").append(q(vbs.getAbsolutePath())).append(" >nul 2>&1\r\n");
        sb.append("(goto) 2>nul & del \"%~f0\"\r\n");

        File bat = File.createTempFile("togglemods-relaunch-", ".bat");
        Files.writeString(bat.toPath(), sb.toString());
        return bat;
    }

    /** Wrap a single argument in double quotes for cmd.exe. */
    private static String q(String s) {
        return '"' + s + '"';
    }

    // --------------------------------------------------------- shared helpers

    @SuppressWarnings("unused")
    private static boolean tryRelauncherLibrary() {
        try {
            Class<?> cls = Class.forName("com.juanmuscaria.relauncher.Relauncher");
            Method m = cls.getMethod("relaunch", List.class);
            Object result = m.invoke(null, Collections.emptyList());
            // A successful in-place exec never returns. If it did, it didn't relaunch.
            if (result != null) {
                ToggleMods.LOGGER.warn("[ToggleMods] Relauncher returned without relaunching: {}", result);
            }
            return false;
        } catch (ClassNotFoundException notInstalled) {
            ToggleMods.LOGGER.info("[ToggleMods] Relauncher not installed; using built-in fallback.");
            return false;
        } catch (Throwable t) {
            ToggleMods.LOGGER.warn("[ToggleMods] Relauncher call failed; using fallback.", t);
            return false;
        }
    }

    /** POSIX fallback: spawn a fresh JVM inheriting our stdio, then hard-exit. */
    private static boolean fallbackSpawnAndHalt(List<String> command) {
        try {
            if (command.isEmpty()) {
                ToggleMods.LOGGER.error("[ToggleMods] Could not reconstruct launch command; aborting restart.");
                return false;
            }
            ToggleMods.LOGGER.info("[ToggleMods] Relaunching: {}", String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            pb.directory(new File(System.getProperty("user.dir")));
            pb.start();
            Runtime.getRuntime().halt(0);
            return true;
        } catch (Throwable t) {
            ToggleMods.LOGGER.error("[ToggleMods] Fallback relaunch failed.", t);
            return false;
        }
    }

    /**
     * Reconstruct the command that started this JVM. Uses the JVM's own input
     * arguments (memory, system properties incl. the natives {@code
     * java.library.path}, mixin {@code -javaagent}, module options) plus the
     * classpath and main class - sources that, unlike {@code
     * ProcessHandle.arguments()}, are actually populated on Windows.
     */
    private static List<String> buildCommand() {
        List<String> out = new ArrayList<>();

        String javaHome = System.getProperty("java.home");
        String exe = isWindows() ? "javaw.exe" : "java";
        out.add(javaHome + File.separator + "bin" + File.separator + exe);

        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // A debugger port would clash with the still-dying parent; drop it.
            if (a.startsWith("-agentlib:jdwp")) continue;
            out.add(a);
        }

        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            out.add("-cp");
            out.add(cp);
        }

        // main class + program args (auth, version, gameDir, ...).
        String mainCmd = System.getProperty("sun.java.command");
        if (mainCmd != null && !mainCmd.isBlank()) {
            for (String part : mainCmd.split(" ")) {
                if (!part.isEmpty()) out.add(part);
            }
        }
        return out;
    }
}
