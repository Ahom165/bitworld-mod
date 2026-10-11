# BitWorld Mod

Mod Fabric pour **Minecraft 26.3** qui reflète les données du monde sous forme de **bits stockés dans de vrais fichiers** sur le disque. Le monde est littéralement une image binaire : chaque bloc correspond à un bit, et chaque bit est un octet adressable dans le dossier miroir `c:/users/Ahomine_/experiment` (configurable).

## Fonctionnalités

- **Générateur de chunks personnalisé** (`BitWorldChunkGenerator`) : le terrain est reconstruit bit par bit depuis les fichiers réels.
- **Miroir disque au niveau du bit** (`BitStore`) : I/O `RandomAccessFile` avec `fsync` physique après chaque écriture, découpage en fichiers `file00.bin`, `file01.bin`, …
- **En-tête PE en diamant** (`PeHeader` + `BitPalette`) : déposez un vrai fichier `.exe` (format PE) dans le dossier miroir — le mod détecte la signature `MZ`, lit `e_lfanew` à l'offset `0x3C`, valide `PE\0\0` et mesure la longueur d'en-tête (COFF + optionnel + sections). Les bits à 1 de cette **zone d'en-tête sont rendus en blocs de diamant**, les bits à 1 des données en glowstone. Le motif d'affichage suit une trame en diamant dans le plan X/Z.
- **TNT fonctionnelle** (`WorldSync`) : les explosions de TNT détruisent les blocs côté jeu **et** effacent les bits correspondants dans les fichiers réels. Un event `PlayerBlockBreakEvents.AFTER` efface immédiatement, et une passe de resync toutes les 20 ticks rattrape tout (TNT, creepers, `/setblock air`, …).
- **Coder à la main in-game (v1.2.0)** : poser un bloc de la palette (GLOWSTONE ou BLOC DE DIAMANT) **écrit réellement le bit à 1** dans le fichier physique (fsync). Détection instantanée via `BlockEvents.USE_ITEM_ON` (vérification en fin de tick) + resync bidirectionnel qui rattrape pistons, dispensers et poses non interceptées.
- **Commandes** :
  - `/bitworld info` — état du monde binaire
  - `/bitworld stats` — population de bits (popcount)
  - `/bitworld peinfo` — diagnostic de l'en-tête PE détecté
  - `/bitworld restore` — resynchronise les fichiers vers le jeu

## Architecture

```
src/main/java/com/bitworld/
├── BitWorldMod.java            # Entrée Fabric, enregistrements
├── BitWorldConfig.java         # Chemin du dossier miroir, constantes
├── BitLayout.java              # Mapping (x, y, z) → index de bit
├── BitStore.java               # I/O bits ↔ fichiers réels
├── BitWorldChunkGenerator.java # Génération depuis les bits
├── BitPalette.java             # Choix des blocs (glowstone / diamant)
├── PeHeader.java               # Détection & parsing en-tête PE
├── WorldSync.java              # TNT, casse, pose de blocs, resync bidirectionnel
└── BitWorldCommands.java       # Commandes /bitworld
```

## Compilation

```bash
./gradlew build        # JAR dans build/libs/
./gradlew test         # 48 tests unitaires
```

Prérequis : JDK 21+, Gradle 8.14+ (plugin Fabric Loom variante no-remap — Minecraft 26.3 n'est plus obfusqué).

## Installation sur un serveur

1. Copiez `bitworld-1.1.0.jar` et Fabric API dans `mods/`.
2. Le datapack dimension (`data/minecraft/dimension/overworld.json`) force le générateur BitWorld sur l'Overworld.
3. Le dossier miroir est créé au premier démarrage ; déposez-y un `.exe` pour voir l'en-tête PE en diamant.

## Tests

**48/48 tests verts** : `BitStore` (12), `BitLayout` (9), `PeHeader` (12), `WorldSync` (12), `BitWorldConfig` (3). Validés également par smoke tests sur serveur réel (démarrage, commandes, resync TNT vérifié à l'octet près avec `od`).
