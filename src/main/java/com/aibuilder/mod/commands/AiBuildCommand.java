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
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
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
                // Operators only: regular players never see this command exist.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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

                    if (plan.blocks().isEmpty() && plan.chests().isEmpty() && plan.commandBlocks().isEmpty()) {
                        busyPlayers.remove(playerId);
                        player.sendSystemMessage(Component.literal(
                                "[AI Builder] ما طلعت بلوكات صالحة من رد الذكاء الاصطناعي. جرّب تصف الفكرة بشكل أوضح."), false);
                        return;
                    }

                    player.sendSystemMessage(Component.literal(
                            "[AI Builder] جاري البناء الآن (" + plan.blocks().size() + " بلوك)..."), false);

                    Runnable onComplete = () -> {
                        placeChests(player.level(), origin, plan.chests());
                        placeCommandBlocks(player.level(), origin, plan.commandBlocks());
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

        BlockState baseChestState = BuildPlanParser.resolveBlock("minecraft:chest", new ArrayList<>());
        if (baseChestState == null) {
            return;
        }

        for (BuildPlanParser.ChestPlacement chest : chests) {
            BlockPos pos = origin.offset(chest.x(), chest.y(), chest.z());
            BlockState chestState = BuildPlanParser.withFacing(baseChestState, chest.facing());
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

    /**
     * Places every command block the AI asked for and stores its command in
     * it - e.g. a button wired to a command block that gives the player a
     * kit. A multi-item kit comes in as a real chain: the first block is an
     * impulse one (needs redstone - the button/lever triggers it) and the
     * rest are chain blocks set to "Always Active", so pressing the one
     * button fires every command in the chain, each giving one item. These
     * only ever run for whoever is allowed to use /aibuild in the first
     * place (operators), same as typing the command themselves would.
     */
    private static void placeCommandBlocks(ServerLevel level, BlockPos origin,
                                            List<BuildPlanParser.CommandBlockPlacement> commandBlocks) {
        if (commandBlocks.isEmpty()) {
            return;
        }

        for (BuildPlanParser.CommandBlockPlacement cmd : commandBlocks) {
            BlockState baseState = BuildPlanParser.resolveBlock(
                    BuildPlanParser.commandBlockId(cmd.type()), new ArrayList<>());
            if (baseState == null) {
                continue;
            }

            BlockPos pos = origin.offset(cmd.x(), cmd.y(), cmd.z());
            BlockState state = BuildPlanParser.withCommandFacing(baseState, cmd.facing());
            level.setBlock(pos, state, 3);

            if (level.getBlockEntity(pos) instanceof CommandBlockEntity commandBlockEntity) {
                commandBlockEntity.getCommandBlock().setCommand(cmd.command());
                commandBlockEntity.setAutomatic(cmd.auto());
                commandBlockEntity.setChanged();
            }
        }
    }
}
