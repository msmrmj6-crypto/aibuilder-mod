package com.aibuilder.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw text the AI replied with into a validated, bounded list of
 * block placements. Nothing here is executed in the world yet — this is
 * just parsing + sanity-checking.
 */
public final class BuildPlanParser {

    private BuildPlanParser() {
    }

    /** A single block to place, in coordinates relative to the player. */
    public record PlacedBlock(int x, int y, int z, BlockState state) {
    }

    /** blocks = what we will place. skipped = block ids the AI gave that we could not resolve. */
    public record BuildPlan(List<PlacedBlock> blocks, List<String> skipped) {
    }

    public static BuildPlan parse(String rawJson, int maxDimension, int maxBlocks) {
        List<PlacedBlock> result = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        JsonObject root;
        try {
            root = JsonParser.parseString(extractJson(rawJson)).getAsJsonObject();
        } catch (Exception e) {
            return new BuildPlan(result, skipped);
        }

        if (!root.has("blocks") || !root.get("blocks").isJsonArray()) {
            return new BuildPlan(result, skipped);
        }

        JsonArray arr = root.getAsJsonArray("blocks");
        int half = Math.max(1, maxDimension / 2);

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

                Identifier id = Identifier.tryParse(blockId.trim());
                if (id == null) {
                    skipped.add(blockId);
                    continue;
                }

                Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                if (block == null) {
                    skipped.add(blockId);
                    continue;
                }

                result.add(new PlacedBlock(x, y, z, block.defaultBlockState()));
            } catch (Exception e) {
                // Malformed entry from the model; skip it and keep going.
            }
        }

        return new BuildPlan(result, skipped);
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
