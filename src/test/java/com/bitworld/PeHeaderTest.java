package com.bitworld;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class PeHeaderTest {

    /** Fabrique un PE minimal : MZ + e_lfanew + signature PE + COFF + optionnel + sections. */
    private byte[] pe(int eLfanew, int machine, int sections, int optSize, int totalSize) {
        ByteBuffer b = ByteBuffer.allocate(totalSize);
        byte[] data = new byte[totalSize];
        data[0] = 'M';
        data[1] = 'Z';
        data[0x3C] = (byte) eLfanew;
        data[0x3D] = (byte) (eLfanew >> 8);
        data[eLfanew] = 'P';
        data[eLfanew + 1] = 'E';
        // COFF à eLfanew+4 : machine (2), numberOfSections (2, +2), ..., sizeOfOptionalHeader (2, +16)
        data[eLfanew + 4] = (byte) machine;
        data[eLfanew + 5] = (byte) (machine >> 8);
        data[eLfanew + 6] = (byte) sections;
        data[eLfanew + 7] = (byte) (sections >> 8);
        data[eLfanew + 20] = (byte) optSize;
        data[eLfanew + 21] = (byte) (optSize >> 8);
        return data;
    }

    @Test
    void rejectsShortData() {
        assertFalse(PeHeader.isPeFile(null));
        assertFalse(PeHeader.isPeFile(new byte[0]));
        assertFalse(PeHeader.isPeFile(new byte[63]));
    }

    @Test
    void rejectsNonMz() {
        byte[] data = new byte[256];
        data[0] = 'X';
        data[1] = 'Y';
        assertFalse(PeHeader.isPeFile(data));
    }

    @Test
    void rejectsBadLfanew() {
        byte[] data = new byte[64];
        data[0] = 'M';
        data[1] = 'Z';
        data[0x3C] = (byte) 0xFF; // e_lfanew immense -> hors bornes
        data[0x3D] = (byte) 0xFF;
        data[0x3E] = (byte) 0xFF;
        data[0x3F] = (byte) 0x7F;
        assertFalse(PeHeader.isPeFile(data));
    }

    @Test
    void rejectsMissingPeSignature() {
        byte[] data = new byte[256];
        data[0] = 'M';
        data[1] = 'Z';
        data[0x3C] = 64;
        // pas de "PE\0\0" en 64
        assertFalse(PeHeader.isPeFile(data));
    }

    @Test
    void acceptsValidMinimalPe() {
        byte[] data = pe(64, 0x8664, 3, 240, 512);
        assertTrue(PeHeader.isPeFile(data));
    }

    @Test
    void headerLengthFormula() {
        // e_lfanew(64) + 4 (signature) + 20 (COFF) + 240 (optionnel) + 3*40 (sections) = 448
        byte[] data = pe(64, 0x8664, 3, 240, 4096);
        assertEquals(448, PeHeader.headerLength(data));
    }

    @Test
    void headerLengthClampedToFileSize() {
        byte[] data = pe(64, 0x8664, 3, 240, 300); // en-tête tronqué
        assertEquals(300, PeHeader.headerLength(data));
    }

    @Test
    void headerLengthZeroForNonPe() {
        assertEquals(0, PeHeader.headerLength(new byte[10]));
        assertEquals(0, PeHeader.headerLength(pe(64, 0x8664, 99, 9999, 4096)));
    }

    @Test
    void describeTellsArchitecture() {
        assertTrue(PeHeader.describe(pe(64, 0x8664, 3, 240, 4096)).contains("x64"));
        assertTrue(PeHeader.describe(pe(64, 0x014C, 1, 224, 4096)).contains("x86"));
        assertEquals("non PE", PeHeader.describe(new byte[12]));
    }

    @Test
    void inHeaderZoneMath() {
        // 448 octets d'en-tête : les bits 0..3583 sont dans la zone
        assertTrue(PeHeader.inHeaderZone(0, 448));
        assertTrue(PeHeader.inHeaderZone(3583, 448));
        assertFalse(PeHeader.inHeaderZone(3584, 448));
        assertFalse(PeHeader.inHeaderZone(100_000, 448));
    }

    @Test
    void littleEndianReads() {
        byte[] data = {(byte) 0x34, (byte) 0x12};
        assertEquals(0x1234, PeHeader.u16(data, 0));
        byte[] four = {0x78, 0x56, 0x34, 0x12};
        assertEquals(0x12345678L, PeHeader.u32(four, 0));
        assertEquals(-1, PeHeader.u16(data, 5));
        assertEquals(-1L, PeHeader.u32(data, 5));
    }

    @Test
    void realWorldShapedExecutable() {
        // un .exe "réaliste" : DOS stub de 128 octets, PE à 128, 5 sections, PE32+ opt 240
        byte[] data = pe(128, 0x8664, 5, 240, 8192);
        assertTrue(PeHeader.isPeFile(data));
        int expected = 128 + 4 + 20 + 240 + 5 * 40;
        assertEquals(expected, PeHeader.headerLength(data));
        String desc = PeHeader.describe(data);
        assertTrue(desc.contains("5 section(s)"));
    }
}
