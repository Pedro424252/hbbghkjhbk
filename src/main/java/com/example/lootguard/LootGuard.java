package com.example.lootguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aesir Loot Guard - Gaks_v310
 *
 * SADECE SENIN oldurdugun oyuncu icin, kilit suresi boyunca:
 *  - ekranda "X OLDU" yazar ve eline kilic alir
 *  - kristal / glowstone / anchor eline gelirse surekli guvenli slota gecer
 *  - kristal / glowstone / anchor kullanimini ve kristale vurmayi engeller
 *
 * HIZ: sunucu olumu bildirene kadar (ping) kristal spam'i loot'u patlatir. Bu yuzden TAHMIN modu var:
 *  kristale vurdugunda yakinda dusuk canli bir oyuncu varsa, sunucu cevap verene kadar kristal
 *  vurusu/yerlestirmesi o bolgede ANINDA durdurulur (olum mesaji beklenmez).
 *
 * "Senin oldurdugun" su sekilde anlasilir:
 *  1) olum mesajinda senin adin gecerse (X was slain by SEN, blown up by SEN ...)
 *  2) mesaj yoksa: son 8 sn icinde ona vurduysan
 *  3) mesaj yoksa: son 4 sn icinde yakinindaki bir kristale vurduysan
 *  4) anchor: "[Intentional Game Design]" mesaji + son 4 sn icinde anchor'a tikladiysan
 */
public class LootGuard implements ClientModInitializer {
    public static volatile boolean enabled = true;
    public static volatile int lockSeconds = 5;   // kilit suresi (saniye), GUI'den ayarlanir
    public static volatile boolean predict = true;     // tahmin modu (olumden ONCE durdur)
    public static volatile int predictHp = 12;         // bu cana (can+absorption) ve altindaysa tahmin devreye girer

    private static final double RANGE = 30.0;           // olum algilama mesafesi (blok)
    private static final double NEAR = 20.0;            // "kayboldu" algilamasi icin mesafe
    private static final long DEDUPE_MS = 4000;         // ayni oyuncu icin tekrar algilama araligi
    private static final long HIT_WINDOW_MS = 8000;     // vurustan sonra "benim" sayilma suresi
    private static final long CRYSTAL_WINDOW_MS = 4000; // kristale vurdugundan sonra sure
    private static final double CRYSTAL_RADIUS = 12.0;  // kristal patlamasinin etki mesafesi
    private static final long ANCHOR_WINDOW_MS = 4000;  // anchor'a tikladiktan sonra sure
    private static final double ANCHOR_RADIUS = 12.0;   // anchor patlamasinin etki mesafesi

    private static final double PREDICT_RADIUS = 14.0;  // tahmin kilidinin etki mesafesi
    private static final long PREDICT_MIN_MS = 200;     // tahmin kilidi alt sinir
    private static final long PREDICT_MAX_MS = 700;     // tahmin kilidi ust sinir

    private record Seen(String name, double x, double y, double z) {}

    private static KeyBinding openKey;
    private static volatile long lockUntil = 0;
    private static String lastName = "";

    private static final Map<String, Long> lastTrigger = new HashMap<>();
    private static final Map<String, Long> lastHit = new HashMap<>();
    private static long crystalHitTime = 0;
    private static double crystalX, crystalY, crystalZ;
    private static long anchorTime = 0;
    private static double anchorX, anchorY, anchorZ;
    private static Map<UUID, Seen> prevNear = new HashMap<>();
    private static Object lastWorld = null;

    // tahmin kilidi (olum teyit edilmeden once)
    private static volatile long predictUntil = 0;
    private static UUID predictId = null;
    private static float predictStartHp = 0;
    private static double predX, predY, predZ;

    /** Tahmin kilidi bu konumdaki kristal islemini engelliyor mu? */
    private static boolean predictBlocks(double x, double y, double z) {
        return enabled && predict && System.currentTimeMillis() < predictUntil
                && within(x, y, z, predX, predY, predZ, PREDICT_RADIUS);
    }

