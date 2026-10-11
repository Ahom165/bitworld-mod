package com.bitworld;

import com.bitworld.BitStore.FileEntry;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Palette du monde : quel bloc représenter pour un bit donné.
 *
 *  - bit 0                 -> air
 *  - bit 1 (données)       -> GLOWSTONE
 *  - bit 1 dans l'en-tête PE d'un fichier exécutable -> BLOC DE DIAMANT
 *
 * Le choix diamant/n'est-pas-diamant dépend de la position du bit dans le
 * fichier : si l'octet du bit tombe dans la zone d'en-tête PE (MZ ... table
 * des sections), il est rendu en diamant pour être repérable en jeu.
 */
public final class BitPalette {

    private static final int HEAD_READ_LIMIT = 4096;

    /** Cache des longueurs d'en-tête PE par fichier, invalidé à chaque écriture. */
    private static long cacheModCount = -1;
    private static final Map<Long, Integer> HEADER_LENGTHS = new HashMap<>();

    private BitPalette() {}

    /** Bloc à placer pour le bit global donné (air si bit 0). */
    public static BlockState blockForBit(BitStore store, long bitIndex) throws IOException {
        if (!store.readBit(bitIndex)) {
            return Blocks.AIR.defaultBlockState();
        }
        FileEntry entry = store.entryFor(bitIndex);
        int headerLength = headerLengthOf(store, entry);
        boolean inHeader = headerLength > 0
                && PeHeader.inHeaderZone(bitIndex - entry.bitOffset, headerLength);
        return inHeader
                ? Blocks.DIAMOND_BLOCK.defaultBlockState()
                : Blocks.GLOWSTONE.defaultBlockState();
    }

    /** Longueur d'en-tête PE du fichier (0 si non-PE), avec cache par écriture. */
    private static int headerLengthOf(BitStore store, FileEntry entry) throws IOException {
        long mod = store.modCount();
        if (mod != cacheModCount) {
            HEADER_LENGTHS.clear();
            cacheModCount = mod;
        }
        return HEADER_LENGTHS.computeIfAbsent(entry.bitOffset, offset -> {
            try {
                return PeHeader.headerLength(store.readHead(entry, HEAD_READ_LIMIT));
            } catch (IOException e) {
                return 0;
            }
        });
    }
}
