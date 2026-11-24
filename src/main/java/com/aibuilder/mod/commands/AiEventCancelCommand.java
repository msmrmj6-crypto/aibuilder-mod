package com.aibuilder.mod.commands;

import com.aibuilder.mod.EventScheduler;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.literal;

/**
 * /aieventcancel — ends whatever /aievent(s) the issuing player currently has
 * pending RIGHT NOW, instead of waiting for their timer to run out. Useful
 * when an event was described wrong (bad coordinates, the wrong player
 * trapped, an item rain that's too much, etc.) and the admin wants it undone
 * immediately rather than living with it until the original duration passes.
 *
 * This runs the exact same revert logic a timer would have run anyway
 * (restore blocks, despawn mobs, give items/flight back) - just early.
 */
public final class AiEventCancelCommand {

    private AiEventCancelCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("aieventcancel")
                // Operators only, same as /aievent itself.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> run(ctx.getSource())));
    }

    private static int run(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("هذا الأمر يعمل فقط من داخل اللعبة."));
            return 0;
        }

        int ended = EventScheduler.cancelAndRevertNow(player.getStringUUID());
        if (ended == 0) {
            player.sendSystemMessage(Component.literal(
                    "[AI Builder] ما عندك أي حدث شغّال حالياً تنتظر مدته تخلص."), false);
        } else {
            player.sendSystemMessage(Component.literal(
                    "[AI Builder] تم إنهاء الحدث فوراً ورجع كل شيء لطبيعته. ⏪"), false);
        }
        return 1;
    }
}
