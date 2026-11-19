package com.aibuilder.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores each player's own OpenAI API key, keyed by their UUID, in
 * config/aibuilder/keys.json. Every player who wants to use /aibuild sets
 * their own key, so usage is billed to them individually — nobody is
 * spending anyone else's OpenAI credit.
 *
 * IMPORTANT: keys are stored in PLAIN TEXT on disk. This is fine for a
 * personal/singleplayer world, but if you run a shared server, treat the
 * config folder (and your server backups) as sensitive, and tell players
 * not to reuse a key they care about if others have file access to the server.
 */
public final class PlayerKeyStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<HashMap<String, String>>() {
    }.getType();

    private static Path file;
    private static final Map<String, String> keys = new ConcurrentHashMap<>();

    private PlayerKeyStore() {
    }

    public static void load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(AiBuilderMod.MOD_ID);
        file = dir.resolve("keys.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, String> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    keys.putAll(loaded);
                }
            }
        } catch (IOException e) {
            AiBuilderMod.LOGGER.error("Failed to load keys.json", e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file, GSON.toJson(keys), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiBuilderMod.LOGGER.error("Failed to save keys.json", e);
        }
    }

    public static boolean hasKey(UUID playerId) {
        return keys.containsKey(playerId.toString());
    }

    public static String getKey(UUID playerId) {
        return keys.get(playerId.toString());
    }

    public static void setKey(UUID playerId, String key) {
        keys.put(playerId.toString(), key);
        save();
    }

    public static void clearKey(UUID playerId) {
        keys.remove(playerId.toString());
        save();
    }
}
