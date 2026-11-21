package com.aibuilder.mod;

import com.aibuilder.mod.commands.AiBuildCommand;
import com.aibuilder.mod.commands.AiKeyCommand;
import com.aibuilder.mod.commands.AiProviderCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiBuilderMod implements ModInitializer {

    public static final String MOD_ID = "aibuilder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModConfig.load();
        PlayerKeyStore.load();
        BuildScheduler.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            AiKeyCommand.register(dispatcher);
            AiBuildCommand.register(dispatcher);
            AiProviderCommand.register(dispatcher);
        });

        // As soon as a player joins, if they have no key saved yet, ask for one -
        // but only operators ever see this (and only operators can use the
        // /aibuild, /aikey, /aiprovider commands at all; see the .requires(...)
        // on each of them). Regular players never even see these commands exist.
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            ServerPlayer player = listener.player;
            server.execute(() -> {
                boolean isOperator = player.createCommandSourceStack().hasPermission(2);
                if (isOperator && !PlayerKeyStore.hasKey(player.getUUID())) {
                    player.sendSystemMessage(Component.literal(
                            "[AI Builder] مرحباً! لتفعيل البناء بالذكاء "
                                    + "الاصطناعي، ضع مفتاح "
                                    + "OpenAI API الخاص بك بالأمر:"
                    ), false);
                    player.sendSystemMessage(Component.literal("§e/aikey <مفتاحك>"), false);
                }
            });
        });
    }
}
