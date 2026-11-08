package ab3d2.full;

import ab3d2.full.defs.Database;
import ab3d2.full.defs.DatabaseReader;
import ab3d2.full.level.Backdrop;
import ab3d2.full.level.Level;
import ab3d2.full.level.Palette;
import ab3d2.full.level.LevelReader;
import ab3d2.full.level.FloorTexture;
import ab3d2.full.level.ShadeTable;
import ab3d2.full.level.SpriteSheet;
import ab3d2.full.level.TextureMaps;
import ab3d2.full.level.VectorModel;
import ab3d2.full.level.WaterFrames;
import ab3d2.full.level.WallTexture;
import ab3d2.full.render.EdgePvs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ou trouver les donnees du jeu, et comment les ouvrir.
 *
 * <p>Deux racines, pour deux provenances. Les niveaux et la base de liaison viennent des
 * disquettes, depackees ailleurs, et leur chemin se regle par {@code ab3d2.media}. Les tables
 * que l'assembleur incorporait dans l'executable — la table de sinus notamment — vivent avec le
 * depot, sous {@code data/}.
 *
 * <p>La base et la table de sinus sont lues une fois : elles ne dependent d'aucun niveau et rien
 * ne les modifie.
 */
public final class Data {

    private static final Map<String, Level> LEVELS = new HashMap<>();
    private static final Map<String, EdgePvs> EDGE_PVS = new HashMap<>();
    private static final Map<String, List<WallTexture>> WALLS = new HashMap<>();
    private static final Map<String, FloorTexture> FLOORS = new HashMap<>();
    private static ShadeTable shades;
    private static TextureMaps textureMaps;
    private static byte[] guff;
    private static WaterFrames water;
    private static Backdrop sky;
    private static final Map<Integer, SpriteSheet> SHEETS = new HashMap<>();
    private static final Map<Integer, VectorModel> MODELS = new HashMap<>();
    private static Database database;
    private static Palette palette;
    private static SinCos sinCos;
    private static int[] iterations;

    private Data() {
    }

    /** La racine des donnees depackees des disquettes. */
    public static Path media() {
        return Path.of(System.getProperty("ab3d2.media", "medias/original"));
    }

    /** La racine des tables livrees avec le depot. */
    public static Path local() {
        return Path.of(System.getProperty("ab3d2.data", "data"));
    }

    /** Un niveau, designe par sa lettre. */
    public static Level level(char letter) {
        return LEVELS.computeIfAbsent(String.valueOf(Character.toLowerCase(letter)), k -> {
            try {
                return LevelReader.read(media(), k.charAt(0));
            } catch (IOException e) {
                throw new UncheckedIOException("niveau " + k + " illisible", e);
            }
        });
    }

    /**
     * Le decoupage de la visibilite par ouverture, pour un niveau.
     *
     * <p>Il se deduit du niveau seul et ne change jamais, mais il coute une poignee de
     * millisecondes a etablir : on le garde comme le niveau lui-meme.
     */
    public static EdgePvs edgePvs(char letter) {
        return EDGE_PVS.computeIfAbsent(String.valueOf(Character.toLowerCase(letter)),
                k -> new EdgePvs(level(k.charAt(0))));
    }

    /** La base de liaison. */
    public static synchronized Database database() {
        if (database == null) {
            try {
                database = DatabaseReader.read(media());
            } catch (IOException e) {
                throw new UncheckedIOException("base de liaison illisible", e);
            }
        }
        return database;
    }

    /**
     * La table d'iterations du rendu des murs.
     *
     * <p>Pour chaque largeur de bande a l'ecran, elle donne de combien decaler pour obtenir le
     * pas — c'est-a-dire la puissance de deux immediatement superieure — et combien de pas
     * faire. Le jeu la lit d'un seul tenant : le mot de poids faible est le decalage, celui de
     * poids fort le nombre de pas moins un.
     */
    public static synchronized int[] iterations() {
        if (iterations == null) {
            java.nio.file.Path f = local().resolve("incbin").resolve("includes")
                    .resolve("iterfile");
            byte[] raw;
            try {
                raw = java.nio.file.Files.readAllBytes(f);
            } catch (IOException e) {
                throw new UncheckedIOException("table d'iterations introuvable : " + f, e);
            }
            iterations = new int[raw.length / 4];
            for (int i = 0; i < iterations.length; i++) {
                iterations[i] = ((raw[i * 4] & 0xFF) << 24) | ((raw[i * 4 + 1] & 0xFF) << 16)
                        | ((raw[i * 4 + 2] & 0xFF) << 8) | (raw[i * 4 + 3] & 0xFF);
            }
        }
        return iterations;
    }

