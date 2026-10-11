package com.bitworld;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Accès bit à bit aux fichiers réels du dossier miroir.
 *
 * Chaque fichier du dossier devient une entrée ; les bits sont adressés
 * globalement (cumul des tailles) et relus/écrits directement dans le
 * fichier physique. Toute écriture est suivie d'un sync() du descripteur
 * pour garantir la persistance réelle sur disque.
 */
public final class BitStore implements Closeable {

    /** Un fichier miroir et sa plage de bits globale. */
    public static final class FileEntry {
        public final Path path;
        public final long bitOffset;   // index du premier bit dans l'espace global
        public final long bitCount;    // nombre de bits du fichier
        final RandomAccessFile raf;
        boolean backupCreated;

        FileEntry(Path path, long bitOffset, long bitCount, RandomAccessFile raf) {
            this.path = path;
            this.bitOffset = bitOffset;
            this.bitCount = bitCount;
            this.raf = raf;
        }

        /** Popcount du fichier (nombre de bits à 1). */
        public int popcount() throws IOException {
            int count = 0;
            for (long byteIdx = 0; byteIdx < bitCount / 8; byteIdx++) {
                raf.seek(byteIdx);
                count += Integer.bitCount(raf.read() & 0xFF);
            }
            return count;
        }

        public long byteSize() {
            return bitCount / 8;
        }
    }

    private final List<FileEntry> entries = new ArrayList<>();
    private final Path mirrorDir;
    private long totalBits;
    private long modCount;

    /** Compteur de modifications (invalidation des caches dépendant des bits). */
    public synchronized long modCount() {
        return modCount;
    }

    public BitStore(Path mirrorDir) throws IOException {
        this.mirrorDir = mirrorDir;
        reload();
    }

    /** (Re)découvre les fichiers du dossier et ouvre les descripteurs. */
    public synchronized void reload() throws IOException {
        close();
        entries.clear();
        totalBits = 0;

        if (!Files.isDirectory(mirrorDir)) {
            throw new IOException("dossier miroir introuvable : " + mirrorDir);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(mirrorDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".bak"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }

        for (Path file : files) {
            long size = Files.size(file);
            RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
            entries.add(new FileEntry(file, totalBits, size * 8L, raf));
            totalBits += size * 8L;
        }
    }

    /** Retrouve l'entrée contenant le bit global demandé. */
    private synchronized FileEntry locate(long bitIndex) {
        if (bitIndex < 0 || bitIndex >= totalBits) {
            return null;
        }
        int lo = 0, hi = entries.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            FileEntry e = entries.get(mid);
            if (bitIndex < e.bitOffset) {
                hi = mid - 1;
            } else if (bitIndex >= e.bitOffset + e.bitCount) {
                lo = mid + 1;
            } else {
                return e;
            }
        }
        return null;
    }

    public synchronized boolean readBit(long bitIndex) throws IOException {
        FileEntry e = locate(bitIndex);
        if (e == null) {
            throw new IOException("bitIndex hors limites : " + bitIndex);
        }
        long local = bitIndex - e.bitOffset;
        long byteIndex = local / 8;
        int mask = 1 << (local % 8);
        e.raf.seek(byteIndex);
        int b = e.raf.read();
        return b >= 0 && (b & mask) != 0;
    }

    /**
     * Écrit un bit dans le fichier réel. Le fichier est créé si nécessaire,
     * une sauvegarde .bak est faite au premier write, et le descripteur est
     * synchronisé (sync) pour garantir l'écriture physique sur disque.
     */
    public synchronized void writeBit(long bitIndex, boolean value) throws IOException {
        FileEntry e = locate(bitIndex);
        if (e == null) {
            throw new IOException("bitIndex hors limites : " + bitIndex);
        }
        if (!e.backupCreated) {
            Path bak = e.path.resolveSibling(e.path.getFileName() + ".bak");
            if (Files.exists(e.path) && !Files.exists(bak)) {
                Files.copy(e.path, bak);
            }
            e.backupCreated = true;
        }
        long local = bitIndex - e.bitOffset;
        long byteIndex = local / 8;
        int mask = 1 << (local % 8);
        e.raf.seek(byteIndex);
        int b = e.raf.read();
        if (b < 0) {
            b = 0;
        }
        b = value ? (b | mask) : (b & ~mask);
        e.raf.seek(byteIndex);
        e.raf.write(b);
        e.raf.getFD().sync();
        modCount++;
    }

    /** Retrouve l'entrée (fichier) contenant le bit global demandé, ou null. */
    public synchronized FileEntry entryFor(long bitIndex) {
        return locate(bitIndex);
    }

    /**
     * Lit les premiers octets d'un fichier (jusqu'à maxBytes) — utilisé pour
     * analyser l'en-tête PE sans perturber le curseur des lectures de bits.
     */
    public synchronized byte[] readHead(FileEntry e, int maxBytes) throws IOException {
        long toRead = Math.min(maxBytes, e.byteSize());
        if (toRead <= 0) {
            return new byte[0];
        }
        e.raf.seek(0);
        byte[] head = new byte[(int) toRead];
        int read = e.raf.read(head);
        return read == head.length ? head : java.util.Arrays.copyOf(head, Math.max(0, read));
    }

    public void clearBit(long bitIndex) throws IOException {
        writeBit(bitIndex, false);
    }

    public void setBit(long bitIndex) throws IOException {
        writeBit(bitIndex, true);
    }

    /** Peuple le monde : écrit les blocs du monde d'après les bits (inverse du resync). */
    public List<FileEntry> entries() {
        return List.copyOf(entries);
    }

    public long totalBits() {
        return totalBits;
    }

    public int fileCount() {
        return entries.size();
    }

    public Path mirrorDir() {
        return mirrorDir;
    }

    /** Nombre total de bits à 1 dans tout le dossier. */
    public synchronized int totalPopcount() throws IOException {
        int count = 0;
        for (FileEntry e : entries) {
            count += e.popcount();
        }
        return count;
    }

    @Override
    public synchronized void close() throws IOException {
        for (FileEntry e : entries) {
            try {
                e.raf.close();
            } catch (IOException ignored) {
                // on ferme les suivants quand même
            }
        }
        entries.clear();
        totalBits = 0;
    }
}
