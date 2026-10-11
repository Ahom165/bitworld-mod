package com.bitworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.BlockEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Synchronisation bidirectionnelle monde &lt;-&gt; fichiers.
 *
 * "Il faut que la TNT marche" : quand la TNT explose (ou quand un joueur mine,
 * une creeper souffle, etc.), les blocs détruits doivent REELLEMENT effacer les
 * bits correspondants dans les fichiers du dossier miroir — écriture physique
 * avec fsync, sinon le miroir ment.
 *
 * "Coder à la main in-game" : poser un bloc de la palette (GLOWSTONE ou BLOC
 * DE DIAMANT) doit REELLEMENT écrire le bit à 1 dans le fichier physique.
 *
 * Trois mécanismes :
 *  1. événement de casse joueur (PlayerBlockBreakEvents AFTER) -&gt; effacement
 *     immédiat du bit ;
 *  2. événement de clic-droit avec un bloc de la palette en main (BlockEvents
 *     USE_ITEM_ON) -&gt; positions candidates mises en file, puis vérifiées et
 *     écrites à la fin du tick (le bloc est alors réellement posé) ;
 *  3. passe de resync périodique (1 s) bidirectionnelle :
 *       - bit à 1 + bloc devenu air (TNT, explosion, /setblock) -&gt; effacement ;
 *       - bit à 0 + bloc de palette présent (piston, dispenser, event manqué)
 *         -&gt; écriture.
 */