    /**
     * Les textures de mur d'un niveau, dans l'ordre ou le niveau y renvoie.
     *
     * <p>Seize emplacements, dont treize seulement portent un nom dans la base de liaison. Un
     * emplacement sans nom reste vide : aucun mur ne devrait y renvoyer.
     *
     * <p>Chaque emplacement peut etre REMPLACE pour un niveau donne, par un fichier
     * {@code wall_N.256wad} range avec le niveau. Le jeu essaie toujours de l'ouvrir et garde
     * le fichier commun quand il n'existe pas — ce qui est le cas partout dans le jeu tel
     * qu'il a ete publie.
     */
    public static synchronized List<WallTexture> walls(char letter) {
        return WALLS.computeIfAbsent(String.valueOf(Character.toLowerCase(letter)), k -> {
            List<WallTexture> out = new ArrayList<>();
            for (Database.Resource r : database().wallTextures()) {
                Path override = asset("ab3:levels/level_" + k.charAt(0) + "/wall_"
                        + "0123456789ABCDEF".charAt(r.index()) + ".256wad");
                Path file = override != null ? override : asset(r.file());
                if (file == null) {
                    out.add(null);
                    continue;
                }
                try {
                    out.add(WallTexture.read(file));
                } catch (IOException e) {
                    throw new UncheckedIOException("texture illisible : " + file, e);
                }
            }
            return out;
        });
    }

    /**
     * Le bloc des textures de sol d'un niveau.
     *
     * <p>Comme pour les murs, un niveau peut le REMPLACER par un fichier range avec lui. Ici
     * encore, aucun ne le fait dans le jeu tel qu'il a ete publie.
     */
    public static synchronized FloorTexture floors(char letter) {
        return FLOORS.computeIfAbsent(String.valueOf(Character.toLowerCase(letter)), k -> {
            Path override = asset("ab3:levels/level_" + k.charAt(0) + "/floortile");
            Path file = override != null ? override : asset(database().floorFile());
            if (file == null) {
                throw new IllegalStateException("bloc de textures de sol introuvable");
            }
            try {
                return FloorTexture.read(file);
            } catch (IOException e) {
                throw new UncheckedIOException("bloc de sols illisible : " + file, e);
            }
        });
    }

    /** La table d'ombrage, commune a tout le jeu. */
    public static synchronized ShadeTable shades() {
        if (shades == null) {
            Path file = asset(database().texturePaletteFile());
            if (file == null) {
                throw new IllegalStateException("table d'ombrage introuvable");
            }
            try {
                shades = ShadeTable.read(file);
            } catch (IOException e) {
                throw new UncheckedIOException("table d'ombrage illisible : " + file, e);
            }
        }
        return shades;
    }

    /**
     * Le bloc de textures des modeles en relief, commun a tout le jeu.
     *
     * <p>Les murs n'en sont pas : ils ont leurs propres fichiers, un par texture.
     */
    public static synchronized TextureMaps textureMaps() {
        if (textureMaps == null) {
            Path file = asset(database().textureFile());
            if (file == null) {
                throw new IllegalStateException("bloc de textures introuvable");
            }
            try {
                textureMaps = TextureMaps.read(file);
            } catch (IOException e) {
                throw new UncheckedIOException("bloc de textures illisible : " + file, e);
            }
        }
        return textureMaps;
    }

