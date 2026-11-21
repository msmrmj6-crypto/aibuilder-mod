package com.aibuilder.mod.commands;

import com.aibuilder.mod.AiBuilderMod;
import com.aibuilder.mod.BuildPlanParser;
import com.aibuilder.mod.BuildScheduler;
import com.aibuilder.mod.ModConfig;
import com.aibuilder.mod.OpenAIClient;
import com.aibuilder.mod.PlayerKeyStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /aibuild <description> — sends the description + the player's own saved
 * key to the AI, then places whatever bounded block plan comes back, a
 * little at a time, in front of the player.
 */
public final class AiBuildCommand {

    // Stops one player from queueing a second build while the first is still
    // waiting on the AI or still being placed.
    private static final Set<String> busyPlayers = ConcurrentHashMap.newKeySet();

    private AiBuildCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("aibuild")
                .then(argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), getString(ctx, "description")))));
    }

    private static int run(CommandSourceStack source, String description) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("هذا الأمر يعمل فقط من داخل اللعبة."));
            return 0;
        }

        String key = PlayerKeyStore.getKey(player.getUUID());
        if (key == null || key.isBlank()) {
            source.sendFailure(Component.literal("ما عندك مفتاح محفوظ بعد. استخدم أولاً: /aikey <مفتاحك>"));
            return 0;
        }

        String playerId = player.getStringUUID();
        if (!busyPlayers.add(playerId)) {
            source.sendFailure(Component.literal("[AI Builder] عندك طلب بناء شغّال حالياً، استنى يخلص أولاً."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        BlockPos origin = player.blockPosition();
        String facing = player.getDirection().getSerializedName();

        player.sendSystemMessage(Component.literal("[AI Builder] جاري تصميم الفكرة بالذكاء الاصطناعي..."), false);

        OpenAIClient.requestBuildPlan(key, ModConfig.model, description, facing,
                        ModConfig.maxBuildDimension, ModConfig.maxBlocksPerBuild)
                .whenComplete((rawJson, error) -> server.execute(() -> {
                    if (error != null) {
                        busyPlayers.remove(playerId);
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        AiBuilderMod.LOGGER.error("AI Builder: request failed", cause);
                        player.sendSystemMessage(Component.literal("[AI Builder] صار خطأ: " + cause.getMessage()), false);
                        return;
                    }

                    BuildPlanParser.BuildPlan plan =
                            BuildPlanParser.parse(rawJson, ModConfig.maxBuildDimension, ModConfig.maxBlocksPerBuild);

                    if (plan.blocks().isEmpty() && plan.chests().isEmpty()) {
                        busyPlayers.remove(playerId);
                        player.sendSystemMessage(Component.literal(
                                "[AI Builder] ما طلعت بلوكات صالحة من رد الذكاء الاصطناعي. جرّب تصف الفكرة بشكل أوضح."), false);
                        return;
                    }

                    player.sendSystemMessage(Component.literal(
                            "[AI Builder] جاري البناء الآن (" + plan.blocks().size() + " بلوك)..."), false);

                    Runnable onComplete = () -> {
                        placeChests(player.level(), origin, plan.chests());
                        busyPlayers.remove(playerId);
                        if (player.isAlive()) {
                            player.sendSystemMessage(Component.literal("[AI Builder] خلص البناء! ✅"), false);
                        }
                    };

                    if (plan.blocks().isEmpty()) {
                        // Nothing for the tick scheduler to do (e.g. "put a sword in a
                        // chest here"); place the chest(s) right away.
                        onComplete.run();
                    } else {
                        BuildScheduler.enqueue(new BuildScheduler.Job(
                                player.level(),
                                origin,
                                plan.blocks().iterator(),
                                onComplete));
                    }
                }));

        return 1;
    }

    /**
     * Places every chest the AI asked for and fills its inventory with the
     * items it specified, one stack per slot in the order given.
     */
    private static void placeChests(ServerLevel level, BlockPos origin, List<BuildPlanParser.ChestPlacement> chests) {
        if (chests.isEmpty()) {
            return;
        }

        BlockState chestState = BuildPlanParser.resolveBlock("minecraft:chest", new ArrayList<>());
        if (chestState == null) {
            return;
        }

        for (BuildPlanParser.ChestPlacement chest : chests) {
            BlockPos pos = origin.offset(chest.x(), chest.y(), chest.z());
            level.setBlock(pos, chestState, 3);

            if (level.getBlockEntity(pos) instanceof Container container) {
                int slot = 0;
                for (BuildPlanParser.ChestItem item : chest.items()) {
                    if (slot >= container.getContainerSize()) {
                        break;
                    }
                    container.setItem(slot, new ItemStack(item.item(), item.count()));
                    slot++;
                }
            }
        }
    }
}
