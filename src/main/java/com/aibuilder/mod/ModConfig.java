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

    // Bumped whenever the shipped defaults below change, so load() can tell
    // a server that is still on an older release's defaults (and upgrade it -
    // see the migration in load()) apart from one where an admin deliberately
    // chose a different number.
    private static final int CURRENT_CONFIG_VERSION = 2;

    // The defaults every earlier release of this mod shipped with (and saved
    // to disk on first run) — used only to recognize "never customized" below.
    private static final int LEGACY_MAX_BUILD_DIMENSION = 24;
    private static final int LEGACY_MAX_BLOCKS_PER_BUILD = 3000;
    private static final int LEGACY_BLOCKS_PER_TICK = 150;

    // Defaults. Overwritten by load() if a settings.json already exists.
    public static String model = "gpt-4o-mini";
    public static String apiBaseUrl = "https://api.openai.com/v1/chat/completions";
    public static int maxBuildDimension = 40;    // max width/depth/height of a build, in blocks
    public static int maxBlocksPerBuild = 8000;  // hard safety cap on blocks per /aibuild call
    public static int blocksPerTick = 300;       // how many blocks get placed per server tick

    private ModConfig() {
    }

    /** Plain data holder used only for (de)serialization. */
    private static class Data {
        String model;
        String apiBaseUrl;
        int maxBuildDimension;
        int maxBlocksPerBuild;
        int blocksPerTick;
        int configVersion;
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

                    // One-time migration: this mod's players generally have no way
                    // to edit settings.json by hand, so a value still sitting at
                    // exactly what an older release both defaulted to AND saved on
                    // first run almost certainly means "never customized" rather
                    // than "deliberately chosen" — bump just those untouched
                    // values up to the new, more capable defaults.
                    if (data.configVersion < CURRENT_CONFIG_VERSION) {
                        if (maxBuildDimension == LEGACY_MAX_BUILD_DIMENSION) {
                            maxBuildDimension = 40;
                        }
                        if (maxBlocksPerBuild == LEGACY_MAX_BLOCKS_PER_BUILD) {
                            maxBlocksPerBuild = 8000;
                        }
                        if (blocksPerTick == LEGACY_BLOCKS_PER_TICK) {
                            blocksPerTick = 300;
                        }
                        save();
                    }
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
            data.configVersion = CURRENT_CONFIG_VERSION;
            Files.writeString(configFile, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiBuilderMod.LOGGER.error("Failed to save settings.json", e);
        }
    }
}
