package com.bitworld;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Générateur du monde miroir : un sol de bedrock plein, puis le cube de bits
 * (glowstone = bit 1, diamant = bit 1 situé dans l'en-tête PE d'un fichier,
 * air = bit 0) posé à partir de BASE_Y.
 *
 * API 26.3 : le jeu n'est plus obfusqué, buildTerrain remplace fillFromNoise,
 * et la source de biome est décodée depuis le datapack (les biomes sont des
 * registres dynamiques, plus de BuiltInRegistries.BIOME).
 */
public final class BitWorldChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BitWorldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
            ).apply(instance, BitWorldChunkGenerator::new));

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final Set<Heightmap.Types> PRIMED_HEIGHTMAPS =
            Set.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR);

    private final BiomeSource biomeSource;

    public BitWorldChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
        this.biomeSource = biomeSource;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender,
                                                       RandomState randomState,
                                                       StructureManager structureManager,
                                                       BiomeManager biomeManager,
                                                       WorldGenRegion region,
                                                       Set<Holder<Biome>> spawnBiomes) {
        fillChunk(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Remplit le chunk : bedrock de getMinY() à BASE_Y - 1, puis les bits de la
     * zone miroir qui tombent dans ce chunk. Les heightmaps sont tenues à jour
     * bloc par bloc (WORLD_SURFACE + OCEAN_FLOOR).
     */
    private void fillChunk(ChunkAccess chunk) {
        BitStore store = BitWorldMod.store();
        ChunkPos cp = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinY();

        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR);

        // 1) sol de bedrock plein (anti-void, résistant à la TNT)
        int bedrockTop = BitLayout.BASE_Y - 1;
        for (int worldY = Math.max(minY, getMinY()); worldY <= bedrockTop; worldY++) {
            LevelChunkSection section = sectionFor(sections, minY, worldY);
            if (section == null) {
                continue;
            }
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    section.setBlockState(lx, worldY & 15, lz, BEDROCK, false);
                }
            }
        }

        // 2) les bits du dossier miroir (uniquement si le chunk intersecte la zone)
        if (store != null) {
            int minX = Math.max(cp.getMinBlockX(), 0);
            int maxX = Math.min(cp.getMaxBlockX(), BitLayout.WIDTH_X - 1);
            int minZ = Math.max(cp.getMinBlockZ(), 0);
            int maxZ = Math.min(cp.getMaxBlockZ(), BitLayout.getDepthZ() - 1);
            if (minX <= maxX && minZ <= maxZ) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = BitLayout.BASE_Y; y < BitLayout.BASE_Y + BitLayout.LAYER_COUNT; y++) {
                        LevelChunkSection section = sectionFor(sections, minY, y);
                        if (section == null) {
                            continue;
                        }
                        for (int x = minX; x <= maxX; x++) {
                            BlockState state = blockForBitSafe(store, x, y, z);
                            if (state != null) {
                                section.setBlockState(x & 15, y & 15, z & 15, state, false);
                            }
                        }
                    }
                }
            }
        }

        // 3) heightmaps recalculées par colonne (les blocs sont déjà posés)
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int worldX = cp.getMinBlockX() + lx;
                int worldZ = cp.getMinBlockZ() + lz;
                int top = heightOfColumn(store, worldX, worldZ);
                for (int worldY = bedrockTop; worldY < top; worldY++) {
                    BlockState state = worldY <= bedrockTop ? BEDROCK
                            : blockForBitSafe(store, worldX, worldY, worldZ);
                    if (state == null) {
                        continue;
                    }
                    surface.update(lx, worldY, lz, state);
                    oceanFloor.update(lx, worldY, lz, state);
                }
            }
        }
    }

    /** Première coordonnée libre au-dessus de la colonne (bedrock + cube). */
    private int heightOfColumn(BitStore store, int worldX, int worldZ) {
        boolean inRegion = worldX >= 0 && worldX < BitLayout.WIDTH_X
                && worldZ >= 0 && worldZ < BitLayout.getDepthZ();
        return inRegion ? BitLayout.BASE_Y + BitLayout.LAYER_COUNT : BitLayout.BASE_Y;
    }

    private BlockState blockForBitSafe(BitStore store, int x, int y, int z) {
        try {
            return BitPalette.blockForBit(store, BitLayout.bitIndex(x, y, z));
        } catch (IOException | IllegalArgumentException e) {
            return null; // bit illisible : on laisse l'air
        }
    }

    private static LevelChunkSection sectionFor(LevelChunkSection[] sections, int minY, int worldY) {
        int idx = (worldY >> 4) - (minY >> 4);
        if (idx < 0 || idx >= sections.length) {
            return null;
        }
        return sections[idx];
    }

    // ------------------------------------------------------------------
    // API 26.3 : méthodes abstraites restantes
    // ------------------------------------------------------------------

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // monde stérile : aucune créature naturelle
    }

    @Override
    public int getGenDepth() {
        return 384; // hauteur standard -64 -> 320
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getSeaLevel() {
        return -63; // pas d'eau dans ce monde
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
                             RandomState randomState) {
        boolean inRegion = x >= 0 && x < BitLayout.WIDTH_X
                && z >= 0 && z < BitLayout.getDepthZ();
        return inRegion ? BitLayout.BASE_Y + BitLayout.LAYER_COUNT : BitLayout.BASE_Y;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        boolean inRegion = x >= 0 && x < BitLayout.WIDTH_X
                && z >= 0 && z < BitLayout.getDepthZ();
        BlockState[] column = new BlockState[level.getHeight()];
        int top = inRegion ? BitLayout.BASE_Y + BitLayout.LAYER_COUNT : BitLayout.BASE_Y;
        for (int y = level.getMinY(); y < Math.min(top, level.getMaxY()); y++) {
            column[y - level.getMinY()] = BEDROCK;
        }
        return new NoiseColumn(level.getMinY(), column);
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos,
                                   SamplerContext samplerContext) {
        lines.add("BitWorld : miroir binaire statique (dossier "
                + (BitWorldMod.config() != null ? BitWorldMod.config().configuredPath() : "?") + ")");
    }

    /** Codec du biome source exposé pour le datapack (tests/debug). */
    public static Codec<BiomeSource> biomeSourceCodec() {
        return BiomeSource.CODEC;
    }
}
