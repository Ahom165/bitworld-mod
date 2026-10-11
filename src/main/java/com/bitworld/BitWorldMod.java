package com.bitworld;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Point d'entrée du mod BitWorld : le monde Minecraft est le miroir physique
 * des fichiers d'un dossier réel. Chaque bit de fichier est un bloc ; casser
 * un bloc efface le bit (écriture physique), la TNT aussi.
 */
public final class BitWorldMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("BitWorld");
    public static final String MOD_ID = "bitworld";

    private static BitWorldConfig config;
    private static BitStore store;
    private static WorldSync sync;

    @Override
    public void onInitialize() {
        // Enregistrement du type de générateur (utilisé par le datapack de dimension)
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
                Identifier.fromNamespaceAndPath(MOD_ID, "bit"),
                BitWorldChunkGenerator.CODEC);

        try {
            Path runDir = FabricLoader.getInstance().getGameDir();
            config = BitWorldConfig.load(runDir);
            LOGGER.info("[BitWorld] Mod initialisé — dossier miroir configuré : {}",
                    config.configuredPath());
            if (config.usedFallback()) {
                LOGGER.warn("[BitWorld] chemin introuvable sur cette machine, repli sur : {}",
                        config.mirrorDir());
            }

            store = new BitStore(config.mirrorDir());
            BitLayout.configure(store.totalBits());
            LOGGER.info("[BitWorld] {} fichier(s) chargé(s), {} bits au total",
                    store.fileCount(), store.totalBits());

            sync = WorldSync.register(store);
            BitWorldCommands.register();
        } catch (IOException e) {
            LOGGER.error("[BitWorld] échec d'initialisation du miroir", e);
        }
    }

    public static BitStore store() {
        return store;
    }

    public static BitWorldConfig config() {
        return config;
    }

    public static WorldSync sync() {
        return sync;
    }
}
