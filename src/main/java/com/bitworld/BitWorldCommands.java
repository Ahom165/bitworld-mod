package com.bitworld;

import com.bitworld.BitStore.FileEntry;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/**
 * Commandes /bitworld :
 *   info    — résumé du miroir (dossier, fichiers, bits, popcount, PE détectés)
 *   stats   — popcount fichier par fichier
 *   peinfo  — détail des en-têtes PE (rendus en diamant dans le monde)
 *   resync  — force la passe de synchronisation monde -> fichiers (TNT etc.)
 *   restore — reconstruit le monde d'après les fichiers
 */
public final class BitWorldCommands {

    private BitWorldCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("bitworld")
                        // API 26.3 : hasPermission(LEVEL_GAMEMASTERS) remplace l'ancien niveau CHEAT
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(infoCommand())
                        .then(statsCommand())
                        .then(peInfoCommand())
                        .then(resyncCommand())
                        .then(restoreCommand())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> infoCommand() {
        return Commands.literal("info").executes(ctx -> {
            BitStore store = requireStore(ctx.getSource());
            if (store == null) {
                return 0;
            }
            try {
                long totalBits = store.totalBits();
                long layoutBits = BitLayout.totalBits();
                int popcount = store.totalPopcount();
                int peFiles = countPeFiles(store);
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "[BitWorld] Dossier miroir : " + store.mirrorDir()), false);
                ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                        "[BitWorld] Fichiers : %d | Bits fichiers : %d | Bits monde : %d | Bits à 1 : %d",
                        store.fileCount(), totalBits, layoutBits, popcount)), false);
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "[BitWorld] Exécutables PE : " + peFiles
                                + " (en-têtes rendus en diamant)"), false);
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal(
                        "[BitWorld] lecture impossible : " + e.getMessage()));
                return 0;
            }
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> statsCommand() {
        return Commands.literal("stats").executes(ctx -> {
            BitStore store = requireStore(ctx.getSource());
            if (store == null) {
                return 0;
            }
            try {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "[BitWorld] Statistiques par fichier :"), false);
                int shown = 0;
                for (FileEntry e : store.entries()) {
                    if (shown++ >= 40) {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "  ... (" + (store.fileCount() - 40) + " fichier(s) de plus)"), false);
                        break;
                    }
                    String name = e.path.getFileName().toString();
                    long bits = e.bitCount;
                    int pop = e.popcount();
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "  - %s : %d bits, popcount=%d", name, bits, pop)), false);
                }
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal(
                        "[BitWorld] lecture impossible : " + e.getMessage()));
                return 0;
            }
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> peInfoCommand() {
        return Commands.literal("peinfo").executes(ctx -> {
            BitStore store = requireStore(ctx.getSource());
            if (store == null) {
                return 0;
            }
            try {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "[BitWorld] Analyse PE (en-têtes en diamant dans le monde) :"), false);
                int peCount = 0;
                for (FileEntry e : store.entries()) {
                    byte[] head = store.readHead(e, 4096);
                    if (!PeHeader.isPeFile(head)) {
                        continue;
                    }
                    peCount++;
                    String name = e.path.getFileName().toString();
                    String desc = PeHeader.describe(head);
                    long firstBit = e.bitOffset;
                    int[] coords = BitLayout.indexToCoords(firstBit);
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "  - %s : %s | 1er bit -> (%d, %d, %d)",
                            name, desc, coords[0], coords[1], coords[2])), false);
                }
                if (peCount == 0) {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "  aucun exécutable PE dans le dossier miroir"), false);
                }
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal(
                        "[BitWorld] lecture impossible : " + e.getMessage()));
                return 0;
            }
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resyncCommand() {
        return Commands.literal("resync").executes(ctx -> {
            WorldSync sync = BitWorldMod.sync();
            if (sync == null) {
                ctx.getSource().sendFailure(Component.literal("[BitWorld] miroir non initialisé"));
                return 0;
            }
            int cleared = sync.scanNow(ctx.getSource().getLevel());
            int n = cleared;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[BitWorld] Resync forcé : " + n + " bit(s) effacé(s) du dossier miroir "
                            + "(écriture physique, fsync)"), false);
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> restoreCommand() {
        return Commands.literal("restore").executes(ctx -> {
            WorldSync sync = BitWorldMod.sync();
            if (sync == null) {
                ctx.getSource().sendFailure(Component.literal("[BitWorld] miroir non initialisé"));
                return 0;
            }
            try {
                int placed = sync.restore(ctx.getSource().getLevel());
                int n = placed;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "[BitWorld] Restore : " + n + " bloc(s) reconstruit(s) depuis les fichiers"), false);
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal(
                        "[BitWorld] restore impossible : " + e.getMessage()));
                return 0;
            }
            return 1;
        });
    }

    private static int countPeFiles(BitStore store) throws IOException {
        int count = 0;
        for (FileEntry e : store.entries()) {
            if (PeHeader.isPeFile(store.readHead(e, 4096))) {
                count++;
            }
        }
        return count;
    }

    private static BitStore requireStore(CommandSourceStack source) {
        BitStore store = BitWorldMod.store();
        if (store == null) {
            source.sendFailure(Component.literal(
                    "[BitWorld] miroir non initialisé (voir les logs au démarrage)"));
            return null;
        }
        return store;
    }
}
