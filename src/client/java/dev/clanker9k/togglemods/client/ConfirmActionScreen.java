package dev.clanker9k.togglemods.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A simple centered confirmation screen: a title, a few message lines, and a
 * vertical stack of buttons. Reused for the dependency-conflict prompt and the
 * "review changes" summary. Pressing Esc runs the last choice (treated as
 * Cancel / back).
 */
public class ConfirmActionScreen extends Screen {
    /** One button: its label, a colour for the label, and what it does. */
    public record Choice(Component label, Runnable action) {}

    private final Screen parent;
    private final List<Component> lines;
    private final List<Choice> choices;

    public ConfirmActionScreen(Screen parent, Component title, List<Component> lines, List<Choice> choices) {
        super(title);
        this.parent = parent;
        this.lines = lines;
        this.choices = choices;
    }

    @Override
    protected void init() {
        int btnW = 260;
        int gap = 24;
        // Stack the buttons so the last one sits ~28px from the bottom.
        int firstY = this.height - 28 - (choices.size() - 1) * gap;
        int y = firstY;
        for (Choice c : choices) {
            this.addRenderableWidget(Button.builder(c.label(), b -> c.action().run())
                    .bounds(this.width / 2 - btnW / 2, y, btnW, 20).build());
            y += gap;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);
        int y = 72;
        for (Component line : lines) {
            g.centeredText(this.font, line, this.width / 2, y, 0xFFC8C8C8);
            y += 12;
        }
    }

    @Override
    public void onClose() {
        // Esc = the last choice (Cancel/back by convention).
        if (!choices.isEmpty()) {
            choices.get(choices.size() - 1).action().run();
        } else {
            this.minecraft.setScreen(parent);
        }
    }
}
