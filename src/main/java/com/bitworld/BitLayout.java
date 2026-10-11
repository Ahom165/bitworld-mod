package com.bitworld;

/**
 * Mapping bijectif entre les bits des fichiers et les coordonnées du monde.
 *
 * Organisation du monde :
 *  - axe X : WIDTH_X bits (largeur d'un plan)
 *  - axe Y : LAYER_COUNT couches, à partir de BASE_Y
 *  - axe Z : DEPTH_Z plans (un plan = WIDTH_X * LAYER_COUNT bits consécutifs)
 *
 * Formule (index le plus rapide en premier) :
 *   bitIndex(x,y,z) = z * (WIDTH_X * LAYER_COUNT) + (y - BASE_Y) * WIDTH_X + x
 *
 * La profondeur DEPTH_Z est dérivée du nombre total de bits réellement présents
 * dans le dossier miroir : le monde grandit avec les fichiers.
 */
public final class BitLayout {

    public static final int WIDTH_X = 8;
    public static final int LAYER_COUNT = 8;
    public static final int BASE_Y = 64;

    /** Bits par plan (une tranche Z complète). */
    public static final long BITS_PER_PLANE = (long) WIDTH_X * LAYER_COUNT;

    /** Profondeur minimale : au moins un plan même si le dossier est vide. */
    private static volatile int depthZ = 1;

    private BitLayout() {}

    /**
     * Recalcule la profondeur du monde à partir du nombre total de bits du dossier.
     * Appelé au chargement du mod (après la découverte des fichiers).
     */
    public static void configure(long totalBits) {
        if (totalBits < 0) {
            throw new IllegalArgumentException("totalBits négatif : " + totalBits);
        }
        long planes = (totalBits + BITS_PER_PLANE - 1) / BITS_PER_PLANE;
        depthZ = (int) Math.max(1, planes);
    }

    /** Profondeur actuelle du monde (nombre de plans Z). */
    public static int getDepthZ() {
        return depthZ;
    }

    /** Nombre total de bits représentés dans le monde. */
    public static long totalBits() {
        return (long) depthZ * BITS_PER_PLANE;
    }

    /**
     * Convertit des coordonnées monde en index de bit global.
     * Lève IllegalArgumentException si la coordonnée est hors de la zone miroir :
     * sans cette validation, un Y hors bornes écrasait silencieusement un autre bit
     * (corruption de fichier) — d'où le contrôle explicite ajouté.
     */
    public static long bitIndex(int x, int y, int z) {
        if (x < 0 || x >= WIDTH_X) {
            throw new IllegalArgumentException("X=" + x + " hors [0," + WIDTH_X + ")");
        }
        if (y < BASE_Y || y >= BASE_Y + LAYER_COUNT) {
            throw new IllegalArgumentException("Y=" + y + " hors couche gérée [" + BASE_Y + "," + (BASE_Y + LAYER_COUNT) + ")");
        }
        if (z < 0 || z >= getDepthZ()) {
            throw new IllegalArgumentException("Z=" + z + " hors [0," + getDepthZ() + ")");
        }
        return (long) z * BITS_PER_PLANE + (long) (y - BASE_Y) * WIDTH_X + x;
    }

    /** Conversion inverse : index de bit global -> coordonnées monde. */
    public static int[] indexToCoords(long bitIndex) {
        if (bitIndex < 0 || bitIndex >= totalBits()) {
            throw new IllegalArgumentException("bitIndex=" + bitIndex + " hors [0," + totalBits() + ")");
        }
        int z = (int) (bitIndex / BITS_PER_PLANE);
        long inPlane = bitIndex % BITS_PER_PLANE;
        int y = BASE_Y + (int) (inPlane / WIDTH_X);
        int x = (int) (inPlane % WIDTH_X);
        return new int[] {x, y, z};
    }

    /** true si la coordonnée appartient à la zone miroir (version non-throwing). */
    public static boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < WIDTH_X
                && y >= BASE_Y && y < BASE_Y + LAYER_COUNT
                && z >= 0 && z < getDepthZ();
    }
}
