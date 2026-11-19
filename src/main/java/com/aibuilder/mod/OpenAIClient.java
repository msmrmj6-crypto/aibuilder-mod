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
        String systemPrompt = """
                You are a Minecraft Java Edition building planner.
                Reply with ONLY a single JSON object, no markdown fences, no commentary.
                Schema: {"blocks": [{"x": int, "y": int, "z": int, "block": "minecraft:<id>"}]}
                Coordinates are relative to the player's feet: x=0,y=0,z=0 is the block the player stands on.
                +y is up. Keep every coordinate within x,z in [-%d, %d] and y in [0, %d].
                Build the structure in front of the player, who is facing %s, so it does not overlap the player.
                Use at most %d blocks total, and only real Minecraft Java Edition block ids (with the "minecraft:" namespace).
                Do not include air blocks. Do not explain anything, output JSON only.
                """.formatted(maxDimension / 2, maxDimension / 2, maxDimension, facing, maxBlocks);

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
