package com.aibuilder.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the raw text the AI replied with into a validated, bounded list of
 * block placements. Nothing here is executed in the world yet — this is
 * just parsing + sanity-checking.
 *
 * The AI can describe a build these ways, combined freely:
 *  - "fills": a solid rectangular box of one block (floors, roof slabs,
 *    and - with "facing" set - a whole row of oriented stairs for a real
 *    sloped roof in one entry).
 *  - "hollowBoxes": only the outer shell of a box is solid, the inside stays
 *    empty — this is how whole rooms/building bodies get their straight
 *    walls without the AI having to enumerate every wall block itself.
 *  - "blocks": individual blocks, applied last, for door/window openings
 *    and small details.
 *  - "chests": places a chest and fills it with specific items.
 *
 * Any "fills"/"blocks"/"chests" entry may also set an optional "facing"
 * ("north"/"south"/"east"/"west"); it is applied to the block's FACING
 * property when that block has one (stairs, doors, trapdoors, furnaces,
 * chests, ...), and silently ignored for blocks that don't.
 */
public final class BuildPlanParser {

    /** A chest only has this many inventory slots. */
    private static final int CHEST_SLOTS = 27;

    private BuildPlanParser() {
    }

    /** A single block to place, in coordinates relative to the player. */
    public record PlacedBlock(int x, int y, int z, BlockState state) {
    }

    /** One item stack the AI wants inside a chest. */
    public record ChestItem(Item item, int count) {
    }

    /** A chest to place, in coordinates relative to the player, with its contents. */
    public record ChestPlacement(int x, int y, int z, Direction facing, List<ChestItem> items) {
    }

    /**
     * blocks = what we will place. chests = chests to place and fill.
     * skipped = block/item ids the AI gave that we could not resolve.
     */
    public record BuildPlan(List<PlacedBlock> blocks, List<ChestPlacement> chests, List<String> skipped) {
    }

    public static BuildPlan parse(String rawJson, int maxDimension, int maxBlocks) {
        List<PlacedBlock> result = new ArrayList<>();
        List<ChestPlacement> chests = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        JsonObject root;
        try {
            root = JsonParser.parseString(extractJson(rawJson)).getAsJsonObject();
        } catch (Exception e) {
            return new BuildPlan(result, chests, skipped);
        }

        int half = Math.max(1, maxDimension / 2);

        if (root.has("fills") && root.get("fills").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("fills")) {
                if (result.size() >= maxBlocks) break;
                try {
                    addBox(el.getAsJsonObject(), half, maxDimension, maxBlocks, result, skipped, false);
                } catch (Exception e) {
                    // Malformed entry from the model; skip it and keep going.
                }
            }
        }

