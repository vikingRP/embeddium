package me.jellysquid.mods.sodium.client.gui.screen;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

public class ConfigCorruptedScreen extends Screen {
    private static final Component TEXT_BUTTON_RESTORE_DEFAULTS = Component.translatable("embeddium.config_corrupted.restore_defaults");
    private static final Component TEXT_BUTTON_CLOSE_GAME = Component.translatable("embeddium.config_corrupted.close_game");

    private final Supplier<Screen> child;

    public ConfigCorruptedScreen(Supplier<Screen> child) {
        super(Component.translatable("embeddium.config_corrupted.title"));

        this.child = child;
    }

    @Override
    protected void init() {
        super.init();

        this.addRenderableWidget(Button.builder(TEXT_BUTTON_RESTORE_DEFAULTS, (btn) -> {
            SodiumClientMod.restoreDefaultOptions();
            Minecraft.getInstance().setScreen(this.child.get());
        }).bounds(32, this.height - 40, 174, 20).build());

        this.addRenderableWidget(Button.builder(TEXT_BUTTON_CLOSE_GAME, (btn) -> {
            Minecraft.getInstance().stop();
        }).bounds(this.width - 174 - 32, this.height - 40, 174, 20).build());
    }

    @Override
    public void render(GuiGraphics drawContext, int mouseX, int mouseY, float delta) {
        this.renderBackground(drawContext);

        super.render(drawContext, mouseX, mouseY, delta);

        drawContext.drawString(this.font, Component.translatable("embeddium.config_corrupted.renderer"), 32, 32, 0xffffff);
        drawContext.drawString(this.font, Component.translatable("embeddium.config_corrupted.header"), 32, 48, 0xff0000);

        String[] body = Component.translatable("embeddium.config_corrupted.body").getString().split("\n");

        for (int i = 0; i < body.length; i++) {
            if (body[i].isEmpty()) {
                continue;
            }

            drawContext.drawString(this.font, body[i], 32, 68 + (i * 12), 0xffffff);
        }
    }
}
