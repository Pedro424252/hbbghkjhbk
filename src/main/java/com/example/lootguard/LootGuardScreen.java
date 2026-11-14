package com.example.lootguard;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class LootGuardScreen extends Screen {
    private static final int MIN = 1, MAX = 15;

    public LootGuardScreen() {
        super(Text.literal("Aesir Loot Guard"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 45;

        this.addDrawableChild(ButtonWidget.builder(label(), b -> {
            LootGuard.enabled = !LootGuard.enabled;
            b.setMessage(label());
        }).dimensions(cx - 100, y, 200, 20).build());

        this.addDrawableChild(new SliderWidget(cx - 100, y + 30, 200, 20,
                Text.literal("Kilit suresi: " + LootGuard.lockSeconds + " sn"),
                (LootGuard.lockSeconds - MIN) / (double) (MAX - MIN)) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Kilit suresi: " + secs() + " sn"));
            }

            @Override
            protected void applyValue() {
                LootGuard.lockSeconds = secs();
            }

            private int secs() {
                return MIN + (int) Math.round(value * (MAX - MIN));
            }
        });

        this.addDrawableChild(ButtonWidget.builder(predLabel(), b -> {
            LootGuard.predict = !LootGuard.predict;
            b.setMessage(predLabel());
        }).dimensions(cx - 100, y + 60, 200, 20).build());

        this.addDrawableChild(new SliderWidget(cx - 100, y + 90, 200, 20,
                Text.literal("Tahmin can siniri: " + LootGuard.predictHp + " HP"),
                (LootGuard.predictHp - 4) / 20.0) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Tahmin can siniri: " + hp() + " HP"));
            }

            @Override
            protected void applyValue() {
                LootGuard.predictHp = hp();
            }

            private int hp() {
                return 4 + (int) Math.round(value * 20);
            }
        });

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Kapat"), b -> this.close())
                .dimensions(cx - 100, y + 120, 200, 20).build());
    }

    private Text predLabel() {
        return Text.literal("Hizli Tahmin: " + (LootGuard.predict ? "ACIK" : "KAPALI"));
    }

    private Text label() {
        return Text.literal("Aesir Loot Guard: " + (LootGuard.enabled ? "ACIK" : "KAPALI"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Gaks_v310 Taraf\u0131ndan Yap\u0131lm\u0131\u015Ft\u0131r"),
                this.width / 2, this.height / 2 + 100, 0xFFFFAA00);
    }
}