        if (root.has("hollowBoxes") && root.get("hollowBoxes").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("hollowBoxes")) {
                if (result.size() >= maxBlocks) break;
                try {
                    addBox(el.getAsJsonObject(), half, maxDimension, maxBlocks, result, skipped, true);
                } catch (Exception e) {
                    // Malformed entry from the model; skip it and keep going.
                }
            }
        }

        if (root.has("blocks") && root.get("blocks").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("blocks");

            for (int i = 0; i < arr.size() && result.size() < maxBlocks; i++) {
                try {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    int z = obj.get("z").getAsInt();
                    String blockId = obj.get("block").getAsString();

                    // Keep every build inside a safe bounding box around the player.
                    if (x < -half || x > half || z < -half || z > half || y < 0 || y > maxDimension) {
                        continue;
                    }

                    BlockState state = resolveBlock(blockId, skipped);
                    if (state == null) {
                        continue;
                    }
                    state = withFacing(state, parseDirection(getString(obj, "facing")));

                    result.add(new PlacedBlock(x, y, z, state));
                } catch (Exception e) {
                    // Malformed entry from the model; skip it and keep going.
                }
            }
        }

        if (root.has("chests") && root.get("chests").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("chests")) {
                try {
                    JsonObject obj = el.getAsJsonObject();
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    int z = obj.get("z").getAsInt();

                    if (x < -half || x > half || z < -half || z > half || y < 0 || y > maxDimension) {
                        continue;
                    }

                    Direction facing = parseDirection(getString(obj, "facing"));

                    List<ChestItem> items = new ArrayList<>();
                    if (obj.has("items") && obj.get("items").isJsonArray()) {
                        for (JsonElement itemEl : obj.getAsJsonArray("items")) {
                            if (items.size() >= CHEST_SLOTS) break;
                            try {
                                JsonObject itemObj = itemEl.getAsJsonObject();
                                String itemId = itemObj.get("item").getAsString();
                                int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 1;
                                count = Math.max(1, Math.min(count, 64));

                                Item item = resolveItem(itemId, skipped);
                                if (item == null) {
                                    continue;
                                }

                                items.add(new ChestItem(item, count));
                            } catch (Exception e) {
                                // Malformed item entry; skip it and keep going.
                            }
                        }
                    }

                    chests.add(new ChestPlacement(x, y, z, facing, items));
                } catch (Exception e) {
                    // Malformed chest entry; skip it and keep going.
                }
            }
        }

        return new BuildPlan(result, chests, skipped);
    }

    /**
     * Expands one "fills"/"hollowBoxes" entry into individual placed blocks,
     * clamped to the safe bounding box and to the remaining block budget.
     * When hollow is true, only the outer shell of the box is emitted (the
     * fully-interior cells are left untouched, i.e. a hollow room shell).
     * An optional "facing" on the entry is applied to every block in it -
     * useful for a whole row of stairs making up one slope of a roof.
     */
    private static void addBox(JsonObject obj, int half, int maxDimension, int maxBlocks,
                                List<PlacedBlock> result, List<String> skipped, boolean hollow) {
        int x1 = obj.get("x1").getAsInt();
        int y1 = obj.get("y1").getAsInt();
        int z1 = obj.get("z1").getAsInt();
        int x2 = obj.get("x2").getAsInt();
        int y2 = obj.get("y2").getAsInt();
        int z2 = obj.get("z2").getAsInt();
        String blockId = obj.get("block").getAsString();

        int minX = Math.max(-half, Math.min(x1, x2));
        int maxX = Math.min(half, Math.max(x1, x2));
        int minY = Math.max(0, Math.min(y1, y2));
        int maxY = Math.min(maxDimension, Math.max(y1, y2));
        int minZ = Math.max(-half, Math.min(z1, z2));
        int maxZ = Math.min(half, Math.max(z1, z2));

        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return;
        }

        BlockState state = resolveBlock(blockId, skipped);
        if (state == null) {
            return;
        }
        state = withFacing(state, parseDirection(getString(obj, "facing")));

        for (int x = minX; x <= maxX && result.size() < maxBlocks; x++) {
            for (int y = minY; y <= maxY && result.size() < maxBlocks; y++) {
                for (int z = minZ; z <= maxZ && result.size() < maxBlocks; z++) {
                    boolean interior = x != minX && x != maxX && y != minY && y != maxY && z != minZ && z != maxZ;
                    if (hollow && interior) {
                        continue;
                    }
                    result.add(new PlacedBlock(x, y, z, state));
                }
            }
        }
    }

    /** Public so AiBuildCommand can resolve the "minecraft:chest" block the same way. */
    public static BlockState resolveBlock(String blockId, List<String> skipped) {
        Identifier id = Identifier.tryParse(blockId.trim());
        if (id == null) {
            skipped.add(blockId);
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            skipped.add(blockId);
            return null;
        }

        return block.defaultBlockState();
    }

    /**
     * Sets the block's horizontal FACING property (shared by stairs, doors,
     * trapdoors, furnaces, chests, fence gates, ...) when it has one and a
     * direction was given; returns the state unchanged otherwise. Public so
     * AiBuildCommand can orient a chest the same way.
     */
    public static BlockState withFacing(BlockState state, Direction facing) {
        if (facing != null && state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.setValue(HorizontalDirectionalBlock.FACING, facing);
        }
        return state;
    }

    private static Direction parseDirection(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private static String getString(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : null;
    }

    private static Item resolveItem(String itemId, List<String> skipped) {
        Identifier id = Identifier.tryParse(itemId.trim());
        if (id == null) {
            skipped.add(itemId);
            return null;
        }

        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            skipped.add(itemId);
            return null;
        }

        return item;
    }

    /**
     * Models sometimes wrap JSON in ```json fences even when told not to.
     * Strip those defensively so a good answer isn't thrown away.
     */
    private static String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
