package dev.clanker9k.togglemods.client;

import dev.clanker9k.togglemods.ManagedMod;
import dev.clanker9k.togglemods.ModFileManager;
import dev.clanker9k.togglemods.RelaunchHelper;
import dev.clanker9k.togglemods.ToggleMods;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mod Menu-style toggle screen: a scrollable, searchable list of every jar in
 * the mods folder with a per-row ON/OFF toggle, dependency-aware disabling, and
 * a "review changes" confirmation before applying.
 */
@Environment(EnvType.CLIENT)
public class ToggleModsScreen extends Screen {
    private static final String GITHUB_URL = "https://github.com/Clanker9k/ToggleMods";

    private final Screen parent;
    private List<ManagedMod> allMods;
    private ToggleModsListWidget list;
    private EditBox search;
    private Button applyButton;

    public ToggleModsScreen(Screen parent) {
        super(Component.translatable("screen.togglemods.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (allMods == null) {
            allMods = ModFileManager.scan();
        }

        // --- list ---
        int top = 48;
        int listHeight = Math.max(36, this.height - top - 64);
        list = new ToggleModsListWidget(this.minecraft, this.width, listHeight, top, 36, this);
        this.addWidget(list);

        // --- search box ---
        int sw = Math.min(260, this.width - 150);
        String prev = search != null ? search.getValue() : "";
        search = new EditBox(this.font, this.width / 2 - sw / 2, 22, sw, 18,
                Component.literal("Search mods…"));
        search.setValue(prev);
        search.setHint(Component.literal("Search mods…"));
        search.setResponder(t -> applyFilter());
        this.addWidget(search);

        applyFilter();

        // --- GitHub link (top-right) ---
        this.addRenderableWidget(Button.builder(Component.literal("GitHub"),
                b -> Util.getPlatform().openUri(URI.create(GITHUB_URL)))
                .bounds(this.width - 64, 4, 58, 20).build());

        // --- bottom bars (two centered rows) ---
        int cx = this.width / 2;
        int row1 = this.height - 52;
        int row2 = this.height - 28;

        // Row 1: bulk actions + utilities (centered group of four).
        this.addRenderableWidget(Button.builder(Component.literal("Enable All"), b -> setAll(true))
                .bounds(cx - 171, row1, 82, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Disable All"), b -> setAll(false))
                .bounds(cx - 85, row1, 82, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Rescan"), b -> {
            allMods = ModFileManager.scan();
            this.rebuildWidgets();
        }).bounds(cx + 1, row1, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Mods Folder"),
                b -> Util.getPlatform().openUri(ModFileManager.modsDir().toUri()))
                .bounds(cx + 75, row1, 96, 20).build());

        // Row 2: primary actions.
        applyButton = Button.builder(applyLabel(), b -> onApply())
                .bounds(cx - 154, row2, 150, 20).build();
        this.addRenderableWidget(applyButton);

        Button doneButton = Button.builder(Component.literal("Done"), b -> onDone())
                .bounds(cx + 4, row2, 150, 20).build();
        doneButton.setTooltip(Tooltip.create(Component.literal(
                "Save your changes and close. Changes will be applied on the next launch "
                + "- no restart now. (Press Esc to discard instead.)")));
        this.addRenderableWidget(doneButton);

        this.setInitialFocus(search);
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<ManagedMod> shown = new ArrayList<>();
        for (ManagedMod m : allMods) {
            if (q.isEmpty()
                    || m.displayName.toLowerCase(Locale.ROOT).contains(q)
                    || m.fileName().toLowerCase(Locale.ROOT).contains(q)
                    || m.modIds.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(m);
            }
        }
        list.setMods(shown);
    }

    /** Called by a row when clicked. Disabling a depended-upon mod prompts first. */
    void onToggle(ManagedMod m) {
        if (m.locked) return;
        if (m.desiredEnabled) {
            // turning OFF -> warn if other enabled mods need it
            Set<ManagedMod> broken = ModFileManager.dependentsBrokenByDisabling(m, allMods);
            if (!broken.isEmpty()) {
                this.minecraft.setScreen(buildDependencyPrompt(m, broken));
                return;
            }
        }
        m.desiredEnabled = !m.desiredEnabled;
    }

    /** Bulk enable/disable every unlocked mod (locked ones are left untouched). */
    private void setAll(boolean enabled) {
        for (ManagedMod m : allMods) {
            if (!m.locked) m.desiredEnabled = enabled;
        }
    }

    private Component applyLabel() {
        int pending = allMods == null ? 0 : ModFileManager.pendingCount(allMods);
        return Component.literal("Apply & Restart (" + pending + ")");
    }

    private void onApply() {
        if (ModFileManager.pendingCount(allMods) == 0) return;
        this.minecraft.setScreen(buildChangeSummary(true));
    }

    private void onDone() {
        if (ModFileManager.pendingCount(allMods) == 0) {
            onClose();
            return;
        }
        this.minecraft.setScreen(buildChangeSummary(false));
    }

    // --------------------------------------------------------------- confirm flow

    private ConfirmActionScreen buildDependencyPrompt(ManagedMod target, Set<ManagedMod> broken) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("These enabled mods depend on it and will break:"));
        addNames(lines, broken, 8);

