package com.bitworld;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BitWorldConfigTest {

    @TempDir
    Path runDir;

    @Test
    void createsDefaultConfigWhenMissing() throws IOException {
        BitWorldConfig cfg = BitWorldConfig.load(runDir);
        assertEquals(BitWorldConfig.DEFAULT_MIRROR_DIR, cfg.configuredPath());
        assertTrue(cfg.usedFallback(), "le chemin Windows n'existe pas sous Linux -> repli");
        assertTrue(Files.isDirectory(cfg.mirrorDir()), "le dossier de repli est créé");
        assertTrue(cfg.mirrorDir().toString().endsWith("experiment"));
        // le fichier config/bitworld.properties a été écrit avec les valeurs par défaut
        assertTrue(Files.exists(runDir.resolve("config/bitworld.properties")));
    }

    @Test
    void usesConfiguredDirWhenItExists() throws IOException {
        Path realDir = runDir.resolve("miroir-reel");
        Files.createDirectories(realDir);
        Properties props = new Properties();
        props.setProperty("mirror.dir", realDir.toAbsolutePath().toString());
        try (var out = Files.newOutputStream(runDir.resolve("config.properties.tmp"))) {
            props.store(out, "");
        }
        // écrit directement config/bitworld.properties
        Files.createDirectories(runDir.resolve("config"));
        try (var out = Files.newOutputStream(runDir.resolve("config/bitworld.properties"))) {
            props.store(out, "");
        }
        BitWorldConfig cfg = BitWorldConfig.load(runDir);
        assertFalse(cfg.usedFallback());
        assertEquals(realDir.toAbsolutePath().normalize(), cfg.mirrorDir());
    }

    @Test
    void fallbackDirectoryIsReusable() throws IOException {
        BitWorldConfig first = BitWorldConfig.load(runDir);
        // on dépose un fichier dans le repli comme le ferait l'utilisateur
        Files.write(first.mirrorDir().resolve("f.bin"), new byte[]{(byte) 0xFF});
        BitWorldConfig second = BitWorldConfig.load(runDir);
        assertEquals(first.mirrorDir(), second.mirrorDir());
        try (BitStore store = new BitStore(second.mirrorDir())) {
            assertEquals(8, store.totalBits());
        }
    }
}
