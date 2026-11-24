package com.aibuilder.mod.commands;

import com.aibuilder.mod.AiBuilderMod;
import com.aibuilder.mod.BuildExecutor;
import com.aibuilder.mod.BuildPlanParser;
import com.aibuilder.mod.BuildScheduler;
import com.aibuilder.mod.EventScheduler;
import com.aibuilder.mod.ModConfig;
import com.aibuilder.mod.OpenAIClient;
import com.aibuilder.mod.PlayerKeyStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /aievent <durationSeconds> <description> — an admin/OP-only "event"
 * builder. Reuses the exact same AI + BuildPlanParser pipeline as /aibuild
 * (so it can place blocks, chests, command blocks AND spawn geared-up mobs),
 * but adds one thing /aibuild doesn't have: give a durationSeconds greater
 * than 0 and the whole thing - blocks, chests, command blocks, and mobs -
 * automatically reverts back to exactly how the area was before, once that
 * many seconds pass. Pass 0 to skip the timer and leave it permanent, same
 * as /aibuild (handy for a parkour course or puzzle room meant to stay).
 *
 * This is meant for things like a temporary boss fight, a timed challenge,
 * or any other "event" an admin wants to run on a live server without
 * leaving a permanent mess behind. The revert is counted in plain Java
 * (EventScheduler), not via in-game redstone/scoreboards, so it is exact
 * and never depends on the AI building a working timer circuit itself.
 */
public final class AiEventCommand {

    // Separate from /aibuild's own busy-set: an admin can have one build and
    // one event in flight at the same time if they really want to.
    private static final Set<String> busyPlayers = ConcurrentHashMap.newKeySet();

    private AiEventCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("aievent")
                // Operators only, same as /aibuild, /aikey and /aiprovider.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(argument("durationSeconds", IntegerArgumentType.integer(0))
                        .then(argument("description", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "durationSeconds"),
                                        getString(ctx, "description"))))));
    }

    private static int run(CommandSourceStack source, int durationSeconds, String description) {
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
            source.sendFailure(Component.literal("[AI Builder] عندك حدث شغّال حالياً، استنى يخلص أولاً."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        BlockPos origin = player.blockPosition();
        String facing = player.getDirection().getSerializedName();

        player.sendSystemMessage(Component.literal("[AI Builder] جاري تصميم الحدث بالذكاء الاصطناعي..."), false);

        OpenAIClient.requestBuildPlan(key, ModConfig.model, description, facing,
                        ModConfig.maxBuildDimension, ModConfig.maxBlocksPerBuild)
                .whenComplete((rawJson, error) -> server.execute(() -> {
                    if (error != null) {
                        busyPlayers.remove(playerId);
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        AiBuilderMod.LOGGER.error("AI Builder event: request failed", cause);
                        player.sendSystemMessage(Component.literal("[AI Builder] صار خطأ: " + cause.getMessage()), false);
                        return;
                    }

                    BuildPlanParser.BuildPlan plan =
                            BuildPlanParser.parse(rawJson, ModConfig.maxBuildDimension, ModConfig.maxBlocksPerBuild);

                    if (plan.blocks().isEmpty() && plan.chests().isEmpty() && plan.commandBlocks().isEmpty()
                            && plan.mobs().isEmpty() && plan.itemRain() == null && plan.playerActions().isEmpty()) {
                        busyPlayers.remove(playerId);
                        player.sendSystemMessage(Component.literal(
                                "[AI Builder] ما طلعت نتيجة صالحة من رد الذكاء الاصطناعي. جرّب تصف الحدث بشكل أوضح."), false);
                        return;
                    }

                    ServerLevel level = player.level();

                    // Snapshot every position this event is about to touch,
                    // BEFORE any placement happens, so it can be undone exactly -
                    // restoring whatever was really there before, not just air.
                    // Always collected (even for a permanent event) - cheap, and
                    // it's what lets a "trap" player-action add its cage blocks
                    // into this exact same revert map further down.
                    Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
                    for (BuildPlanParser.PlacedBlock b : plan.blocks()) {
                        snapshot.computeIfAbsent(origin.offset(b.x(), b.y(), b.z()), level::getBlockState);
                    }
                    for (BuildPlanParser.ChestPlacement c : plan.chests()) {
                        snapshot.computeIfAbsent(origin.offset(c.x(), c.y(), c.z()), level::getBlockState);
                    }
                    for (BuildPlanParser.CommandBlockPlacement cb : plan.commandBlocks()) {
                        snapshot.computeIfAbsent(origin.offset(cb.x(), cb.y(), cb.z()), level::getBlockState);
                    }

                    player.sendSystemMessage(Component.literal(
                            "[AI Builder] جاري تنفيذ الحدث الآن (" + plan.blocks().size() + " بلوك)..."), false);

                    Runnable onComplete = () -> {
                        BuildExecutor.placeChests(level, origin, plan.chests());
                        BuildExecutor.placeCommandBlocks(level, origin, plan.commandBlocks());
                        List<Entity> spawned = BuildExecutor.spawnMobs(level, origin, plan.mobs());
                        List<Runnable> playerReverts =
                                BuildExecutor.applyPlayerActions(server, level, plan.playerActions(), snapshot);

                        if (plan.itemRain() != null) {
                            int rainSeconds = durationSeconds > 0 ? durationSeconds : 15;
                            EventScheduler.scheduleRepeating(4, rainSeconds,
                                    () -> BuildExecutor.spawnItemRainWave(level, origin, plan.itemRain()));
                        }

                        busyPlayers.remove(playerId);

                        if (player.isAlive()) {
                            if (durationSeconds > 0) {
                                player.sendSystemMessage(Component.literal(
                                        "[AI Builder] جاهز! الحدث راح يرجع لطبيعته تلقائياً بعد "
                                                + durationSeconds + " ثانية. ✅"), false);
                            } else {
                                player.sendSystemMessage(Component.literal("[AI Builder] خلص الحدث! ✅"), false);
                            }
                        }

                        if (durationSeconds > 0) {
                            EventScheduler.scheduleSeconds(durationSeconds, () -> {
                                for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
                                    level.setBlock(e.getKey(), e.getValue(), 3);
                                }
                                for (Entity entity : spawned) {
                                    if (!entity.isRemoved()) {
                                        entity.discard();
                                    }
                                }
                                for (Runnable revert : playerReverts) {
                                    revert.run();
                                }
                                if (player.isAlive()) {
                                    player.sendSystemMessage(Component.literal(
                                            "[AI Builder] انتهى الحدث ورجع كل شيء لطبيعته. ⏪"), false);
                                }
                            });
                        }
                    };

                    if (plan.blocks().isEmpty()) {
                        // Nothing for the tick scheduler to do; run the rest right away.
                        onComplete.run();
                    } else {
                        BuildScheduler.enqueue(new BuildScheduler.Job(level, origin, plan.blocks().iterator(), onComplete));
                    }
                }));

        return 1;
    }
}
