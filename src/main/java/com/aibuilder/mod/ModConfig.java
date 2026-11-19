package com.aibuilder.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global settings for the mod, stored as config/aibuilder/settings.json.
 * These are NOT per-player (the API key is handled separately, per player,
 * by {@link PlayerKeyStore}) — this file only controls limits and defaults.
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configDir;
    private static Path configFile;

    // Defaults. Overwritten by load() if a settings.json already exists.
    public static String model = "gpt-4o-mini";
    public static String apiBaseUrl = "https://api.openai.com/v1/chat/completions";
    public static int maxBuildDimension = 24;   // max width/depth/height of a build, in blocks
    public static int maxBlocksPerBuild = 3000;  // hard safety cap on blocks per /aibuild call
    public static int blocksPerTick = 150;       // how many blocks get placed per server tick

    private ModConfig() {
    }

    /** Plain data holder used only for (de)serialization. */
    private static class Data {
        String model;
        String apiBaseUrl;
        int maxBuildDimension;
        int maxBlocksPerBuild;
        int blocksPerTick;
    }

    public static void load() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve(AiBuilderMod.MOD_ID);
        configFile = configDir.resolve("settings.json");
        try {
            Files.createDirectories(configDir);
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile, StandardCharsets.UTF_8);
                Data data = GSON.fromJson(json, Data.class);
                if (data != null) {
                    if (data.model != null && !data.model.isBlank()) model = data.model;
                    if (data.apiBaseUrl != null && !data.apiBaseUrl.isBlank()) apiBaseUrl = data.apiBaseUrl;
                    if (data.maxBuildDimension > 0) maxBuildDimension = data.maxBuildDimension;
                    if (data.maxBlocksPerBuild > 0) maxBlocksPerBuild = data.maxBlocksPerBuild;
                    if (data.blocksPerTick > 0) blocksPerTick = data.blocksPerTick;
                }
            } else {
                save();
            }
        } catch (IOException e) {
            AiBuilderMod.LOGGER.error("Failed to load settings.json, using defaults", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(configDir);
            Data data = new Data();
            data.model = model;
            data.apiBaseUrl = apiBaseUrl;
            data.maxBuildDimension = maxBuildDimension;
            data.maxBlocksPerBuild = maxBlocksPerBuild;
            data.blocksPerTick = blocksPerTick;
            Files.writeString(configFile, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiBuilderMod.LOGGER.error("Failed to save settings.json", e);
        }
    }
}
