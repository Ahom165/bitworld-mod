package com.bitworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitLayoutTest {

    @Test
    void bitIndexFormulaMatchesSpec() {
        BitLayout.configure(512); // 8 plans
        // x le plus rapide, puis y, puis z
        assertEquals(0L, BitLayout.bitIndex(0, BitLayout.BASE_Y, 0));
        assertEquals(1L, BitLayout.bitIndex(1, BitLayout.BASE_Y, 0));
        assertEquals((long) BitLayout.WIDTH_X, BitLayout.bitIndex(0, BitLayout.BASE_Y + 1, 0));
        assertEquals(BitLayout.BITS_PER_PLANE, BitLayout.bitIndex(0, BitLayout.BASE_Y, 1));
        long expected = 3L * BitLayout.BITS_PER_PLANE + 2L * BitLayout.WIDTH_X + 5L;
        assertEquals(expected, BitLayout.bitIndex(5, BitLayout.BASE_Y + 2, 3));
    }

    @Test
    void roundTripIndexToCoords() {
        BitLayout.configure(512);
        for (long i = 0; i < BitLayout.totalBits(); i += 7) {
            int[] c = BitLayout.indexToCoords(i);
            assertEquals(i, BitLayout.bitIndex(c[0], c[1], c[2]), "index " + i);
        }
    }

    @Test
    void negativeYRejected() {
        // bug historique : un Y hors couche écrasait silencieusement un autre bit
        BitLayout.configure(512);
        assertThrows(IllegalArgumentException.class,
                () -> BitLayout.bitIndex(0, BitLayout.BASE_Y - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> BitLayout.bitIndex(0, -60, 0));
    }

    @Test
    void yAboveLayersRejected() {
        BitLayout.configure(512);
        assertThrows(IllegalArgumentException.class,
                () -> BitLayout.bitIndex(0, BitLayout.BASE_Y + BitLayout.LAYER_COUNT, 0));
    }

    @Test
    void xOutOfBoundsRejected() {
        BitLayout.configure(512);
        assertThrows(IllegalArgumentException.class, () -> BitLayout.bitIndex(-1, BitLayout.BASE_Y, 0));
        assertThrows(IllegalArgumentException.class, () -> BitLayout.bitIndex(BitLayout.WIDTH_X, BitLayout.BASE_Y, 0));
    }

    @Test
    void zOutOfBoundsRejected() {
        BitLayout.configure(512); // plans 0..7
        assertThrows(IllegalArgumentException.class, () -> BitLayout.bitIndex(0, BitLayout.BASE_Y, 8));
        assertThrows(IllegalArgumentException.class, () -> BitLayout.bitIndex(0, BitLayout.BASE_Y, -1));
    }

    @Test
    void inBoundsChecks() {
        BitLayout.configure(512);
        assertTrue(BitLayout.inBounds(0, BitLayout.BASE_Y, 0));
        assertTrue(BitLayout.inBounds(7, BitLayout.BASE_Y + 7, 7));
        assertFalse(BitLayout.inBounds(8, BitLayout.BASE_Y, 0));
        assertFalse(BitLayout.inBounds(0, 0, 0));
        assertFalse(BitLayout.inBounds(0, BitLayout.BASE_Y, 100));
    }

    @Test
    void configureGrowsDepthWithFolder() {
        BitLayout.configure(0);
        assertEquals(1, BitLayout.getDepthZ(), "au moins un plan même vide");
        BitLayout.configure(512);
        assertEquals(8, BitLayout.getDepthZ());
        BitLayout.configure(513);
        assertEquals(9, BitLayout.getDepthZ(), "un bit de plus -> un plan de plus");
        BitLayout.configure(1152); // 64 fichiers d'un octet + un PE de 80 octets
        assertEquals(18, BitLayout.getDepthZ());
        assertEquals(1152, BitLayout.totalBits());
    }

    @Test
    void configureRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> BitLayout.configure(-1));
    }
}