public final class WorldSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("BitWorld");
    private static final int RESYNC_INTERVAL_TICKS = 20;
    /** Garde-fou anti-spam sur la file des poses candidates (le scan rattrape). */
    private static final int MAX_PENDING = 4096;

    private final BitStore store;
    private final Set<BlockPos> pendingPlacements = new LinkedHashSet<>();
    private int tickCounter;

    private WorldSync(BitStore store) {
        this.store = store;
    }

    /** Enregistre les événements serveur (tick, casse de bloc, pose de bloc). */
    public static WorldSync register(BitStore store) {
        WorldSync sync = new WorldSync(store);
        ServerTickEvents.END_SERVER_TICK.register(sync::onServerTick);
        PlayerBlockBreakEvents.AFTER.register(sync::onBlockBreak);
        BlockEvents.USE_ITEM_ON.register(sync::onUseItemOn);
        return sync;
    }

    private void onServerTick(MinecraftServer server) {
        try {
            drainPendingPlacements(server.overworld());
        } catch (RuntimeException e) {
            LOGGER.error("[BitWorld] écriture des bits de pose en échec", e);
        }
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
        clearBitAt(pos, "casse de bloc");
    }

    /**
     * Clic-droit sur un bloc avec un bloc de la palette en main : la pose
     * vanilla n'a PAS encore eu lieu (l'event est intercepté avant), donc on
     * met en file les deux positions plausibles — le bloc visé (cas d'une cible
     * remplaçable comme l'herbe) et la face visée — et on vérifie l'état réel
     * en fin de tick. On renvoie PASS : la pose vanilla n'est pas modifiée.
     */
    private InteractionResult onUseItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (serverLevel != serverLevel.getServer().overworld()) {
            return InteractionResult.PASS;
        }
        if (!isPaletteItem(stack)) {
            return InteractionResult.PASS;
        }
        enqueueCandidate(pos);
        enqueueCandidate(pos.relative(hit.getDirection()));
        return InteractionResult.PASS;
    }

    private void enqueueCandidate(BlockPos pos) {
        if (pendingPlacements.size() >= MAX_PENDING) {
            return; // spam : le scan de resync rattrapera ces bits
        }
        if (!BitLayout.inBounds(pos.getX(), pos.getY(), pos.getZ())) {
            return;
        }
        pendingPlacements.add(pos);
    }

    /**
     * Vérifie les poses candidates (fin de tick : le bloc est posé ou non) et
     * écrit physiquement les bits correspondants dans les fichiers réels.
     *
     * @return le nombre de bits écrits à 1
     */
    public int drainPendingPlacements(ServerLevel level) {
        if (pendingPlacements.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (BlockPos pos : pendingPlacements) {
            BlockState state = level.getBlockState(pos);
            if (!isPaletteBlock(state)) {
                continue; // pose refusée ou bloc non-palette : rien à écrire
            }
            written += setBitAt(pos);
        }
        pendingPlacements.clear();
        if (written > 0) {
            LOGGER.info("[BitWorld] Pose de blocs : {} bit(s) écrit(s) à 1 dans les fichiers "
                    + "réels (écriture physique fsync)", written);
        }
        return written;
    }

    private void clearBitAt(BlockPos pos, String cause) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (!BitLayout.inBounds(x, y, z)) {
            return;
        }
        try {
            store.clearBit(BitLayout.bitIndex(x, y, z));
        } catch (IOException e) {
            LOGGER.error("[BitWorld] échec d'effacement du bit ({}) en {}",
                    cause, pos.toShortString(), e);
        }
    }

    private int setBitAt(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (!BitLayout.inBounds(x, y, z)) {
            return 0;
        }
        try {
            store.setBit(BitLayout.bitIndex(x, y, z));
            return 1;
        } catch (IOException e) {
            LOGGER.error("[BitWorld] échec d'écriture du bit en {}", pos.toShortString(), e);
            return 0;
        }
    }

    /** Le stack en main est-il un bloc de la palette (bit à 1 en cas de pose) ? */
    private static boolean isPaletteItem(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() == Items.GLOWSTONE || stack.getItem() == Items.DIAMOND_BLOCK);
    }

    /** Le bloc posé représente-t-il un bit à 1 dans le miroir ? */
    private static boolean isPaletteBlock(BlockState state) {
        return state.getBlock() == Blocks.GLOWSTONE || state.getBlock() == Blocks.DIAMOND_BLOCK;
    }

    /**
     * Passe de resync bidirectionnelle : parcourt les colonnes de la zone
     * miroir dont le chunk est chargé, et réconcilie fichiers et monde.
     *  - air + bit à 1   -&gt; effacement (TNT, explosion, minage, /setblock) ;
     *  - palette + bit 0 -&gt; écriture (piston, dispenser, pose non interceptée).
     *
     * Optimisation : on teste D'ABORD le bloc (lecture mémoire), et on ne
     * touche aux fichiers (syscalls) que si l'état est pertinent — cas rare.
     *
     * @return le nombre de corrections (effacements + écritures)
     */
    public int scanNow(ServerLevel level) {
        int cleared = 0;
        int written = 0;
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
                    BlockState state = level.getBlockState(pos);
                    boolean air = state.isAir();
                    if (!air && !isPaletteBlock(state)) {
                        continue; // bloc hors palette (terre, TNT, bedrock...) : rien à faire
                    }
                    long idx;
                    try {
                        idx = BitLayout.bitIndex(x, y, z);
                    } catch (IllegalArgumentException outside) {
                        continue; // hors zone
                    }
                    try {
                        if (air) {
                            if (store.readBit(idx)) {
                                store.clearBit(idx);
                                cleared++;
                            }
                        } else if (!store.readBit(idx)) {
                            store.setBit(idx);
                            written++;
                        }
                    } catch (IOException e) {
                        LOGGER.error("[BitWorld] resync impossible @({},{},{})", x, y, z, e);
                    }
                }
            }
        }

        if (cleared > 0 || written > 0) {
            LOGGER.info("[BitWorld] Resync : {} bit(s) effacé(s) (TNT/explosion/minage), "
                    + "{} bit(s) écrit(s) (pose/piston/dispenser) — écritures physiques fsync",
                    cleared, written);
        }
        return cleared + written;
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

    /** Sonde de test : "un bloc de la palette occupe-t-il cette position ?" */
    @FunctionalInterface
    public interface PaletteProbe {
        boolean hasPaletteBlock(int x, int y, int z);
    }

    /**
     * Logique pure de resync (sens effacement), testable sans serveur : renvoie
     * la liste des bits à 1 dans le store dont le bloc correspondant est de
     * l'air (donc à effacer).
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

    /**
     * Logique pure d'écriture (sens pose), testable sans serveur : renvoie la
     * liste des bits à 0 dont la position est occupée par un bloc de la palette
     * (donc à écrire à 1). Symétrique exact de {@link #computeCorrections}.
     */
    public static List<Long> computeWrites(BitStore store, PaletteProbe probe) throws IOException {
        List<Long> toSet = new ArrayList<>();
        for (long i = 0; i < store.totalBits(); i++) {
            if (store.readBit(i)) {
                continue; // déjà à 1 : conforme
            }
            int[] c = BitLayout.indexToCoords(i);
            if (probe.hasPaletteBlock(c[0], c[1], c[2])) {
                toSet.add(i);
            }
        }
        return toSet;
    }
}
