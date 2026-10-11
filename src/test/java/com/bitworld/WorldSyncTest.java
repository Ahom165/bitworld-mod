package com.bitworld;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldSyncTest {

    @TempDir
    Path dir;

    private BitStore storeWith(byte[] content) throws IOException {
        Files.write(dir.resolve("a.bin"), content);
        BitStore store = new BitStore(dir);
        BitLayout.configure(store.totalBits());
        return store;
    }

    @Test
    void computeCorrectionsFindsDestroyedBits() throws IOException {
        // octet 0b1000_0101 : bits 0, 2 et 7 à 1 ; la TNT a détruit les blocs des bits 0 et 7
        try (BitStore store = storeWith(new byte[]{(byte) 0b1000_0101})) {
            List<Long> corrections = WorldSync.computeCorrections(store,
                    (x, y, z) -> y == BitLayout.BASE_Y && z == 0 && (x == 0 || x == 7));
            assertEquals(List.of(0L, 7L), corrections);
        }
    }

    @Test
    void computeCorrectionsIgnoresIntactBits() throws IOException {
        // aucun bloc détruit : aucune correction
        try (BitStore store = storeWith(new byte[]{(byte) 0b1111_1111})) {
            List<Long> corrections = WorldSync.computeCorrections(store, (x, y, z) -> false);
            assertTrue(corrections.isEmpty());
        }
    }

    @Test
    void computeCorrectionsIgnoresAirWhereBitIsZero() throws IOException {
        // des trous là où le bit vaut déjà 0 : conforme, rien à faire
        try (BitStore store = storeWith(new byte[]{(byte) 0b0000_0001})) {
            List<Long> corrections = WorldSync.computeCorrections(store,
                    (x, y, z) -> x != 0 || y != BitLayout.BASE_Y || z != 0);
            assertTrue(corrections.isEmpty());
        }
    }

    @Test
    void clearingCorrectionsWritesFilesPhysically() throws IOException {
        Files.write(dir.resolve("a.bin"), new byte[]{(byte) 0b1111_1111});
        try (BitStore store = new BitStore(dir)) {
            BitLayout.configure(store.totalBits());
            List<Long> corrections = WorldSync.computeCorrections(store,
                    (x, y, z) -> y == BitLayout.BASE_Y && (x == 3 || x == 5) && z == 0);
            for (long bit : corrections) {
                store.clearBit(bit);
            }
            // relecture FRAÎCHE depuis le disque : l'écriture doit être physiquement là
            byte[] onDisk = Files.readAllBytes(dir.resolve("a.bin"));
            assertEquals((byte) 0b1101_0111, onDisk[0]);
            assertEquals(6, store.entries().get(0).popcount());
        }
    }

    @Test
    void backupPreservedBeforeResync() throws IOException {
        Files.write(dir.resolve("a.bin"), new byte[]{(byte) 0b0000_1000});
        try (BitStore store = new BitStore(dir)) {
            BitLayout.configure(store.totalBits());
            WorldSync.computeCorrections(store, (x, y, z) -> true); // tout est "air"
            for (long i = 0; i < store.totalBits(); i++) {
                store.clearBit(i);
            }
            Path bak = dir.resolve("a.bin.bak");
            assertTrue(Files.exists(bak));
            assertArrayEquals(new byte[]{(byte) 0b0000_1000}, Files.readAllBytes(bak));
            assertEquals(0, Files.readAllBytes(dir.resolve("a.bin"))[0]);
        }
    }

    @Test
    void correctionsEmptyForEmptyStore() throws IOException {
        Files.write(dir.resolve("a.bin"), new byte[0]);
        try (BitStore store = new BitStore(dir)) {
            BitLayout.configure(store.totalBits());
            assertTrue(WorldSync.computeCorrections(store, (x, y, z) -> true).isEmpty());
        }
    }

    // ===== voie d'écriture (pose de blocs -> bits à 1) =====

    @Test
    void computeWritesFindsPlacedPaletteBlocks() throws IOException {
        // fichier 0x00 : le joueur pose de la glowstone aux bits 0, 2 et 7
        try (BitStore store = storeWith(new byte[]{(byte) 0x00})) {
            List<Long> writes = WorldSync.computeWrites(store,
                    (x, y, z) -> y == BitLayout.BASE_Y && z == 0 && (x == 0 || x == 2 || x == 7));
            assertEquals(List.of(0L, 2L, 7L), writes);
            for (long bit : writes) {
                store.setBit(bit);
            }
            // relecture FRAÎCHE depuis le disque : l'écriture doit être physiquement là
            byte[] onDisk = Files.readAllBytes(dir.resolve("a.bin"));
            assertEquals((byte) 0b1000_0101, onDisk[0]);
        }
    }

    @Test
    void computeWritesIgnoresBitsAlreadyOne() throws IOException {
        // tous les bits déjà à 1 : conforme, rien à écrire
        try (BitStore store = storeWith(new byte[]{(byte) 0b1111_1111})) {
            List<Long> writes = WorldSync.computeWrites(store, (x, y, z) -> true);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    void computeWritesIgnoresPositionsWithoutPalette() throws IOException {
        // des positions vides de blocs palette (terre cassée, TNT posée...) : rien à écrire
        try (BitStore store = storeWith(new byte[]{(byte) 0x00})) {
            List<Long> writes = WorldSync.computeWrites(store, (x, y, z) -> false);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    void placementWritesFilesPhysicallyWithBackup() throws IOException {
        Files.write(dir.resolve("a.bin"), new byte[]{(byte) 0x00});
        try (BitStore store = new BitStore(dir)) {
            BitLayout.configure(store.totalBits());
            List<Long> writes = WorldSync.computeWrites(store,
                    (x, y, z) -> y == BitLayout.BASE_Y && x == 3 && z == 0);
            assertEquals(List.of(3L), writes);
            store.setBit(3);
            byte[] onDisk = Files.readAllBytes(dir.resolve("a.bin"));
            assertEquals((byte) 0b0000_1000, onDisk[0]);
            assertEquals(1, store.entries().get(0).popcount());
            // comme pour l'effacement, le .bak de l'original doit exister
            assertTrue(Files.exists(dir.resolve("a.bin.bak")));
            assertArrayEquals(new byte[]{(byte) 0x00}, Files.readAllBytes(dir.resolve("a.bin.bak")));
        }
    }

    @Test
    void writesAndCorrectionsAreSymmetric() throws IOException {
        // propriété clé : tout bit écrit par une pose peut être effacé par la
        // destruction — les deux passes sont des inverses exacts
        byte start = (byte) 0b0101_0010; // bits 1, 4, 6 déjà à 1
        try (BitStore store = storeWith(new byte[]{start})) {
            // pose de palette sur les positions des bits 0, 3 et 7 (actuellement à 0)
            List<Long> writes = WorldSync.computeWrites(store,
                    (x, y, z) -> y == BitLayout.BASE_Y && z == 0 && (x == 0 || x == 3 || x == 7));
            assertEquals(List.of(0L, 3L, 7L), writes);
            for (long bit : writes) {
                store.setBit(bit);
            }
            // maintenant tout est détruit : les corrections retrouvent TOUS les bits à 1
            List<Long> corrections = WorldSync.computeCorrections(store, (x, y, z) -> true);
            assertEquals(List.of(0L, 1L, 3L, 4L, 6L, 7L), corrections);
            for (long bit : corrections) {
                store.clearBit(bit);
            }
            assertEquals(0, Files.readAllBytes(dir.resolve("a.bin"))[0]);
        }
    }

    @Test
    void fullExplosionScenario() throws IOException {
        // simulation bout-en-bout : 4 octets (couches BASE_Y..BASE_Y+3),
        // la TNT souffle tout SAUF la couche BASE_Y+3
        Files.write(dir.resolve("a.bin"), new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        try (BitStore store = new BitStore(dir)) {
            BitLayout.configure(store.totalBits());
            List<Long> corrections = WorldSync.computeCorrections(store,
                    (x, y, z) -> y != BitLayout.BASE_Y + 3);
            assertEquals(24, corrections.size()); // 3 couches de 8 bits détruites sur 32
            for (long bit : corrections) {
                store.clearBit(bit);
            }
            byte[] onDisk = Files.readAllBytes(dir.resolve("a.bin"));
            assertEquals((byte) 0x00, onDisk[0]);
            assertEquals((byte) 0x00, onDisk[1]);
            assertEquals((byte) 0x00, onDisk[2]);
            assertEquals((byte) 0xFF, onDisk[3]); // la couche intacte reste à 1
        }
    }
}
