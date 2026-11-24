package com.aibuilder.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *  - "commandBlocks": places an (impulse) command block with a stored
 *    command, e.g. for a button that gives the player a kit.
 *  - "mobs": spawns a mob, optionally geared up with real equipment.
 *
 * Any "fills"/"blocks"/"chests"/"commandBlocks" entry may also set an
 * optional "facing"
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
     * A command block to place, in coordinates relative to the player, with its stored command.
     * type is "impulse" (fires once per redstone pulse — the one a button/lever triggers),
     * "chain" (auto-fires right after the block behind it in the chain succeeds — used for the
     * 2nd+ command of a multi-item kit) or "repeat" (fires every tick while powered). auto mirrors
     * the "Always Active" toggle (true) vs "Needs Redstone" (false) — a chain block is always auto=true
     * so it doesn't need its own redstone, only the impulse block at the start of the chain does.
     */
    public record CommandBlockPlacement(int x, int y, int z, Direction facing, String type, boolean auto,
                                         String command) {
    }

    /**
     * A mob to spawn, in coordinates relative to the player, optionally geared
     * up with real equipment (any slot not present in the map is left empty).
     * count spawns that many copies at the same position (a small group).
     */
    public record MobSpawn(int x, int y, int z, EntityType<?> type, int count, Map<EquipmentSlot, Item> equipment) {
    }

    /**
     * A continuous "rain" of random items from this list, falling from above
     * somewhere within radius blocks of the event's origin. Only meaningful
     * for the timed /aievent command - /aibuild ignores this field entirely.
     */
    public record ItemRainSpec(List<Item> items, int radius) {
    }

    /**
     * Something to do to one specific, currently-online player, found by
     * their exact in-game username. Only meaningful for /aievent - /aibuild
     * ignores this field entirely. trap/fly/takeItems are reverted
     * automatically when a timed event ends (same as everything else it
     * placed); kill happens once, immediately, and is never "reverted".
     */
    public record PlayerAction(String playerName, boolean trap, boolean fly, boolean kill, boolean takeItems) {
    }

    /**
     * blocks = what we will place. chests = chests to place and fill.
     * commandBlocks = command blocks to place with their stored command.
     * mobs = mobs to spawn, optionally geared up.
     * itemRain = a continuous rain of items (null if none was asked for).
     * playerActions = things to do to specific named players.
     * weather = "clear"/"rain"/"thunder" (null if not asked for).
     * time = target day-time in ticks (null if not asked for).
     * immediateCommands = raw vanilla command strings to run once, right
     * away, with full operator permission (null-filtered, already capped).
     * skipped = block/item ids the AI gave that we could not resolve.
     */
    public record BuildPlan(List<PlacedBlock> blocks, List<ChestPlacement> chests,
                             List<CommandBlockPlacement> commandBlocks, List<MobSpawn> mobs,
                             ItemRainSpec itemRain, List<PlayerAction> playerActions,
                             String weather, Long time, List<String> immediateCommands, List<String> skipped) {
    }

    public static BuildPlan parse(String rawJson, int maxDimension, int maxBlocks) {
        List<PlacedBlock> result = new ArrayList<>();
        List<ChestPlacement> chests = new ArrayList<>();
        List<CommandBlockPlacement> commandBlocks = new ArrayList<>();
        List<MobSpawn> mobs = new ArrayList<>();
        List<PlayerAction> playerActions = new ArrayList<>();
        List<String> immediateCommands = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        JsonObject root;
        try {
            root = JsonParser.parseString(extractJson(rawJson)).getAsJsonObject();
        } catch (Exception e) {
            return new BuildPlan(result, chests, commandBlocks, mobs, null, playerActions, null, null,
                    immediateCommands, skipped);
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

        if (root.has("commandBlocks") && root.get("commandBlocks").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("commandBlocks")) {
                try {
                    JsonObject obj = el.getAsJsonObject();
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    int z = obj.get("z").getAsInt();

                    if (x < -half || x > half || z < -half || z > half || y < 0 || y > maxDimension) {
                        continue;
                    }

                    String rawCommand = obj.has("command") ? obj.get("command").getAsString() : "";
                    String type = normalizeCommandBlockType(getString(obj, "type"));
                    boolean auto = obj.has("auto") && obj.get("auto").getAsBoolean();
                    Direction facing = parseDirection(getString(obj, "facing"));

                    // A single vanilla command block can only ever store and run ONE
                    // command. The AI is told this, but - especially for a multi-item
                    // "kit" - sometimes still writes several give commands into one
                    // "command" string anyway (joined by a literal newline or a
                    // semicolon), which then errors out in-game instead of working.
                    // Defensively split that back apart here and auto-build the real
                    // fix for it: a physical chain of command blocks (first = impulse,
                    // triggered by the button; the rest = chain + always-active, so
                    // they fire automatically right after it), one command each.
                    List<String> parts = splitIntoSingleCommands(rawCommand);
                    if (parts.isEmpty()) {
                        continue;
                    }

                    if (parts.size() == 1) {
                        commandBlocks.add(new CommandBlockPlacement(x, y, z, facing, type, auto, parts.get(0)));
                    } else {
                        Direction chainDir = facing != null ? facing : Direction.EAST;
                        for (int i = 0; i < parts.size(); i++) {
                            int px = x + chainDir.getStepX() * i;
                            int py = y + chainDir.getStepY() * i;
                            int pz = z + chainDir.getStepZ() * i;
                            String t = i == 0 ? "impulse" : "chain";
                            boolean a = i != 0;
                            commandBlocks.add(new CommandBlockPlacement(px, py, pz, chainDir, t, a, parts.get(i)));
                        }
                    }
                } catch (Exception e) {
                    // Malformed command block entry; skip it and keep going.
                }
            }
        }

        if (root.has("mobs") && root.get("mobs").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("mobs")) {
                try {
                    JsonObject obj = el.getAsJsonObject();
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    int z = obj.get("z").getAsInt();

                    if (x < -half || x > half || z < -half || z > half || y < 0 || y > maxDimension) {
                        continue;
                    }

                    String entityId = obj.has("entity") ? obj.get("entity").getAsString() : "";
                    EntityType<?> type = resolveEntityType(entityId, skipped);
                    if (type == null) {
                        continue;
                    }

                    int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
                    count = Math.max(1, Math.min(count, 10));

                    Map<EquipmentSlot, Item> equipment = new LinkedHashMap<>();
                    addEquipment(obj, "mainhand", EquipmentSlot.MAINHAND, equipment, skipped);
                    addEquipment(obj, "offhand", EquipmentSlot.OFFHAND, equipment, skipped);
                    addEquipment(obj, "helmet", EquipmentSlot.HEAD, equipment, skipped);
                    addEquipment(obj, "chestplate", EquipmentSlot.CHEST, equipment, skipped);
                    addEquipment(obj, "leggings", EquipmentSlot.LEGS, equipment, skipped);
                    addEquipment(obj, "boots", EquipmentSlot.FEET, equipment, skipped);

                    mobs.add(new MobSpawn(x, y, z, type, count, equipment));
                } catch (Exception e) {
                    // Malformed mob entry; skip it and keep going.
                }
            }
        }

        ItemRainSpec itemRain = null;
        if (root.has("itemRain") && root.get("itemRain").isJsonObject()) {
            try {
                JsonObject obj = root.getAsJsonObject("itemRain");
                List<Item> rainItems = new ArrayList<>();
                if (obj.has("items") && obj.get("items").isJsonArray()) {
                    for (JsonElement itemEl : obj.getAsJsonArray("items")) {
                        Item item = resolveItem(itemEl.getAsString(), skipped);
                        if (item != null) {
                            rainItems.add(item);
                        }
                    }
                }
                if (!rainItems.isEmpty()) {
                    int radius = obj.has("radius") ? obj.get("radius").getAsInt() : Math.min(10, half);
                    radius = Math.max(3, Math.min(radius, half));
                    itemRain = new ItemRainSpec(rainItems, radius);
                }
            } catch (Exception e) {
                // Malformed itemRain object; just skip the feature.
            }
        }

        if (root.has("playerActions") && root.get("playerActions").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("playerActions")) {
                try {
                    JsonObject obj = el.getAsJsonObject();
                    String playerName = getString(obj, "player");
                    if (playerName == null || playerName.isBlank()) {
                        continue;
                    }
                    boolean trap = obj.has("trap") && obj.get("trap").getAsBoolean();
                    boolean fly = obj.has("fly") && obj.get("fly").getAsBoolean();
                    boolean kill = obj.has("kill") && obj.get("kill").getAsBoolean();
                    boolean takeItems = obj.has("takeItems") && obj.get("takeItems").getAsBoolean();
                    if (trap || fly || kill || takeItems) {
                        playerActions.add(new PlayerAction(playerName.trim(), trap, fly, kill, takeItems));
                    }
                } catch (Exception e) {
                    // Malformed playerActions entry; skip it and keep going.
                }
            }
        }

        String weather = null;
        if (root.has("weather") && root.get("weather").isJsonPrimitive()) {
            String w = root.get("weather").getAsString();
            if ("clear".equals(w) || "rain".equals(w) || "thunder".equals(w)) {
                weather = w;
            }
        }

        Long time = null;
        if (root.has("time") && root.get("time").isJsonPrimitive()) {
            try {
                String t = root.get("time").getAsString();
                time = switch (t) {
                    case "day" -> 1000L;
                    case "noon" -> 6000L;
                    case "night" -> 13000L;
                    case "midnight" -> 18000L;
                    default -> null;
                };
            } catch (Exception e) {
                // Not a recognized string keyword; leave time as null.
            }
        }

        if (root.has("immediateCommands") && root.get("immediateCommands").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("immediateCommands")) {
                if (immediateCommands.size() >= 10) break;
                try {
                    String cmd = el.getAsString().trim();
                    if (!cmd.isBlank()) {
                        immediateCommands.add(cmd);
                    }
                } catch (Exception e) {
                    // Malformed entry; skip it and keep going.
                }
            }
        }

        return new BuildPlan(result, chests, commandBlocks, mobs, itemRain, playerActions,
                weather, time, immediateCommands, skipped);
    }

    private static void addEquipment(JsonObject obj, String field, EquipmentSlot slot,
                                      Map<EquipmentSlot, Item> equipment, List<String> skipped) {
        String itemId = getString(obj, field);
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        Item item = resolveItem(itemId, skipped);
        if (item != null) {
            equipment.put(slot, item);
        }
    }

    private static EntityType<?> resolveEntityType(String entityId, List<String> skipped) {
        Identifier id = Identifier.tryParse(entityId.trim());
        if (id == null) {
            skipped.add(entityId);
            return null;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            skipped.add(entityId);
            return null;
        }

        return type;
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

    /**
     * Command blocks (impulse/chain/repeating) declare their own separate
     * FACING property (CommandBlock.FACING, a 6-way property) rather than
     * reusing HorizontalDirectionalBlock's 4-way one, so they need their own
     * setter. This direction is what makes a "chain" command block actually
     * propagate execution to the next block in line.
     */
    public static BlockState withCommandFacing(BlockState state, Direction facing) {
        if (facing != null && state.hasProperty(CommandBlock.FACING)) {
            return state.setValue(CommandBlock.FACING, facing);
        }
        return state;
    }

    /** The real block id for a command block "type" as the AI may specify it. */
    public static String commandBlockId(String type) {
        return switch (type) {
            case "chain" -> "minecraft:chain_command_block";
            case "repeat" -> "minecraft:repeating_command_block";
            default -> "minecraft:command_block";
        };
    }

    private static String normalizeCommandBlockType(String type) {
        if (type == null) {
            return "impulse";
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "chain" -> "chain";
            case "repeat", "repeating" -> "repeat";
            default -> "impulse";
        };
    }

    /**
     * Splits one raw "command" string from the AI into one or more real
     * single commands. Normally this is just one command (the common case);
     * this only kicks in when the model ignored the "one command per entry"
     * instruction and joined several together with a newline or semicolon.
     * Each returned command has any leading "/" stripped and is never blank.
     */
    private static List<String> splitIntoSingleCommands(String rawCommand) {
        List<String> parts = new ArrayList<>();
        if (rawCommand == null) {
            return parts;
        }
        for (String piece : rawCommand.split("[\\n;]+")) {
            String trimmed = piece.trim();
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
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
