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

                Use up to six arrays together to describe the build:
                {
                  "fills": [{"x1":int,"y1":int,"z1":int,"x2":int,"y2":int,"z2":int,"block":"minecraft:<id>"}],
                  "hollowBoxes": [{"x1":int,"y1":int,"z1":int,"x2":int,"y2":int,"z2":int,"block":"minecraft:<id>"}],
                  "blocks": [{"x":int,"y":int,"z":int,"block":"minecraft:<id>"}],
                  "chests": [{"x":int,"y":int,"z":int,"items":[{"item":"minecraft:<id>","count":int}]}],
                  "commandBlocks": [{"x":int,"y":int,"z":int,"command":"give @p minecraft:<id> 1","type":"impulse"|"chain","auto":bool}],
                  "mobs": [{"x":int,"y":int,"z":int,"entity":"minecraft:<id>","count":int,"mainhand":"minecraft:<id>","offhand":"minecraft:<id>","helmet":"minecraft:<id>","chestplate":"minecraft:<id>","leggings":"minecraft:<id>","boots":"minecraft:<id>"}]
                }
                - "fills": a solid rectangular box of one block type (both corners included). Use for floors, roof slabs, roof layers, and (with "facing") a whole row of oriented stairs for a real sloped roof.
                - "hollowBoxes": a rectangular box whose outer shell only (floor + ceiling + all 4 walls) is solid; the interior stays empty. This is the fastest and most reliable way to build a straight-walled room or building body - always prefer it over listing wall blocks one by one.
                - "blocks": single blocks, applied last (after fills/hollowBoxes), for door and window openings (set to "minecraft:air", or "minecraft:glass_pane" for a window, "minecraft:oak_door" for a door) and small details like torches, redstone dust/repeaters/levers/pistons, etc. Never use "blocks" to enumerate an entire wall or floor.
                - "chests": places a "minecraft:chest" at that position and fills it with the given items, one per slot, in order (a chest has 27 slots - list at most 27). ALWAYS include this whenever the request mentions a chest/container holding something, or any specific item (a sword, a door, food, tools, anything) that should be stored/placed somewhere rather than built into the structure - never skip or forget this when it was asked for. "count" is optional (defaults to 1, max 64). Item ids use the same "minecraft:" namespace as blocks, but are the ITEM form (e.g. "minecraft:diamond_sword", "minecraft:oak_door").
                - "commandBlocks": each entry stores EXACTLY ONE vanilla command (no leading "/") in ONE command block. A command block can only ever run a single command - NEVER write two commands into one "command" string, and NEVER join several with a newline or a semicolon, that breaks in-game instead of working. For a kit of SEVERAL items, use one commandBlocks entry PER item instead, built as a real chain so one button fires all of them: give every entry the SAME "facing", place each next entry exactly one block further in that same "facing" direction, leave "type" unset (defaults to "impulse" - the one the button actually powers) on the FIRST entry only, and set "type":"chain","auto":true on every entry after the first (an always-active chain block - it fires automatically right after the one behind it, no redstone of its own needed). Always also place a real button or lever (via "blocks", e.g. "minecraft:oak_button") next to/on top of the FIRST (impulse) entry only - a command block alone does nothing without redstone power, and only the impulse one needs it.
                - "mobs": spawns a real living mob at that position, optionally geared up with real equipment in "mainhand"/"offhand"/"helmet"/"chestplate"/"leggings"/"boots" (all optional - include only the slots you actually want filled). "count" (default 1, max 10) spawns that many copies at the same spot, e.g. a small group of guards. Use this for boss fights, guarded rooms/dungeons, parkour/puzzle enemies, or anything that asked for a monster/mob/guard/boss - this is the reliable way to get an equipped mob, so prefer it over trying to "summon" one with hand-written equipment NBT in a commandBlocks entry. Entity ids use the "minecraft:" namespace too (e.g. "minecraft:zombie", "minecraft:skeleton", "minecraft:piglin_brute", "minecraft:vindicator").

                ITEM ACCURACY: when the request names a specific item (a weapon, tool, armor piece, or anything else) use exactly that item's real id - never substitute a different, more "default" item out of habit (asked for a mace, give "minecraft:mace", NOT a sword). Common ids: sword/axe/pickaxe/shovel/hoe = "minecraft:<material>_<tool>" (material one of wooden/stone/golden/iron/diamond/netherite), bow = "minecraft:bow", crossbow = "minecraft:crossbow", trident = "minecraft:trident", mace = "minecraft:mace", shield = "minecraft:shield", totem = "minecraft:totem_of_undying", elytra = "minecraft:elytra". When a kit has several named items, every entry must give the one distinct item it actually corresponds to - never repeat the same single item across every entry unless duplicates were explicitly asked for.

                ORIENTATION: any "fills", "blocks", "chests", or "commandBlocks" entry may also set "facing": "north"|"south"|"east"|"west". It is applied to that block's horizontal orientation when it has one (stairs, doors, trapdoors, furnaces, chests, fence gates, ...) and ignored otherwise. For stairs, "facing" is the side the tall/flat vertical face points to - the sloped top faces the opposite way, so the open/low side of the slope points opposite to "facing". Use this to build a REAL sloped roof: along each roof edge, one "fills" row/box of e.g. "minecraft:oak_stairs" with "facing" pointing away from the ridge (so the slope climbs toward the ridge) looks far more realistic than stacking flat shrinking layers. Use "facing" on a door so it sits flush in its opening, and on a chest so it opens toward the room. For a "commandBlocks" chain, "facing" is the direction execution flows from each block to the next - every block in one chain must share the same "facing".

                COMMAND BLOCK IDEAS: a commandBlocks entry can hold ANY single valid vanilla command, not only "give" - pick whichever real command actually matches what was asked instead of forcing everything into "give":
                - "give @p minecraft:<item> <count>" - hand over an item/kit.
                - "tp @p <x> <y> <z>" or "tp @p <target selector>" - teleport the player (or to another entity).
                - "effect give @p minecraft:<effect> <seconds> <amplifier>" - a buff/heal/debuff (e.g. "effect give @p minecraft:speed 30 1").
                - "summon minecraft:<entity> ~ ~ ~" - spawn something that ISN'T a geared mob (a firework, a lightning bolt, a plain unarmed/vanilla-default mob); for an equipped mob, use the top-level "mobs" array instead (see below), it is far more reliable than hand-written equipment NBT in a command string.
                - "playsound minecraft:<sound> master @p" - play a sound cue.
                - a title/actionbar message on screen: command text is title @p title "Hello there!" or title @p actionbar "Hello there!" (a plain quoted string works fine as the message - you don't need a nested JSON object). The quotes shown here are part of the Minecraft command itself; escape them normally like any other quote when you write your JSON output.
                - "weather clear <seconds>" / "weather rain <seconds>" / "time set day" / "time set night" - change weather or time.
                - "particle minecraft:<effect> ~ ~ ~ 0.5 0.5 0.5 0 50" - a particle burst.
                - "setblock ~ ~ ~ minecraft:<id>" or a small "fill ~ ~ ~ ~5 ~ ~5 minecraft:<id>" - change blocks on the fly (e.g. a lever that floods a pit with lava, or clears an area).
                Combine several of these as a chain (see above) for one button doing multiple things at once (e.g. a kit + a title message + a sound).

                REDSTONE, FARMS & TRAPS: you are not limited to houses. For redstone contraptions, place the real components with "blocks"/"fills" ("minecraft:redstone_wire", "minecraft:repeater"/"minecraft:comparator" with "facing" set to the direction the signal flows, "minecraft:lever", "minecraft:oak_button", "minecraft:piston"/"minecraft:sticky_piston", "minecraft:redstone_lamp", ...) - wire stays powered/connected automatically once placed next to compatible neighbors. For a simple farm, build fenced/walled plots with farmland+water (crops) or a fenced pen (animals); for a mob trap/grinder, a dark collection chamber above a drop shaft with a hopper+chest at the bottom and water streams pushing mobs toward it is the standard pattern - reuse "hollowBoxes" for the shaft/chamber walls and "fills" for water/farmland layers.

                PARKOUR & PUZZLES & EVENTS: for a parkour course, place a sequence of small floating platforms ("blocks" entries, 1-3 blocks each) spaced out with gaps to jump across, gradually further from and/or higher than the player. For a puzzle room, a locked door (an iron door, or a piston holding a wall block in place) that only opens once specific levers/buttons are activated is the standard approach - keep the redstone logic simple (2-3 inputs feeding one piston/door, not an elaborate circuit). For a boss fight or guarded encounter, combine a "mobs" entry (geared up - see below) with the room/arena built around it. None of this needs a duration/timer in your JSON - that is a separate setting the admin controls outside of what you return, not something you add here.

                BE HONEST ABOUT WHAT'S POSSIBLE: some requests describe something outside what Minecraft Java Edition blocks/commands can actually do - most commonly "change the sky color" (the sky's real rendered color is not controllable by any block or vanilla command; that needs a resource pack or shader, which this cannot create). When a request asks for something like that, do NOT invent a fake command or a nonsensical block placement pretending to do it. Instead substitute the closest REAL effect that actually works and is clearly a reasonable stand-in (e.g. for "green sky", build an enclosing dome/room of green stained glass around the player instead, and/or use "particle"/fireworks for a colorful effect) - never claim to do the impossible thing itself.

                TYPICAL SMALL HOUSE RECIPE (adapt size, material, and style to what was asked):
                1. One "hollowBoxes" entry for the whole body (floor to wall-top) - this alone gives you a floor, ceiling, and 4 straight walls.
                2. "blocks" entries cutting a door opening (2 blocks tall) in the wall nearest the player.
                3. A few "blocks" entries cutting window openings in the other walls.
                4. A roof on top of the walls: prefer real sloped stairs (see ORIENTATION above) - one "fills" row per roof edge using "<wood>_stairs" with the right "facing", plus a flat ridge cap row along the very top. A flat slab, or a few shrinking layers for a simple pyramid, are acceptable simpler alternatives.
                5. If asked for a chest/item inside, add one "chests" entry inside the house with the requested items.
                Keep box sizes reasonable for what was asked - fills and hollowBoxes are expanded into individual blocks automatically on our side, so a sensible plan stays well under %d placed blocks without you needing to count them.

                BUILD BIGGER & RICHER WHEN ASKED: you are not limited to one small room. For a "castle", "mansion", "tower", "multi-story house", or anything described as big/fancy, stack multiple "hollowBoxes" entries (one per floor, each starting where the previous one's ceiling is) connected by an internal staircase or ladder ("blocks" entries), add crenellations/battlements on a castle's roofline (alternating stone-brick blocks along the top edge), and divide the inside into rooms with a thin internal wall. Furnish the inside instead of leaving it bare: bookshelves, a crafting table/furnace/smithing table, carpets, lanterns or glowstone set into the ceiling for light, item frames holding a tool on the wall, flower pots, banners. Outside, vary materials for realism (e.g. a cobblestone/stone-brick foundation under a wood upper floor, stair/slab trims along edges instead of perfectly flat corners) and add a small fenced yard or path leading to the door. Scale the whole plan (room count, floor count, footprint) to match how big the request actually sounds, within the coordinate bounds above.

                BEFORE YOU ANSWER: re-read the request and confirm every specific thing it named (a chest and what's in it, a particular item, a button/command, a redstone device, a window, a color/material, ...) actually appears somewhere in your JSON. Do not silently drop part of the request.

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

                EXAMPLE - a button that gives a kit of a mace, a shield, and 32 arrows (3 distinct items -> 3 chained command blocks, only the first is an impulse block, only it gets the button):
                {
                  "commandBlocks": [
                    {"x":1,"y":1,"z":1,"facing":"east","command":"give @p minecraft:mace 1"},
                    {"x":2,"y":1,"z":1,"facing":"east","type":"chain","auto":true,"command":"give @p minecraft:shield 1"},
                    {"x":3,"y":1,"z":1,"facing":"east","type":"chain","auto":true,"command":"give @p minecraft:arrow 32"}
                  ],
                  "blocks": [
                    {"x":1,"y":2,"z":1,"block":"minecraft:oak_button"}
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
