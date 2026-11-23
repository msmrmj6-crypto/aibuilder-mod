package com.aibuilder.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Places chests/command blocks/mobs from a parsed {@link BuildPlanParser.BuildPlan}
 * into the world. Shared by /aibuild (permanent) and /aievent (temporary,
 * auto-reverted) so both commands place things the exact same way.
 */
public final class BuildExecutor {

    private BuildExecutor() {
    }

    /**
     * Places every chest the AI asked for and fills its inventory with the
     * items it specified, one stack per slot in the order given.
     */
    public static void placeChests(ServerLevel level, BlockPos origin, List<BuildPlanParser.ChestPlacement> chests) {
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
     * only ever run for whoever is allowed to trigger an AI build in the
     * first place (operators), same as typing the command themselves would.
     */
    public static void placeCommandBlocks(ServerLevel level, BlockPos origin,
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

    /**
     * Spawns every mob the AI asked for, geared up with whatever equipment
     * was given, and returns the spawned entities so a caller (the timed
     * /aievent command) can despawn them again later. /aibuild just ignores
     * the returned list - its mobs stay permanently, same as its blocks.
     */
    public static List<Entity> spawnMobs(ServerLevel level, BlockPos origin, List<BuildPlanParser.MobSpawn> mobs) {
        List<Entity> spawned = new ArrayList<>();
        if (mobs.isEmpty()) {
            return spawned;
        }

        for (BuildPlanParser.MobSpawn mobSpawn : mobs) {
            BlockPos pos = origin.offset(mobSpawn.x(), mobSpawn.y(), mobSpawn.z());
            for (int i = 0; i < mobSpawn.count(); i++) {
                Entity entity = mobSpawn.type().create(level, EntitySpawnReason.COMMAND);
                if (entity == null) {
                    continue;
                }
                entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

                if (entity instanceof LivingEntity living) {
                    for (Map.Entry<EquipmentSlot, net.minecraft.world.item.Item> entry : mobSpawn.equipment().entrySet()) {
                        living.setItemSlot(entry.getKey(), new ItemStack(entry.getValue()));
                    }
                }

                level.addFreshEntity(entity);
                spawned.add(entity);
            }
        }

        return spawned;
    }
}
