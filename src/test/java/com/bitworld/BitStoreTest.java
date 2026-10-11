package com.bitworld;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BitStoreTest {

    @TempDir
    Path dir;

    private Path file(String name, byte[] content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content);
        return p;
    }

    @Test
    void writesRealBytesCorrectly() throws IOException {
        file("a.bin", new byte[2]);
        try (BitStore store = new BitStore(dir)) {
            store.setBit(0);
            store.setBit(7);
            store.setBit(9);
            byte[] bytes = Files.readAllBytes(dir.resolve("a.bin"));
            assertEquals((byte) 0b1000_0001, bytes[0]); // bit 7 et bit 0 (mask = 1 << index%8)
            assertEquals((byte) 0b0000_0010, bytes[1]); // bit 9 -> octet 1, mask 1<<1 (LSB first)
        }
    }

    @Test
    void writeIsIdempotent() throws IOException {
        file("a.bin", new byte[1]);
        try (BitStore store = new BitStore(dir)) {
            store.setBit(3);
            store.setBit(3);
            store.setBit(3);
            assertEquals((byte) 0b0000_1000, Files.readAllBytes(dir.resolve("a.bin"))[0]);
            assertTrue(store.readBit(3));
        }
    }

    @Test
    void readBackBits() throws IOException {
        file("a.bin", new byte[]{(byte) 0b1010_0101});
        try (BitStore store = new BitStore(dir)) {
            assertTrue(store.readBit(0));
            assertFalse(store.readBit(1));
            assertTrue(store.readBit(2));
            assertFalse(store.readBit(3));
            assertTrue(store.readBit(7));
        }
    }

    @Test
    void respectsFileBoundaries() throws IOException {
        file("a.bin", new byte[1]);
        file("b.bin", new byte[1]);
        try (BitStore store = new BitStore(dir)) {
            assertEquals(16, store.totalBits());
            store.setBit(7);              // dernier bit de a.bin
            store.setBit(8);              // premier bit de b.bin
            assertEquals((byte) 0b1000_0000, Files.readAllBytes(dir.resolve("a.bin"))[0]);
            assertEquals((byte) 0b0000_0001, Files.readAllBytes(dir.resolve("b.bin"))[0]);
        }
    }

    @Test
    void createsBackupOnFirstWriteOnly() throws IOException {
        file("a.bin", new byte[]{0x00});
        try (BitStore store = new BitStore(dir)) {
            store.setBit(0);
            store.setBit(1);
            store.setBit(2);
            Path bak = dir.resolve("a.bin.bak");
            assertTrue(Files.exists(bak), "le .bak doit exister après la première écriture");
            assertArrayEquals(new byte[]{0x00}, Files.readAllBytes(bak));
            assertEquals((byte) 0b0000_0111, Files.readAllBytes(dir.resolve("a.bin"))[0]);
        }
        // rechargement : le .bak n'est ni listé ni écrasé
        try (BitStore store = new BitStore(dir)) {
            assertEquals(1, store.fileCount());
            store.clearBit(0);
            assertArrayEquals(new byte[]{0x00}, Files.readAllBytes(dir.resolve("a.bin.bak")));
        }
    }

    @Test
    void handlesOutOfRangeBits() throws IOException {
        file("a.bin", new byte[1]);
        try (BitStore store = new BitStore(dir)) {
            assertThrows(IOException.class, () -> store.readBit(8));
            assertThrows(IOException.class, () -> store.writeBit(8, true));
            assertThrows(IOException.class, () -> store.writeBit(-1, false));
        }
    }

    @Test
    void popcountCountsOnes() throws IOException {
        file("a.bin", new byte[]{(byte) 0b1011_0010});
        try (BitStore store = new BitStore(dir)) {
            assertEquals(4, store.entries().get(0).popcount());
            assertEquals(4, store.totalPopcount());
            store.clearBit(1);
            assertEquals(3, store.entries().get(0).popcount());
        }
    }

    @Test
    void totalBitsMatchesFileSizes() throws IOException {
        file("a.bin", new byte[3]);
        file("b.bin", new byte[5]);
        try (BitStore store = new BitStore(dir)) {
            assertEquals(64, store.totalBits());
            assertEquals(2, store.fileCount());
        }
    }

    @Test
    void emptyFilesAreSupported() throws IOException {
        file("a.bin", new byte[0]);
        try (BitStore store = new BitStore(dir)) {
            assertEquals(0, store.totalBits());
            assertThrows(IOException.class, () -> store.readBit(0));
        }
    }

    @Test
    void entryForAndReadHead() throws IOException {
        byte[] head = "MZ header-like".getBytes();
        file("a.bin", head);
        file("b.bin", new byte[]{1, 2, 3});
        try (BitStore store = new BitStore(dir)) {
            assertEquals(136, store.totalBits()); // 14 + 3 octets = 136 bits
            BitStore.FileEntry e = store.entryFor(112); // premier bit de b.bin
            assertNotNull(e);
            assertEquals("b.bin", e.path.getFileName().toString());
            assertEquals(112, e.bitOffset);
            assertArrayEquals(head, store.readHead(store.entryFor(0), 4096));
            assertNull(store.entryFor(999));
        }
    }

    @Test
    void modCountIncrementsOnWrites() throws IOException {
        file("a.bin", new byte[1]);
        try (BitStore store = new BitStore(dir)) {
            long before = store.modCount();
            store.setBit(0);
            store.clearBit(0);
            assertEquals(before + 2, store.modCount());
        }
    }

    @Test
    void randomRoundTripAcrossFiles() throws IOException {
        Random rng = new Random(42);
        byte[] data = new byte[64];
        rng.nextBytes(data);
        file("a.bin", data);
        try (BitStore store = new BitStore(dir)) {
            for (int i = 0; i < 512; i++) {
                boolean expected = (data[i / 8] & (1 << (i % 8))) != 0;
                assertEquals(expected, store.readBit(i), "bit " + i);
            }
        }
    }
}
