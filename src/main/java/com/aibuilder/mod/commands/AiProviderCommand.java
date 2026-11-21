package com.aibuilder.mod.commands;

import com.aibuilder.mod.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.literal;

/**
 * /aiprovider groq    -> switches model + apiBaseUrl to Groq's free,
 *                        OpenAI-compatible endpoint
 * /aiprovider openai  -> switches back to OpenAI's own endpoint/defaults
 * /aiprovider status  -> shows the currently active model + apiBaseUrl
 *                        (never shows the saved key itself)
 *
 * This exists so switching AI providers never requires finding or editing
 * config/aibuilder/settings.json by hand (some players, especially on
 * phone-based setups, have no easy way to browse to that file) and never
 * requires a full game restart: ModConfig's fields are updated live, in
 * memory, right away, and also saved to disk so the change survives a
 * restart too.
 */
public final class AiProviderCommand {

    private AiProviderCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("aiprovider")
                // Operators only, same as /aibuild and /aikey.
                .requires(source -> source.hasPermission(2))
                .then(literal("groq").executes(ctx -> useGroq(ctx.getSource())))
                .then(literal("openai").executes(ctx -> useOpenAi(ctx.getSource())))
                .then(literal("status").executes(ctx -> status(ctx.getSource()))));
    }

    private static int useGroq(CommandSourceStack source) {
        ModConfig.model = "openai/gpt-oss-120b";
        ModConfig.apiBaseUrl = "https://api.groq.com/openai/v1/chat/completions";
        ModConfig.save();
        source.sendSuccess(() -> Component.literal(
                "[AI Builder] تم التحويل إلى Groq ✅ (الموديل: " + ModConfig.model
                        + "). الآن حط مفتاح Groq الخاص فيك بالأمر: /aikey <مفتاحك>"), false);
        return 1;
    }

    private static int useOpenAi(CommandSourceStack source) {
        ModConfig.model = "gpt-4o-mini";
        ModConfig.apiBaseUrl = "https://api.openai.com/v1/chat/completions";
        ModConfig.save();
        source.sendSuccess(() -> Component.literal(
                "[AI Builder] تم التحويل إلى OpenAI ✅ (الموديل: " + ModConfig.model
                        + "). الآن حط مفتاح OpenAI الخاص فيك بالأمر: /aikey <مفتاحك>"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[AI Builder] المزود الحالي -> الموديل: " + ModConfig.model
                        + " | الرابط: " + ModConfig.apiBaseUrl), false);
        return 1;
    }
}
