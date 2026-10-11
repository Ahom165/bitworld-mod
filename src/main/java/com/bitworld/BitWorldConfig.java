package com.bitworld;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration du mod : chemin du dossier miroir.
 * Fichier : config/bitworld.properties (créé avec les valeurs par défaut au premier lancement).
 */
public final class BitWorldConfig {

    /** Chemin par défaut : le dossier expérimental de l'utilisateur (machine Windows). */
    public static final String DEFAULT_MIRROR_DIR = "c:/users/Ahomine_/experiment";
    /** Dossier de secours relatif si le chemin configuré n'existe pas sur cette machine. */
    public static final String FALLBACK_MIRROR_DIR = "experiment";

    private final Path mirrorDir;
    private final String configuredPath;
    private final boolean usedFallback;

    private BitWorldConfig(Path mirrorDir, String configuredPath, boolean usedFallback) {
        this.mirrorDir = mirrorDir;
        this.configuredPath = configuredPath;
        this.usedFallback = usedFallback;
    }

    /** Charge la configuration depuis <runDir>/config/bitworld.properties. */
    public static BitWorldConfig load(Path runDir) throws IOException {
        Path configDir = runDir.resolve("config");
        Path configFile = configDir.resolve("bitworld.properties");
        Properties props = new Properties();

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            }
        } else {
            Files.createDirectories(configDir);
            props.setProperty("mirror.dir", DEFAULT_MIRROR_DIR);
            try (OutputStream out = Files.newOutputStream(configFile)) {
                props.store(out, "BitWorld - dossier miroir des fichiers binaires");
            }
        }

        String configured = props.getProperty("mirror.dir", DEFAULT_MIRROR_DIR).trim();
        Path configuredPath = Path.of(configured);

        if (Files.isDirectory(configuredPath)) {
            return new BitWorldConfig(configuredPath, configured, false);
        }

        // Repli : le chemin configuré n'existe pas sur cette machine (ex: chemin Windows sur Linux)
        Path fallback = runDir.resolve(FALLBACK_MIRROR_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(fallback)) {
            Files.createDirectories(fallback);
        }
        return new BitWorldConfig(fallback, configured, true);
    }

    public Path mirrorDir() {
        return mirrorDir;
    }

    public String configuredPath() {
        return configuredPath;
    }

    public boolean usedFallback() {
        return usedFallback;
    }
}
