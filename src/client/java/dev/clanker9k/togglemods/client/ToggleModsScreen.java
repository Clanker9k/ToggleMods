package dev.clanker9k.togglemods.client;

import dev.clanker9k.togglemods.ToggleMods;
import dev.clanker9k.togglemods.ManagedMod;
import dev.clanker9k.togglemods.ModFileManager;
import dev.clanker9k.togglemods.RelaunchHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mod Menu-style toggle screen: a scrollable, searchable list of every jar in
 * the mods folder with a per-row ON/OFF toggle, plus "Apply &amp; Restart".
 */
@Environment(EnvType.CLIENT)
public class ToggleModsScreen extends Screen {
    private final Screen parent;
    private List<ManagedMod> allMods;
    private ToggleModsListWidget list;
    private EditBox search;
    private Button applyButton;
    private boolean confirmingRestart = false;

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
        int sw = Math.min(280, this.width - 40);
        String prev = search != null ? search.getValue() : "";
        search = new EditBox(this.font, this.width / 2 - sw / 2, 22, sw, 18,
                Component.literal("Search mods…"));
        search.setValue(prev);
        search.setHint(Component.literal("Search mods…"));
        search.setResponder(t -> applyFilter());
        this.addWidget(search);

        applyFilter();

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
            confirmingRestart = false;
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

    /** Called by a row when clicked. */
    void onToggle(ManagedMod m) {
        if (m.locked) return;
        m.desiredEnabled = !m.desiredEnabled;
        confirmingRestart = false;
    }

    /** Bulk enable/disable every unlocked mod (locked ones are left untouched). */
    private void setAll(boolean enabled) {
        for (ManagedMod m : allMods) {
            if (!m.locked) m.desiredEnabled = enabled;
        }
        confirmingRestart = false;
    }

    private Component applyLabel() {
        int pending = allMods == null ? 0 : ModFileManager.pendingCount(allMods);
        return confirmingRestart
                ? Component.literal("Click again to RESTART")
                : Component.literal("Apply & Restart (" + pending + ")");
    }

    private void onApply() {
        int pending = ModFileManager.pendingCount(allMods);
        if (pending == 0) return;
        if (!confirmingRestart) {
            confirmingRestart = true;
            return;
        }

        // Persist intent first (so it survives even if the relaunch fails).
        ModFileManager.savePending(allMods);
        var renames = ModFileManager.computePendingRenames(allMods);
        ToggleMods.LOGGER.info("[ToggleMods] Applying {} change(s) and restarting.", renames.size());

        boolean ok = RelaunchHelper.applyAndRelaunch(renames);
        if (!ok) {
            ToggleMods.LOGGER.error("[ToggleMods] Restart could not be started - "
                    + "any safe changes were applied; restart the game manually to finish.");
            confirmingRestart = false;
        }
    }

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

        // keep the apply button's label/enabled state live
        int pending = ModFileManager.pendingCount(allMods);
        applyButton.setMessage(applyLabel());
        applyButton.active = pending > 0;

        g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        // One info line directly above the button rows: the restart warning while
        // confirming, otherwise the mod counts.
        if (confirmingRestart) {
            // Only mention the world when there's a local one to lose; on a
            // server / title screen it would be misleading.
            String warn = this.minecraft.hasSingleplayerServer()
                    ? "Save the world, instance will relaunch"
                    : "Instance will relaunch";
            g.centeredText(this.font, Component.literal(warn),
                    this.width / 2, this.height - 63, 0xFFFF5555);
        } else {
            int total = allMods == null ? 0 : allMods.size();
            Component sub = Component.literal(total + " mods   |   " + pending + " pending change"
                    + (pending == 1 ? "" : "s"));
            g.centeredText(this.font, sub, this.width / 2, this.height - 63, 0xFFA0A0A0);
        }
    }

    /**
     * "Done": persist the choices and close. The actual jar renames are applied
     * when the game next quits (see the CLIENT_STOPPING hook in the client init),
     * so the next launch reflects them - no restart needed now.
     */
    private void onDone() {
        ModFileManager.savePending(allMods);
        ToggleMods.LOGGER.info("[ToggleMods] Saved choices; will apply on game exit.");
        onClose();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
