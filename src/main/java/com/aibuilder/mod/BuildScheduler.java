package com.aibuilder.mod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Places queued builds a little at a time across many server ticks, instead
 * of all at once, so a big build doesn't freeze the server for a moment.
 *
 * Registers exactly ONE tick listener for the whole mod's lifetime (Fabric's
 * event bus has no "unregister", so we never create a listener per build —
 * everything goes through this single shared queue instead).
 */
public final class BuildScheduler {

    /** One in-progress build. onComplete runs once every block has been placed. */
    public record Job(ServerLevel level, BlockPos origin, Iterator<BuildPlanParser.PlacedBlock> blocks,
                       Runnable onComplete) {
    }

    // The standard "update neighbors + sync to client" flag, same value Block.UPDATE_ALL uses.
    private static final int SET_BLOCK_FLAGS = 3;

    private static final Deque<Job> queue = new ArrayDeque<>();
    private static boolean registered = false;

    private BuildScheduler() {
    }

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    public static void enqueue(Job job) {
        queue.addLast(job);
    }

    private static void tick() {
        int budget = ModConfig.blocksPerTick;

        while (budget > 0 && !queue.isEmpty()) {
            Job job = queue.peekFirst();

            while (budget > 0 && job.blocks().hasNext()) {
                BuildPlanParser.PlacedBlock pb = job.blocks().next();
                BlockPos pos = job.origin().offset(pb.x(), pb.y(), pb.z());
                job.level().setBlock(pos, pb.state(), SET_BLOCK_FLAGS);
                budget--;
            }

            if (!job.blocks().hasNext()) {
                queue.pollFirst();
                job.onComplete().run();
            } else {
                // This job used up the whole tick's budget; resume it next tick.
                break;
            }
        }
    }
}
