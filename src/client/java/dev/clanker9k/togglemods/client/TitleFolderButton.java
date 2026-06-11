package dev.clanker9k.togglemods.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * A small square icon button (the folder texture) used to open the ToggleMods
 * screen from the title screen - similar to the buttons ReplayMod / account
 * switchers inject. Extends {@link Button} so click/narration/focus all work;
 * only the appearance is overridden to draw the icon instead of a label.
 */
public class TitleFolderButton extends Button {
    private static final Identifier ICON =
            Identifier.fromNamespaceAndPath("togglemods", "textures/gui/folder.png");
    private static final int TEX = 512; // Folder.png is 512x512

    public TitleFolderButton(int x, int y, int size, OnPress onPress) {
        super(x, y, size, size, Component.literal("ToggleMods"), onPress, DEFAULT_NARRATION);
    }

    // The vanilla button frame + hover highlight are drawn by AbstractButton's
    // (final) extractWidgetRenderState; we just fill in the contents with the icon.
    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int pad = 2;
        g.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + pad, getY() + pad, 0.0F, 0.0F,
                this.width - 2 * pad, this.height - 2 * pad, TEX, TEX, TEX, TEX, ARGB.white(1.0F));
    }
}
