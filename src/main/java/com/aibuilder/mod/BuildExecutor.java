package com.aibuilder.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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

    /**
     * Spawns one small burst of a few random items from the given rain spec,
     * scattered above a random spot within radius of origin, falling
     * naturally (gravity + a little scatter velocity) so players underneath
     * can walk up and grab them. Meant to be called repeatedly (every few
     * ticks) by {@link EventScheduler#scheduleRepeating} for the event's
     * whole duration - a single call only drops 1-3 items.
     */
    public static void spawnItemRainWave(ServerLevel level, BlockPos origin, BuildPlanParser.ItemRainSpec rain) {
        if (rain == null || rain.items().isEmpty()) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int dropCount = random.nextInt(1, 4);

        for (int i = 0; i < dropCount; i++) {
            Item item = rain.items().get(random.nextInt(rain.items().size()));
            double x = origin.getX() + 0.5 + random.nextDouble(-rain.radius(), rain.radius());
            double z = origin.getZ() + 0.5 + random.nextDouble(-rain.radius(), rain.radius());
            double y = origin.getY() + 15;

            net.minecraft.world.entity.item.ItemEntity itemEntity = new net.minecraft.world.entity.item.ItemEntity(
                    level, x, y, z, new ItemStack(item),
                    random.nextDouble(-0.05, 0.05), -0.1, random.nextDouble(-0.05, 0.05));
            level.addFreshEntity(itemEntity);
        }
    }

    /**
     * Carries out every per-player action the AI asked for, against whichever
     * named players are actually online right now (anyone not found is
     * silently skipped - nothing to apply this to). "kill" happens once, on
     * the spot. "trap" adds cage blocks directly into the shared block
     * snapshot/placement so it reverts exactly like everything else /aievent
     * places. "fly" and "takeItems" return a revert Runnable each, which the
     * caller only needs to run if/when the timed event actually ends.
     */
    public static List<Runnable> applyPlayerActions(MinecraftServer server, ServerLevel level,
                                                      List<BuildPlanParser.PlayerAction> actions,
                                                      Map<BlockPos, BlockState> snapshot) {
        List<Runnable> reverts = new ArrayList<>();
        if (actions.isEmpty()) {
            return reverts;
        }

        BlockState bars = BuildPlanParser.resolveBlock("minecraft:iron_bars", new ArrayList<>());

        for (BuildPlanParser.PlayerAction action : actions) {
            ServerPlayer target = server.getPlayerList().getPlayerByName(action.playerName());
            if (target == null) {
                continue;
            }

            if (action.trap() && bars != null) {
                BlockPos center = target.blockPosition();
                for (BlockPos cagePos : cagePositions(center)) {
                    snapshot.computeIfAbsent(cagePos, level::getBlockState);
                    level.setBlock(cagePos, bars, 3);
                }
            }

            if (action.fly()) {
                Abilities abilities = target.getAbilities();
                boolean originalMayfly = abilities.mayfly;
                boolean originalFlying = abilities.flying;
                abilities.mayfly = true;
                abilities.flying = true;
                target.onUpdateAbilities();

                reverts.add(() -> {
                    if (!target.isRemoved()) {
                        Abilities current = target.getAbilities();
                        current.mayfly = originalMayfly;
                        current.flying = originalFlying;
                        target.onUpdateAbilities();
                    }
                });
            }

            if (action.takeItems()) {
                Inventory inventory = target.getInventory();
                List<ItemStack> savedMain = new ArrayList<>();
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    savedMain.add(inventory.getItem(i).copy());
                }
                inventory.clearContent();

                Map<EquipmentSlot, ItemStack> savedEquipment = new java.util.LinkedHashMap<>();
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    savedEquipment.put(slot, target.getItemBySlot(slot).copy());
                    target.setItemSlot(slot, ItemStack.EMPTY);
                }

                reverts.add(() -> {
                    if (!target.isRemoved()) {
                        for (int i = 0; i < savedMain.size() && i < inventory.getContainerSize(); i++) {
                            inventory.setItem(i, savedMain.get(i));
                        }
                        for (Map.Entry<EquipmentSlot, ItemStack> e : savedEquipment.entrySet()) {
                            target.setItemSlot(e.getKey(), e.getValue());
                        }
                    }
                });
            }

            if (action.kill()) {
                target.kill(level);
            }
        }

        return reverts;
    }

    /**
     * A hollow iron-bars shell around (and one above) a 1x2 standing space at
     * center, leaving only the center column (where the player already is)
     * empty - a simple see-through jail cell built around wherever the
     * targeted player currently stands.
     */
    private static List<BlockPos> cagePositions(BlockPos center) {
        List<BlockPos> positions = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean isShell = dx == -1 || dx == 1 || dz == -1 || dz == 1 || dy == -1 || dy == 2;
                    boolean isPlayerColumn = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                    if (isShell && !isPlayerColumn) {
                        positions.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        return positions;
    }
}
