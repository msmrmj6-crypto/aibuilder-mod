package com.aibuilder.mod.commands;

import com.aibuilder.mod.PlayerKeyStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /aikey <key>    -> saves your own OpenAI API key
 * /aikey status   -> tells you whether a key is saved (never shows it)
 * /aikey clear    -> forgets your saved key
 *
 * Each player stores their own key; see PlayerKeyStore for how/where.
 */
public final class AiKeyCommand {

    private AiKeyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("aikey")
                .then(literal("clear").executes(ctx -> clear(ctx.getSource())))
                .then(literal("status").executes(ctx -> status(ctx.getSource())))
                .then(argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> setKey(ctx.getSource(), getString(ctx, "key")))));
    }

    private static int setKey(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("هذا الأمر يعمل فقط من داخل اللعبة."));
            return 0;
        }

        key = key.trim();
        if (key.isEmpty()) {
            source.sendFailure(Component.literal("اكتب مفتاحك بعد الأمر، مثال: /aikey sk-xxxxxxxx"));
            return 0;
        }

        PlayerKeyStore.setKey(player.getUUID(), key);
        source.sendSuccess(() -> Component.literal(
                "[AI Builder] تم حفظ مفتاحك. جرّب الآن مثلاً: /aibuild بيت خشبي صغير بسقف مائل"), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        PlayerKeyStore.clearKey(player.getUUID());
        source.sendSuccess(() -> Component.literal("[AI Builder] تم حذف مفتاحك المحفوظ."), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean has = PlayerKeyStore.hasKey(player.getUUID());
        source.sendSuccess(() -> Component.literal(has
                ? "[AI Builder] عندك مفتاح محفوظ حالياً."
                : "[AI Builder] لا يوجد مفتاح محفوظ. استخدم: /aikey <مفتاحك>"), false);
        return 1;
    }
}