        List<ConfirmActionScreen.Choice> choices = List.of(
                new ConfirmActionScreen.Choice(Component.literal("Disable only this mod"), () -> {
                    target.desiredEnabled = false;
                    this.minecraft.setScreen(this);
                }),
                new ConfirmActionScreen.Choice(
                        Component.literal("Disable it + its " + broken.size()
                                + " dependent" + (broken.size() == 1 ? "" : "s")), () -> {
                    target.desiredEnabled = false;
                    for (ManagedMod d : broken) if (!d.locked) d.desiredEnabled = false;
                    this.minecraft.setScreen(this);
                }),
                new ConfirmActionScreen.Choice(Component.literal("Cancel"),
                        () -> this.minecraft.setScreen(this)));

        return new ConfirmActionScreen(this,
                Component.literal("Disable \"" + target.displayName + "\"?"), lines, choices);
    }

    private ConfirmActionScreen buildChangeSummary(boolean restart) {
        List<ManagedMod> enabling = new ArrayList<>();
        List<ManagedMod> disabling = new ArrayList<>();
        for (ManagedMod m : allMods) {
            if (m.locked || !m.isDirty()) continue;
            (m.desiredEnabled ? enabling : disabling).add(m);
        }

        List<Component> lines = new ArrayList<>();
        if (!enabling.isEmpty()) {
            lines.add(Component.literal("Enabling (" + enabling.size() + "):"));
            addNames(lines, enabling, 6);
        }
        if (!disabling.isEmpty()) {
            lines.add(Component.literal("Disabling (" + disabling.size() + "):"));
            addNames(lines, disabling, 6);
        }
        if (restart && this.minecraft.hasSingleplayerServer()) {
            lines.add(Component.literal(""));
            lines.add(Component.literal("Save your world first - the instance will relaunch."));
        }

        Component primaryLabel = restart
                ? Component.literal("Apply & Restart now")
                : Component.literal("Save for next launch");
        Runnable primaryAction = restart ? this::doApplyRestart : this::doSaveForLater;

        List<ConfirmActionScreen.Choice> choices = List.of(
                new ConfirmActionScreen.Choice(primaryLabel, primaryAction),
                new ConfirmActionScreen.Choice(Component.literal("Cancel"),
                        () -> this.minecraft.setScreen(this)));

        return new ConfirmActionScreen(this, Component.literal("Review changes"), lines, choices);
    }

    private void doApplyRestart() {
        ModFileManager.savePending(allMods);
        var renames = ModFileManager.computePendingRenames(allMods);
        ToggleMods.LOGGER.info("[ToggleMods] Applying {} change(s) and restarting.", renames.size());
        boolean ok = RelaunchHelper.applyAndRelaunch(renames); // never returns on success
        if (!ok) {
            ToggleMods.LOGGER.error("[ToggleMods] Restart could not be started - "
                    + "changes are saved; restart the game manually to finish.");
            this.minecraft.setScreen(this);
        }
    }

    private void doSaveForLater() {
        ModFileManager.savePending(allMods);
        ToggleMods.LOGGER.info("[ToggleMods] Saved choices; will apply on game exit.");
        this.minecraft.setScreen(parent);
    }

    private static void addNames(List<Component> lines, Collection<ManagedMod> mods, int cap) {
        int i = 0;
        for (ManagedMod m : mods) {
            if (i >= cap) {
                lines.add(Component.literal("  + " + (mods.size() - cap) + " more"));
                break;
            }
            lines.add(Component.literal("  - " + m.displayName));
            i++;
        }
    }

    // ------------------------------------------------------------------- rendering

    @Override
    public boolean keyPressed(KeyEvent input) {
        return super.keyPressed(input) || search.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        return search.charTyped(input);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        list.extractRenderState(g, mouseX, mouseY, delta);
        search.extractRenderState(g, mouseX, mouseY, delta);

        int pending = ModFileManager.pendingCount(allMods);
        applyButton.setMessage(applyLabel());
        applyButton.active = pending > 0;

        g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        int total = allMods == null ? 0 : allMods.size();
        Component sub = Component.literal(total + " mods   |   " + pending + " pending change"
                + (pending == 1 ? "" : "s"));
        g.centeredText(this.font, sub, this.width / 2, this.height - 63, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
