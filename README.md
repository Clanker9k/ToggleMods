# ToggleMods

A small, client-side **Fabric** mod for **Minecraft 26.1.x**.
Enables you to toggle any installed mod in /mods folder and then apply the
change with a one-click restart (or save it for the next launch).

---

Opens from a **folder button on the title screen** or a keybind in-game (default **O**).

## Tech side

1. It edits the `mods/` folder, renaming `foo.jar` ⇄ `foo.jar.disabled` (the
   suffix Fabric and every launcher ignore).
2. A fresh game process re-scans the folder, and your changes are live.

On **Windows** a currently-loaded jar is locked and can't be renamed mid-session,
so a disable is deferred: the choice is saved, and the rename happens the moment
the game exits.

## Security Note

This mod is fully open-source and does **no networking, obfuscation, or data
collection**. To restart the game and to rename a jar after exit, it
must spawn external processes, which can look unusual to automated scanners:

- If the [Relauncher](https://modrinth.com/mod/relauncher) library is installed, it does the restart cleanly (called by reflection, never a hard dependency). Enabling/reloading mods takes this path.
- Disabling a *loaded* mod can't rename its jar mid-session, so it writes a small temporary `.bat` (and a `.vbs` shim to run it hidden) to the OS temp dir, which waits for this game process to exit, renames the jars, then relaunches. See [`RelaunchHelper.java`](src/main/java/dev/clanker9k/togglemods/RelaunchHelper.java).
- Without Relauncher, that helper handles every restart too; under Prism Launcher / MultiMC it relaunches via `prismlauncher.exe --launch <INST_ID>` (their stdin launch protocol can't be replayed any other way).

All of this is visible and commented in the source.

Don't trust binaries? Don't run binaries. Clone the repo, review the diffs, and build it yourself.
Just be consistent: if you're not auditing this, you probably aren't auditing the client/plugins you already run.
## AI Notice

This mod was built completely by Anthropic's Opus 4.8, I built it just for myself, it works as-is and all I did was audit the code and lead the AI through the logic.

If you can make a deslopified fork of this mod, that'd be neat.
The full source is here for anyone to read, audit, and modify.

## Building

Requires a **JDK 25**, Gradle is provided via the
wrapper.

```bash
# Windows
gradlew build
# Linux/macOS
./gradlew build
```

The jar lands in `build/libs/togglemods-1.0.0.jar`.

## Installing

Drop `togglemods-1.0.0.jar` into your instance's `mods/` folder, alongside
**Fabric API**. **Relauncher** is optional but recommended for the cleanest
restart.

## Compatibility

- **Minecraft** 26.1.x · **Fabric Loader** ≥ 0.18.4 · **Fabric API** required.
- **Client-side.** Mod toggling works on any launcher; the one-click restart is cleanest with **[Relauncher](https://modrinth.com/mod/relauncher)** installed (tested on Prism Launcher).

## License

[MIT](https://opensource.org/license/mit) © Clanker9K
