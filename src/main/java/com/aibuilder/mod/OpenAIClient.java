package com.aibuilder.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to an OpenAI-compatible Chat Completions endpoint (defaults to
 * OpenAI itself; see ModConfig.apiBaseUrl to point this at a compatible
 * local/alternative server instead).
 *
 * Only the JDK's built-in java.net.http client is used here, so no extra
 * Gradle dependency is required for networking.
 */
public final class OpenAIClient {

    private static final Gson GSON = new Gson();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private OpenAIClient() {
    }

    /**
     * Returns a future that completes with the raw text the model replied
     * with (expected to be a JSON object — see BuildPlanParser), or fails
     * with an exception describing what went wrong.
     */
    public static CompletableFuture<String> requestBuildPlan(String apiKey, String model, String description,
                                                               String facing, int maxDimension, int maxBlocks) {
        try {
            return doRequest(apiKey, model, description, facing, maxDimension, maxBlocks);
        } catch (Exception e) {
            // Building the request itself can throw synchronously (e.g. an API key
            // or URL with an invalid/invisible character makes the HTTP header
            // builder reject it). That must never crash the /aibuild command outright
            // - turn it into a normal failed future so the player gets our own clear
            // "[AI Builder] صار خطأ: ..." message instead of Minecraft's raw,
            // unhelpful "An unexpected error occurred" and a permanently-stuck
            // "busy" state.
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletableFuture<String> doRequest(String apiKey, String model, String description,
                                                         String facing, int maxDimension, int maxBlocks) {
        int half = Math.max(1, maxDimension / 2);

        String systemPrompt = """
                You are a careful Minecraft Java Edition building architect and planner.
                Reply with ONLY a single JSON object - no markdown fences, no commentary, no explanation text before or after it.

                COORDINATE SYSTEM: all coordinates are relative to the player's feet, where x=0,y=0,z=0 is the block the player stands on.
                +y = up. +x = east, -x = west. +z = south, -z = north.
                The player is currently facing %s. Build the whole structure in the direction the player is facing, so it never overlaps the player, and put the entrance/front side of the structure on the side closest to the player.
                Keep every coordinate within x in [-%d, %d], z in [-%d, %d], and y in [0, %d].

                Use up to four arrays together to describe the build:
                {
                  "fills": [{"x1":int,"y1":int,"z1":int,"x2":int,"y2":int,"z2":int,"block":"minecraft:<id>"}],
                  "hollowBoxes": [{"x1":int,"y1":int,"z1":int,"x2":int,"y2":int,"z2":int,"block":"minecraft:<id>"}],
                  "blocks": [{"x":int,"y":int,"z":int,"block":"minecraft:<id>"}],
                  "chests": [{"x":int,"y":int,"z":int,"items":[{"item":"minecraft:<id>","count":int}]}]
                }
                - "fills": a solid rectangular box of one block type (both corners included). Use for floors, roof slabs, roof layers, and (with "facing") a whole row of oriented stairs for a real sloped roof.
                - "hollowBoxes": a rectangular box whose outer shell only (floor + ceiling + all 4 walls) is solid; the interior stays empty. This is the fastest and most reliable way to build a straight-walled room or building body - always prefer it over listing wall blocks one by one.
                - "blocks": single blocks, applied last (after fills/hollowBoxes), for door and window openings (set to "minecraft:air", or "minecraft:glass_pane" for a window, "minecraft:oak_door" for a door) and small details like torches. Never use "blocks" to enumerate an entire wall or floor.
                - "chests": places a "minecraft:chest" at that position and fills it with the given items, one per slot, in order (a chest has 27 slots - list at most 27). Use this whenever the request asks for a chest/container holding something, or for a specific item (a sword, a door, food, tools, anything) to be stored/placed somewhere rather than built as a block in the structure. "count" is optional (defaults to 1, max 64). Item ids use the same "minecraft:" namespace as blocks, but are the ITEM form (e.g. "minecraft:diamond_sword", "minecraft:oak_door").

                ORIENTATION: any "fills", "blocks", or "chests" entry may also set "facing": "north"|"south"|"east"|"west". It is applied to that block's horizontal orientation when it has one (stairs, doors, trapdoors, furnaces, chests, fence gates, ...) and ignored otherwise. For stairs, "facing" is the side the tall/flat vertical face points to - the sloped top faces the opposite way, so the open/low side of the slope points opposite to "facing". Use this to build a REAL sloped roof: along each roof edge, one "fills" row/box of e.g. "minecraft:oak_stairs" with "facing" pointing away from the ridge (so the slope climbs toward the ridge) looks far more realistic than stacking flat shrinking layers. Use "facing" on a door so it sits flush in its opening, and on a chest so it opens toward the room.

                TYPICAL SMALL HOUSE RECIPE (adapt size, material, and style to what was asked):
                1. One "hollowBoxes" entry for the whole body (floor to wall-top) - this alone gives you a floor, ceiling, and 4 straight walls.
                2. "blocks" entries cutting a door opening (2 blocks tall) in the wall nearest the player.
                3. A few "blocks" entries cutting window openings in the other walls.
                4. A roof on top of the walls: prefer real sloped stairs (see ORIENTATION above) - one "fills" row per roof edge using "<wood>_stairs" with the right "facing", plus a flat ridge cap row along the very top. A flat slab, or a few shrinking layers for a simple pyramid, are acceptable simpler alternatives.
                5. If asked for a chest/item inside, add one "chests" entry inside the house with the requested items.
                Keep box sizes reasonable for what was asked - fills and hollowBoxes are expanded into individual blocks automatically on our side, so a sensible plan stays well under %d placed blocks without you needing to count them.

                EXAMPLE - a small house with a chest holding a sword, player facing south, so the house sits in front toward +z and the door faces back toward the player (north side of the house):
                {
                  "hollowBoxes": [
                    {"x1":-3,"y1":0,"z1":2,"x2":3,"y2":3,"z2":8,"block":"minecraft:oak_planks"}
                  ],
                  "fills": [
                    {"x1":-4,"y1":4,"z1":1,"x2":4,"y2":4,"z2":9,"block":"minecraft:oak_slab"}
                  ],
                  "blocks": [
                    {"x":0,"y":1,"z":2,"block":"minecraft:air"},
                    {"x":0,"y":2,"z":2,"block":"minecraft:air"},
                    {"x":-2,"y":2,"z":2,"block":"minecraft:glass_pane"},
                    {"x":2,"y":2,"z":2,"block":"minecraft:glass_pane"}
                  ],
                  "chests": [
                    {"x":2,"y":1,"z":7,"items":[{"item":"minecraft:diamond_sword","count":1}]}
                  ]
                }

                Only use real Minecraft Java Edition block/item ids with the "minecraft:" namespace. Output JSON only.
                """.formatted(facing, half, half, half, half, maxDimension, maxBlocks);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.4);

        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Build: " + description);
        messages.add(user);

        body.add("messages", messages);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ModConfig.apiBaseUrl))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException("OpenAI HTTP " + response.statusCode() + ": " + response.body());
                    }
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return json.getAsJsonArray("choices")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("message")
                            .get("content").getAsString();
                });
    }
}