    /**
     * Ou se trouve un fichier que les donnees d'origine designent a la mode Amiga.
     *
     * <p>Un nom comme {@code TKG1:WALLINC/STONEWALL.256WAD} porte le volume qui le contenait —
     * une disquette parmi cinq — et la casse n'y veut rien dire. On jette le volume, on
     * descend l'arborescence sans regarder la casse, et on rend {@code null} si le nom est vide
     * ou si rien ne correspond.
     */
    public static Path asset(String amigaPath) {
        String rest = amigaPath.trim();
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(colon + 1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        Path at = media();
        for (String step : rest.split("/")) {
            Path exact = at.resolve(step);
            if (Files.exists(exact)) {
                at = exact;
                continue;
            }
            Path found = null;
            try (var entries = Files.list(at)) {
                found = entries.filter(p -> p.getFileName().toString().equalsIgnoreCase(step))
                        .findFirst().orElse(null);
            } catch (IOException e) {
                return null;
            }
            if (found == null) {
                return null;
            }
            at = found;
        }
        return at;
    }

    /**
     * Les mille sept cent quatre-vingt-douze octets de la grille d'eclairage des sprites.
     *
     * <p>Le fichier s'appelle {@code guff} et la source elle-meme avoue n'en rien savoir :
     * {@code ; todo - what is this?}. C'est cent douze lignes de seize octets — sept colonnes
     * de sept, pour seize positions du point le plus clair —, et c'est de la que sort la
     * tache de lumiere qu'une image eclairee porte sur elle.
     *
     * <p>Sa taille dit son usage mieux qu'un commentaire : le pas vertical est de cent douze
     * octets et l'indice qui le multiplie va de zero a quinze ; quinze fois cent douze, plus
     * six fois seize, plus quinze, font mille sept cent quatre-vingt-onze. Le dernier octet du
     * fichier, et pas un de plus.
     */
    public static synchronized byte[] guff() {
        if (guff == null) {
            Path file = local().resolve("incbin").resolve("includes").resolve("guff");
            try {
                guff = java.nio.file.Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException("grille d'eclairage introuvable : " + file, e);
            }
        }
        return guff;
    }

    /** Les huit images de l'ondulation de l'eau. */
    public static synchronized WaterFrames water() {
        if (water == null) {
            Path file = local().resolve("incbin").resolve("waterfile");
            try {
                water = WaterFrames.read(file);
            } catch (IOException e) {
                throw new UncheckedIOException("images d'eau introuvables : " + file, e);
            }
        }
        return water;
    }

    /**
     * L'image du ciel.
     *
     * <p>Elle ne vit pas avec le depot mais avec les disquettes, et son nom dit "packed"
     * alors qu'elle ne l'est pas : six cent quarante-huit fois deux cent quarante octets, tels
     * quels.
     */
    public static synchronized Backdrop sky() {
        if (sky == null) {
            Path file = asset("ab3:includes/rawbackpacked");
            if (file == null) {
                throw new IllegalStateException("image du ciel introuvable");
            }
            try {
                sky = Backdrop.read(file);
            } catch (IOException e) {
                throw new UncheckedIOException("image du ciel illisible : " + file, e);
            }
        }
        return sky;
    }

    /**
     * Une feuille de sprites, par son rang dans la base de liaison.
     *
     * <p>Trois fichiers du meme nom, d'extensions differentes. Le nom que la base donne n'en
     * porte aucune : c'est le chargeur qui les ajoute, et c'est ce qu'on refait ici.
     */
    /**
     * Un modele en relief, designe par son rang dans la base de liaison.
     *
     * <p>Plusieurs rangs peuvent nommer le MEME fichier — trois d'entre eux sont la clef de
     * passe — et chacun garde alors sa propre lecture, comme le jeu qui charge le fichier
     * autant de fois qu'il est nomme.
     */
    public static synchronized VectorModel model(int index) {
        return MODELS.computeIfAbsent(index, k -> {
            String nom = database().vectorModels().get(k).file().trim();
            Path f = asset(nom);
            if (f == null) {
                throw new IllegalStateException("modele introuvable : " + nom);
            }
            try {
                return VectorModel.read(f, database().vectorModels().get(k).name());
            } catch (IOException e) {
                throw new UncheckedIOException("modele illisible : " + nom, e);
            }
        });
    }

    public static synchronized SpriteSheet sheet(int index) {
        return SHEETS.computeIfAbsent(index, k -> {
            String nom = database().spriteSheets().get(k).file().trim();
            Path wad = asset(nom + ".wad");
            Path ptr = asset(nom + ".ptr");
            Path pal = asset(nom + ".256pal");
            if (wad == null || ptr == null || pal == null) {
                throw new IllegalStateException("feuille de sprites incomplete : " + nom);
            }
            try {
                return SpriteSheet.read(wad, ptr, pal);
            } catch (IOException e) {
                throw new UncheckedIOException("feuille illisible : " + nom, e);
            }
        });
    }

    /**
     * Les deux cent cinquante-six couleurs de l'ecran.
     *
     * <p>Tout le rendu travaille en rangs ; la couleur n'apparait qu'au moment de montrer
     * l'image.
     */
    public static synchronized Palette palette() {
        if (palette == null) {
            Path file = asset("EDIT/Includes/256pal");
            if (file == null) {
                throw new IllegalStateException("palette d'ecran introuvable");
            }
            try {
                palette = Palette.read(file);
            } catch (IOException e) {
                throw new UncheckedIOException("palette illisible : " + file, e);
            }
        }
        return palette;
    }

    /** La table de sinus. */
    public static synchronized SinCos sinCos() {
        if (sinCos == null) {
            sinCos = SinCos.read(local());
        }
        return sinCos;
    }
}
