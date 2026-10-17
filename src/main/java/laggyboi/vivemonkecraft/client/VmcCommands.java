package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.compat.VmcCompat;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// =====================================================================
// THE /vmc CLIENT COMMAND — shared by every loader
// =====================================================================
//
// Built GENERICALLY over the brigadier source type <S>. That's the trick that
// lets this be shared code: Fabric dispatches client commands with
// FabricClientCommandSource while NeoForge/Forge use CommandSourceStack, but not
// one command body below actually reads the source — they all go through
// Minecraft.getInstance(). So the tree is source-agnostic and each loader's
// bootstrap just calls build() with its own S and registers the result.
//
//   /vmc               -> toggle on/off
//   /vmc on|off        -> set explicitly
//   /vmc reload        -> re-read the config file
//   /vmc set <k> <v>   -> set one setting by name (tab-completes)
//   /vmc gravity <0-1> -> set the gravity multiplier (operator only)
// =====================================================================
public final class VmcCommands {

    private VmcCommands() {}

    public static <S> LiteralArgumentBuilder<S> build() {
        return LiteralArgumentBuilder.<S>literal("vmc")
            .executes(ctx -> { VivemonkecraftClient.cmdToggle(); return 1; })
            .then(LiteralArgumentBuilder.<S>literal("on")
                .executes(ctx -> { VivemonkecraftClient.cmdSetEnabled(true); return 1; }))
            .then(LiteralArgumentBuilder.<S>literal("off")
                .executes(ctx -> { VivemonkecraftClient.cmdSetEnabled(false); return 1; }))
            .then(LiteralArgumentBuilder.<S>literal("reload")
                .executes(ctx -> { VivemonkecraftClient.cmdReload(); return 1; }))
            .then(LiteralArgumentBuilder.<S>literal("set")
                .then(RequiredArgumentBuilder.<S, String>argument("setting", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        String typed = builder.getRemainingLowerCase();
                        for (String n : MovementConfig.settingNames()) {
                            if (n.toLowerCase().startsWith(typed)) builder.suggest(n);
                        }
                        return builder.buildFuture();
                    })
                    .then(RequiredArgumentBuilder.<S, String>argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            Minecraft mc  = Minecraft.getInstance();
                            String name   = StringArgumentType.getString(ctx, "setting");
                            String value  = StringArgumentType.getString(ctx, "value");
                            String result = MovementConfig.setByName(name, value);
                            if (mc.player != null) {
                                VmcCompat.overlay(mc, Component.literal(
                                    result != null
                                        ? "§e[ViveMonkeCraft] §f" + result
                                        : "§c[ViveMonkeCraft] §fUnknown setting or bad value: "
                                            + name + " " + value),
                                    false);
                            }
                            return result != null ? 1 : 0;
                        }))))
            .then(LiteralArgumentBuilder.<S>literal("gravity")
                .then(RequiredArgumentBuilder.<S, Double>argument("level", DoubleArgumentType.doubleArg(0.0, 1.0))
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        // Op-lock: requires permission level 2 (operator).
                        // In singleplayer the host is always level 4, so this always works.
                        // On a server it reflects what the server reported to the client.
                        if (mc.player == null || !VmcCompat.isOperator(mc.player)) {
                            if (mc.player != null) {
                                VmcCompat.overlay(mc,
                                    Component.literal("§c[ViveMonkeCraft] §fNeed operator access to change gravity"),
                                    false);
                            }
                            return 0;
                        }
                        double level = DoubleArgumentType.getDouble(ctx, "level");
                        MovementConfig.gravityMultiplier = level;
                        MovementConfig.save();
                        VmcCompat.overlay(mc,
                            Component.literal("§e[ViveMonkeCraft] §fGravity: §b" + level
                                + (level == 0.0 ? " §7(zero-G)" : level == 1.0 ? " §7(normal)" : "")),
                            false);
                        return 1;
                    })));
    }
}
