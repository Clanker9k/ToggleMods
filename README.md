# ToggleMods

A small, client-side **Fabric** mod for **Minecraft 26.1.x** that lets you
**enable or disable any installed mod from an in-game screen** — then apply the
change with a one-click restart (or save it for the next launch).

No more alt-tabbing to your launcher and renaming jars by hand.

---

## Features

- 🗂️ **Mod Menu–style list** of every jar in your `mods/` folder, with each
  mod's own icon, name, file name, and an `[ON]` / `[OFF]` / `[LOCKED]` state.
- 🔎 **Search** box to filter, **Enable All** / **Disable All** bulk buttons.
- 🔒 **Essential jars are locked** (ToggleMods itself, Fabric API/Loader,
  Relauncher) and pinned to the bottom — you can't accidentally disable the
  things needed to boot back up.
- ⭐ A **`*`** marks any change that hasn't taken effect yet (a restart away).
- 🚀 **Apply & Restart** — writes the changes and relaunches the game.
- 💾 **Done** — saves your choices and applies them the next time you quit, no
  restart needed now. Choices persist across menu re-opens (`config/togglemods.properties`).
- 🏷️ Opens from a **folder button on the title screen** or a keybind (default **O**).

## How it works (the honest version)

Mods are **code**, not data — Fabric applies their mixins, freezes registries,
and wires their event listeners once at JVM startup. None of that can be cleanly
undone in a running game, so there is **no true hot-toggle**. ToggleMods takes
the realistic route:

1. It edits the `mods/` folder, renaming `foo.jar` ⇄ `foo.jar.disabled` (the
   suffix Fabric and every launcher ignore).
2. A fresh game process re-scans the folder, and your changes are live.

On **Windows** a currently-loaded jar is locked and can't be renamed mid-session,
so a disable is deferred: the choice is saved, and the rename happens the moment
the game exits (or immediately, on Apply & Restart).

## ⚠️ Note for mod-platform reviewers & antivirus

This mod is fully open-source and does **no networking, obfuscation, or data
collection**. To restart the game and to rename a *locked* jar after exit, it
must spawn external processes, which can look unusual to automated scanners:

- It writes a small temporary `.bat` (and a `.vbs` shim to run it hidden) to the
  OS temp dir, which waits for this game process to exit, renames the jars, then
  optionally relaunches. See [`RelaunchHelper.java`](src/main/java/dev/clanker9k/togglemods/RelaunchHelper.java).
- Under Prism Launcher / MultiMC it relaunches via `prismlauncher.exe --launch <INST_ID>`
  (their stdin launch protocol can't be replayed any other way).
- It uses reflection to optionally call the [Relauncher](https://modrinth.com/mod/relauncher)
  library if present.

All of this is visible and commented in the source.

## Built with AI

This mod was written collaboratively with an AI assistant (Anthropic's Claude).
The full source is here for anyone to read, audit, and modify.

## Building

Requires a **JDK 25** (Minecraft 26.1 needs Java 25); Gradle is provided via the
wrapper.

```bash
# Windows
gradlew build
# Linux/macOS
./gradlew build
```

If your default `java` isn't 25, point Gradle at one:

```bash
./gradlew build -Dorg.gradle.java.home="/path/to/jdk-25"
```

The jar lands in `build/libs/togglemods-1.0.0.jar`.

## Installing

Drop `togglemods-1.0.0.jar` into your instance's `mods/` folder, alongside
**Fabric API**. **Relauncher** is optional but recommended for the cleanest
restart.

## Compatibility

- **Minecraft** 26.1.x · **Fabric Loader** ≥ 0.18.4 · **Fabric API** required.
- **Client-side only.**
- Restart-relaunch is best-effort per launcher; if it can't relaunch, your
  changes are still saved and applied — just relaunch manually that once.

## License

[MIT](LICENSE) © Clanker9K
