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
        int half = Math.max(1, maxDimension / 2);

        String systemPrompt = """
                You are a careful Minecraft Java Edition building architect and planner.
                Reply with ONLY a single JSON object - no markdown fences, no commentary, no explanation text before or after it.

                COORDINATE SYSTEM: all coordinates are relative to the player's feet, where x=0,y=0,z=0 is the block the player stands on.
                +y = up. +x = east, -x = west. +z = south, -z = north.
                The player is currently facing %s. Build the whole structure in the direction the player is facing, so it never overlaps the player, and put the entrance/front side of the structure on the side closest to the player.
                Keep every coordinate within x in [-%d, %d], z in [-%d, %d], and y in [0, %d].

                Use up to three arrays together to describe the build:
                {
                  "fills": [{"x1":int,"y1":int,"z1":int,"x2":int,"y2":int,"z2":int,"block":"minecraft:<id>"}],
                  "hollowBoxes": [{"x1":int,"y1":int,"z1":int,"x2":int,"y2":int,"z2":int,"block":"minecraft:<id>"}],
                  "blocks": [{"x":int,"y":int,"z":int,"block":"minecraft:<id>"}]
                }
                - "fills": a solid rectangular box of one block type (both corners included). Use for floors, roof slabs, and roof layers.
                - "hollowBoxes": a rectangular box whose outer shell only (floor + ceiling + all 4 walls) is solid; the interior stays empty. This is the fastest and most reliable way to build a straight-walled room or building body - always prefer it over listing wall blocks one by one.
                - "blocks": single blocks, applied last (after fills/hollowBoxes), for door and window openings (set to "minecraft:air", or "minecraft:glass_pane" for a window, "minecraft:oak_door" for a door) and small details like torches. Never use "blocks" to enumerate an entire wall or floor.

                TYPICAL SMALL HOUSE RECIPE (adapt size, material, and style to what was asked):
                1. One "hollowBoxes" entry for the whole body (floor to wall-top) - this alone gives you a floor, ceiling, and 4 straight walls.
                2. "blocks" entries cutting a door opening (2 blocks tall) in the wall nearest the player.
                3. A few "blocks" entries cutting window openings in the other walls.
                4. One or more "fills" entries just above the walls for the roof: either one flat slab the size of the footprint, or several layers, each one block smaller on every side and one level higher, for a simple pyramid/stepped roof.
                Keep box sizes reasonable for what was asked - fills and hollowBoxes are expanded into individual blocks automatically on our side, so a sensible plan stays well under %d placed blocks without you needing to count them.

                EXAMPLE - a small house, player facing south, so the house sits in front toward +z and the door faces back toward the player (north side of the house):
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
                  ]
                }

                Only use real Minecraft Java Edition block ids with the "minecraft:" namespace. Output JSON only.
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
