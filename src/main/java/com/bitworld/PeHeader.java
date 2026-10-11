package com.bitworld;

/**
 * Détection et description de l'en-tête PE (Portable Executable, format .exe Windows).
 *
 * Un fichier PE commence par :
 *  - la signature DOS "MZ" (0x4D 0x5A) à l'offset 0
 *  - e_lfanew (offset de la signature PE), entier little-endian 32 bits à l'offset 0x3C
 *  - la signature "PE\0\0" à l'offset e_lfanew
 *  - l'en-tête COFF (20 octets) : machine, nombre de sections, taille de l'en-tête optionnel...
 *  - l'en-tête optionnel (PE32 ou PE32+)
 *  - la table des sections (40 octets par section)
 *
 * La longueur totale de l'en-tête sert au générateur : les bits de la zone
 * d'en-tête sont rendus en BLOCS DE DIAMANT pour être identifiables en jeu.
 */
public final class PeHeader {

    /** Machines COFF connues, pour l'affichage. */
    private static final int MACHINE_X64 = 0x8664;
    private static final int MACHINE_X86 = 0x014C;
    private static final int MACHINE_ARM64 = 0xAA64;
    private static final int MACHINE_ARM = 0x01C0;

    private static final int DOS_MAGIC_0 = 0x4D; // 'M'
    private static final int DOS_MAGIC_1 = 0x5A; // 'Z'
    private static final int E_LFANEW_OFFSET = 0x3C;
    private static final int COFF_HEADER_SIZE = 20;
    private static final int SECTION_TABLE_ENTRY_SIZE = 40;

    private PeHeader() {}

    /** Lit un entier little-endian non signé (2 octets) ou -1 si hors bornes. */
    static int u16(byte[] data, int offset) {
        if (offset < 0 || offset + 1 >= data.length) {
            return -1;
        }
        return ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }

    /** Lit un entier little-endian non signé (4 octets) ou -1 si hors bornes. */
    static long u32(byte[] data, int offset) {
        if (offset < 0 || offset + 3 >= data.length) {
            return -1L;
        }
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    /**
     * true si les octets ressemblent à un fichier PE valide :
     * magic "MZ", e_lfanew dans les bornes, et signature "PE\0\0" à e_lfanew.
     */
    public static boolean isPeFile(byte[] data) {
        if (data == null || data.length < 64) {
            return false;
        }
        if ((data[0] & 0xFF) != DOS_MAGIC_0 || (data[1] & 0xFF) != DOS_MAGIC_1) {
            return false;
        }
        long eLfanew = u32(data, E_LFANEW_OFFSET);
        if (eLfanew < 0 || eLfanew + 4 > data.length) {
            return false;
        }
        int p0 = data[(int) eLfanew] & 0xFF;
        int p1 = data[(int) eLfanew + 1] & 0xFF;
        int p2 = data[(int) eLfanew + 2] & 0xFF;
        int p3 = data[(int) eLfanew + 3] & 0xFF;
        return p0 == 'P' && p1 == 'E' && p2 == 0 && p3 == 0;
    }

    /**
     * Longueur de l'en-tête PE en octets :
     *   e_lfanew + 4 (signature) + 20 (COFF) + SizeOfOptionalHeader + sections * 40.
     * Renvoie 0 si le fichier n'est pas un PE ou si la table est incohérente.
     * Le résultat est borné à la taille réelle du fichier.
     */
    public static int headerLength(byte[] data) {
        if (!isPeFile(data)) {
            return 0;
        }
        int eLfanew = (int) u32(data, E_LFANEW_OFFSET);
        int coff = eLfanew + 4;
        int numberOfSections = u16(data, coff + 2);
        int sizeOfOptionalHeader = u16(data, coff + 16);
        if (numberOfSections < 0 || sizeOfOptionalHeader < 0
                || numberOfSections > 96 || sizeOfOptionalHeader > 4096) {
            return 0; // valeurs incohérentes : on ne prend pas de risque
        }
        long end = (long) coff + COFF_HEADER_SIZE + sizeOfOptionalHeader
                + (long) numberOfSections * SECTION_TABLE_ENTRY_SIZE;
        if (end > data.length) {
            return data.length; // en-tête tronqué : on borne au fichier réel
        }
        return (int) end;
    }

    /** Description lisible : architecture, sections, taille d'en-tête. */
    public static String describe(byte[] data) {
        if (!isPeFile(data)) {
            return "non PE";
        }
        int eLfanew = (int) u32(data, E_LFANEW_OFFSET);
        int coff = eLfanew + 4;
        int machine = u16(data, coff);
        int numberOfSections = u16(data, coff + 2);
        int sizeOfOptionalHeader = u16(data, coff + 16);
        String arch = switch (machine) {
            case MACHINE_X64 -> "x64 (PE32+)";
            case MACHINE_X86 -> "x86 (PE32)";
            case MACHINE_ARM64 -> "ARM64";
            case MACHINE_ARM -> "ARM";
            default -> "machine 0x" + Integer.toHexString(machine);
        };
        return String.format("PE %s, %d section(s), en-tête %d octets (%.1f%% du fichier)",
                arch, numberOfSections, headerLength(data),
                100.0 * headerLength(data) / data.length);
    }

    /** true si l'octet à l'index global donné tombe dans la zone d'en-tête PE du fichier. */
    public static boolean inHeaderZone(long bitIndexInFile, int headerByteLength) {
        return bitIndexInFile / 8 < headerByteLength;
    }
}
