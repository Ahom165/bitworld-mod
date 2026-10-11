package com.bitworld;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronisation monde -> fichiers.
 *
 * "Il faut que la TNT marche" : quand la TNT explose (ou quand un joueur mine,
 * une creeper souffle, etc.), les blocs détruits doivent REELLEMENT effacer les
 * bits correspondants dans les fichiers du dossier miroir — écriture physique
 * avec fsync, sinon le miroir ment.
 *
 * Deux mécanismes :
 *  1. événement de casse joueur -> effacement immédiat du bit ;
 *  2. passe de resync périodique (1 s) : pour chaque colonne de la zone miroir
 *     dont le chunk est chargé, tout bit à 1 dont le bloc est devenu de l'air
 *     (TNT, explosion, /setblock...) est effacé du fichier réel.
 */
public final class WorldSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("BitWorld");
    private static final int RESYNC_INTERVAL_TICKS = 20;

    private final BitStore store;
    private int tickCounter;

    private WorldSync(BitStore store) {
        this.store = store;
    }

    /** Enregistre les événements serveur (tick + casse de bloc). */
    public static WorldSync register(BitStore store) {
        WorldSync sync = new WorldSync(store);
        ServerTickEvents.END_SERVER_TICK.register(sync::onServerTick);
        PlayerBlockBreakEvents.AFTER.register(sync::onBlockBreak);
        return sync;
    }

    private void onServerTick(MinecraftServer server) {
        if (++tickCounter < RESYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        try {
            scanNow(server.overworld());
        } catch (RuntimeException e) {
            LOGGER.error("[BitWorld] passe de resync en échec", e);
        }
    }

    private void onBlockBreak(Level level, Player player, BlockPos pos,
                              BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel != serverLevel.getServer().overworld()) {
            return;
        }
        if (!BitLayout.inBounds(pos.getX(), pos.getY(), pos.getZ())) {
            return;
        }
        try {
            store.clearBit(BitLayout.bitIndex(pos.getX(), pos.getY(), pos.getZ()));
        } catch (IOException e) {
            LOGGER.error("[BitWorld] échec d'effacement du bit en {}", pos.toShortString(), e);
        }
    }

    /**
     * Passe de resync immédiate : parcourt les colonnes de la zone miroir dont
     * le chunk est chargé, et efface les bits dont le bloc a disparu.
     *
     * Optimisation : on teste D'ABORD le bloc (lecture mémoire), et on ne touche
     * aux fichiers (syscalls) que si le bloc est de l'air — cas rare.
     *
     * @return le nombre de bits effacés
     */
    public int scanNow(ServerLevel level) {
        int corrections = 0;
        int minY = Math.max(level.getMinY(), BitLayout.BASE_Y);
        int maxY = Math.min(level.getMaxY() - 1, BitLayout.BASE_Y + BitLayout.LAYER_COUNT - 1);
        int maxZ = BitLayout.getDepthZ() - 1;

        for (int z = 0; z <= maxZ; z++) {
            for (int x = 0; x < BitLayout.WIDTH_X; x++) {
                // colonne ignorée si son chunk n'est pas chargé (pas de TNT hors écran)
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        continue; // bloc présent : rien à corriger
                    }
                    long idx;
                    try {
                        idx = BitLayout.bitIndex(x, y, z);
                    } catch (IllegalArgumentException outside) {
                        continue; // hors zone
                    }
                    try {
                        if (!store.readBit(idx)) {
                            continue; // le fichier dit déjà 0 : conforme
                        }
                        store.clearBit(idx);
                        corrections++;
                    } catch (IOException e) {
                        LOGGER.error("[BitWorld] resync impossible @({},{},{})", x, y, z, e);
                    }
                }
            }
        }

        if (corrections > 0) {
            LOGGER.info("[BitWorld] Resync : {} bit(s) effacé(s) dans les fichiers réels "
                    + "(TNT / explosion / minage), écriture physique effectuée", corrections);
        }
        return corrections;
    }

    /**
     * Reconstruit le monde d'après les fichiers (inverse du resync) :
     * replace les blocs manquants. Retourne le nombre de blocs replacés.
     */
    public int restore(ServerLevel level) throws IOException {
        if (store.totalBits() > 5_000_000) {
            throw new IOException("dossier trop grand pour /bitworld restore ("
                    + store.totalBits() + " bits) — utilise un monde neuf");
        }
        int placed = 0;
        for (long i = 0; i < store.totalBits(); i++) {
            int[] c = BitLayout.indexToCoords(i);
            BlockPos pos = new BlockPos(c[0], c[1], c[2]);
            BlockState want = BitPalette.blockForBit(store, i);
            if (!level.getBlockState(pos).equals(want)) {
                level.setBlock(pos, want, 3, 512);
                placed++;
            }
        }
        LOGGER.info("[BitWorld] Restore : {} bloc(s) replacé(s) depuis les fichiers", placed);
        return placed;
    }

    /** Sonde de test : "ce bloc est-il de l'air ?" (indépendant de Minecraft). */
    @FunctionalInterface
    public interface AirProbe {
        boolean isAir(int x, int y, int z);
    }

    /**
     * Logique pure de resync, testable sans serveur : renvoie la liste des bits
     * à 1 dans le store dont le bloc correspondant est de l'air (donc à effacer).
     */
    public static List<Long> computeCorrections(BitStore store, AirProbe probe) throws IOException {
        List<Long> toClear = new ArrayList<>();
        for (long i = 0; i < store.totalBits(); i++) {
            if (!store.readBit(i)) {
                continue;
            }
            int[] c = BitLayout.indexToCoords(i);
            if (probe.isAir(c[0], c[1], c[2])) {
                toClear.add(i);
            }
        }
        return toClear;
    }
}