    /** Kristale vurdum: yakinda olmek uzere biri varsa sunucu cevap verene kadar kilitle. */
    private static void armPredict(MinecraftClient client, double cx, double cy, double cz) {
        if (!predict || client.player == null || client.world == null) return;
        AbstractClientPlayerEntity best = null;
        float bestHp = Float.MAX_VALUE;
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.isDead()) continue;
            if (!within(p.getX(), p.getY(), p.getZ(), cx, cy, cz, CRYSTAL_RADIUS)) continue;
            float hp = p.getHealth() + p.getAbsorptionAmount();
            if (hp <= predictHp && hp < bestHp) { best = p; bestHp = hp; }
        }
        if (best == null) return;
        int ping = 0;
        if (client.getNetworkHandler() != null) {
            var e = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (e != null) ping = e.getLatency();
        }
        long win = Math.max(PREDICT_MIN_MS, Math.min(PREDICT_MAX_MS, ping + 150L));
        predictId = best.getUuid();
        predictStartHp = bestHp;
        predX = best.getX(); predY = best.getY(); predZ = best.getZ();
        predictUntil = System.currentTimeMillis() + win;
    }

    /** Sunucu cevap verdiyse (can degisti) tahmin kilidini birak. Olduyse normal kilit zaten devreye girer. */
    private static void checkPredictRelease(MinecraftClient client) {
        if (System.currentTimeMillis() >= predictUntil) return;
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p.getUuid().equals(predictId)) {
                float hp = p.getHealth() + p.getAbsorptionAmount();
                if (!p.isDead() && hp != predictStartHp) predictUntil = 0;
                return;
            }
        }
    }

    public static boolean locked() {
        return enabled && System.currentTimeMillis() < lockUntil;
    }

    private static boolean bad(ItemStack s) {
        return s.isOf(Items.END_CRYSTAL) || s.isOf(Items.GLOWSTONE) || s.isOf(Items.RESPAWN_ANCHOR);
    }

    /** Bu oyuncuyu ben mi oldurdum? (mesaj yoksa vurus/kristal kaydina bakar) */
    private static boolean mine(String name, double x, double y, double z) {
        long now = System.currentTimeMillis();
        Long h = lastHit.get(name);
        if (h != null && now - h < HIT_WINDOW_MS) return true;
        if (now - crystalHitTime < CRYSTAL_WINDOW_MS
                && within(x, y, z, crystalX, crystalY, crystalZ, CRYSTAL_RADIUS)) return true;
        if (now - anchorTime < ANCHOR_WINDOW_MS
                && within(x, y, z, anchorX, anchorY, anchorZ, ANCHOR_RADIUS)) return true;
        return false;
    }

    private static boolean within(double x, double y, double z, double ax, double ay, double az, double r) {
        double dx = x - ax, dy = y - ay, dz = z - az;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private static void trigger(MinecraftClient client, String name) {
        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(name);
        if (last != null && now - last < DEDUPE_MS) return;
        lastTrigger.put(name, now);
        lockUntil = now + lockSeconds * 1000L;
        predictUntil = 0;
        lastName = name;
        switchToSword(client);
    }

    private static void switchToSword(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    /** Oncelik: kilic -> bos slot -> pearl -> totem. Bulamazsa bir sey yapmaz. */
    private static void switchToSafe(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.ENDER_PEARL)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { inv.setSelectedSlot(i); return; }
        }
    }

    /** Olum taramasi. Tick basinda, tick sonunda VE her tikta (kristal/anchor) cagrilir - gecikme minimum. */
    private static void scan(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            prevNear.clear();
            lastWorld = null;
            return;
        }
        if (!enabled) {
            prevNear.clear();
            return;
        }
        if (client.world != lastWorld) {
            lastWorld = client.world;
            prevNear.clear();
        }

        checkPredictRelease(client);
        Map<UUID, Seen> curNear = new HashMap<>();
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player) continue;
            present.add(p.getUuid());
            String name = p.getName().getString();
            double d2 = client.player.squaredDistanceTo(p);
            if (p.isDead()) {                       // 1) olu olarak goruldu
                if (d2 <= RANGE * RANGE && mine(name, p.getX(), p.getY(), p.getZ())) {
                    trigger(client, name);
                }
                continue;
            }
            if (d2 <= NEAR * NEAR) curNear.put(p.getUuid(), new Seen(name, p.getX(), p.getY(), p.getZ()));
        }
        // 2) yakindayken bir anda kayboldu (olu hali hic gorulmediyse)
        for (Map.Entry<UUID, Seen> e : prevNear.entrySet()) {
            if (present.contains(e.getKey())) continue;
            Seen s = e.getValue();
            if (mine(s.name(), s.x(), s.y(), s.z())) trigger(client, s.name());
        }
        prevNear = curNear;
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("lootguard", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootguard.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aesir Loot Guard"),
                        b -> MinecraftClient.getInstance().setScreen(new LootGuardScreen()))
                        .dimensions(5, 5, 110, 20).build());
            }
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> scan(client));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new LootGuardScreen());
            }
            scan(client);
            if (client.player == null || !enabled) return;

            if (locked() && bad(client.player.getMainHandStack())) {
                switchToSafe(client);   // kilit boyunca surekli kontrol, her tick
            }
            if (locked() && client.player.age % 5 == 0) {
                long left = Math.max(0, lockUntil - System.currentTimeMillis());
                client.inGameHud.setOverlayMessage(
                        Text.literal("Aesir Loot Guard: " + lastName + " OLDU - loot korunuyor ("
                                + (left / 1000 + 1) + " sn)"), false);
            }
        });

        // 3) sohbetteki olum mesaji: sadece mesajda SENIN adin geciyorsa
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            if (!(message.getContent() instanceof TranslatableTextContent t)) return;
            String key = t.getKey();
            if (!key.startsWith("death.")) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;
            String myName = client.player.getName().getString();

            // Anchor: "X was killed by [Intentional Game Design]" - mesajda katil adi yok
            if (key.startsWith("death.attack.badRespawnPoint")) {
                Object[] a = t.getArgs();
                if (a.length == 0) return;
                String v = (a[0] instanceof Text tx0) ? tx0.getString() : String.valueOf(a[0]);
                if (v.equals(myName)) return;
                if (System.currentTimeMillis() - anchorTime >= ANCHOR_WINDOW_MS) return;
                boolean ok = true;   // oyuncu goruluyorsa anchor'a yakin olmali
                for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                    if (p.getName().getString().equals(v)) {
                        ok = within(p.getX(), p.getY(), p.getZ(), anchorX, anchorY, anchorZ, ANCHOR_RADIUS);
                        break;
                    }
                }
                if (ok) trigger(client, v);
                return;
            }
            String victim = null;
            boolean me = false;
            Object[] args = t.getArgs();
            for (int i = 0; i < args.length; i++) {
                String s = (args[i] instanceof Text tx) ? tx.getString() : String.valueOf(args[i]);
                if (i == 0) victim = s;
                else if (s.equals(myName)) me = true;
            }
            if (me && victim != null && !victim.equals(myName)) trigger(client, victim);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient()) return ActionResult.PASS;
            if (enabled) scan(MinecraftClient.getInstance());   // tiktan hemen once olum kontrolu
            boolean anchor = world.getBlockState(hit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR);
            if (locked()) {
                if (bad(player.getStackInHand(hand)) || anchor) return ActionResult.FAIL;
                return ActionResult.PASS;
            }
            if (player.getStackInHand(hand).isOf(Items.END_CRYSTAL)
                    && predictBlocks(hit.getBlockPos().getX() + 0.5, hit.getBlockPos().getY() + 1, hit.getBlockPos().getZ() + 0.5)) {
                return ActionResult.FAIL;   // olum tahmini: yeni kristal koyma
            }
            if (anchor && enabled) {   // anchor'a tikladim: patlarsa benim sayilir
                anchorTime = System.currentTimeMillis();
                anchorX = hit.getBlockPos().getX();
                anchorY = hit.getBlockPos().getY();
                anchorZ = hit.getBlockPos().getZ();
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient()) return ActionResult.PASS;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (enabled) scan(mc);                               // tiktan hemen once olum kontrolu
            if (entity instanceof EndCrystalEntity) {
                if (locked()) return ActionResult.FAIL;
                if (predictBlocks(entity.getX(), entity.getY(), entity.getZ())) return ActionResult.FAIL;
                armPredict(mc, entity.getX(), entity.getY(), entity.getZ());   // olmek uzere biri varsa hemen kilitle
                crystalHitTime = System.currentTimeMillis();
                crystalX = entity.getX();
                crystalY = entity.getY();
                crystalZ = entity.getZ();
            } else if (entity instanceof PlayerEntity) {
                lastHit.put(entity.getName().getString(), System.currentTimeMillis());
            }
            return ActionResult.PASS;
        });
    }
}
