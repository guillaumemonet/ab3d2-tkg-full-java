package ab3d2.full.defs;

import ab3d2.full.Blob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lit {@code test.lnk} et en fait des objets.
 *
 * <p>Le fichier ne se decrit pas lui-meme : il n'a ni en-tete, ni compteurs, ni offsets. Ce sont
 * les CONSTANTES DU CODE qui disent ou commence chaque table et combien d'entrees elle porte, et
 * le jeu s'en remet a ce que l'editeur et le moteur aient ete compiles avec les memes. Les
 * valeurs ci-dessous viennent donc du source, pas du fichier — et elles tombent juste : la
 * derniere table finit exactement sur le dernier octet.
 */
public final class DatabaseReader {

    // --- combien d'entrees par table ---
    private static final int NUM_LEVELS = 16;
    private static final int NUM_WALL_TEXTURES = 16;
    private static final int NUM_OBJECT_DEFS = 30;
    private static final int FRAMES_PER_SHEET = 32;
    private static final int NUM_ALIEN_DEFS = 20;
    private static final int NUM_GUN_DEFS = 10;
    private static final int NUM_BULLET_DEFS = 20;
    private static final int NUM_SFX = 64;
    private static final int NUM_FLOOR_TYPES = 16;
    private static final int NUM_AMBIENT_SFX = 16;
    /** Les consommables de l'inventaire : un par projectile, plus la sante et le carburant. */
    private static final int NUM_INVENTORY_CONSUMABLES = NUM_BULLET_DEFS + 2;
    /** Les objets de l'inventaire : les armes, plus deux. */
    private static final int NUM_INVENTORY_ITEMS = NUM_GUN_DEFS + 2;

    // --- ou commence chaque table ---
    private static final int LEVEL_NAMES = 64;
    private static final int OBJ_GFX_NAMES = 704;
    private static final int SFX_FILENAMES = 2624;
    private static final int FLOOR_FILENAME = 6464;
    private static final int TEXTURE_FILENAME = 6528;
    private static final int BULLET_DEFS = 6848;
    private static final int BULLET_NAMES = 12848;
    private static final int GUN_NAMES = 13248;
    private static final int SHOOT_DEFS = 13448;
    private static final int ALIEN_NAMES = 13528;
    private static final int ALIEN_DEFS = 13928;
    private static final int FRAME_DATA = 14768;
    private static final int OBJECT_NAMES = 22448;
    private static final int OBJECT_DEFS = 23048;
    private static final int OBJECT_IDLE_ANIMS = 24248;
    private static final int OBJECT_ACTIVE_ANIMS = 27848;
    private static final int AMMO_GIVE = 31448;
    private static final int GUN_GIVE = 32768;
    private static final int ALIEN_ANIMS = 33488;
    private static final int VECTOR_NAMES = 81888;
    private static final int WALL_GFX_NAMES = 83808;
    private static final int ALIEN_BRIGHTS = 84864;
    private static final int GUN_OBJECTS = 84904;
    private static final int PLAYER1_GRAPHIC = 84924;
    private static final int PLAYER2_GRAPHIC = 84926;
    private static final int FLOOR_DATA = 84928;
    private static final int ALIEN_SHOOT_DEFS = 84992;
    private static final int AMBIENT_SFX = 85152;
    private static final int LEVEL_MUSIC = 85184;

    // --- la taille d'une entree ---
    private static final int NAME_LENGTH = 20;
    private static final int FILENAME_LENGTH = 64;
    private static final int LEVEL_NAME_LENGTH = 40;
    /**
     * Le pas d'un nom de BRUITAGE : soixante-quatre octets, et non soixante.
     *
     * <p>La declaration de {@code defs.i} dit soixante ({@code NUM_SFX*60}), et elle a tort :
     * le chargeur avance de soixante-quatre ({@code adda.w #64,a0}, res.s), et les donnees sont
     * bien espacees de soixante-quatre. A soixante, les noms derivent de quatre octets par
     * entree et ne sont plus que des fragments.
     *
     * <p>Consequence de cette discordance : les quatre dernieres entrees DEBORDENT sur les noms
     * de fichiers qui suivent. Le bruitage soixante est le pave de sol, le soixante-et-unieme
     * les cartes de textures. Le jeu les charge donc comme des echantillons, et personne ne les
     * joue jamais.
     */
    private static final int SFX_NAME_LENGTH = 64;
    private static final int OBJECT_DEF_SIZE = 40;
    private static final int ALIEN_DEF_SIZE = 42;
    private static final int SHOOT_SIZE = 8;
    private static final int BULLET_DEF_SIZE = 300;

    /** Un script d'animation : vingt pas de six octets, qu'il les utilise tous ou non. */
    private static final int ANIM_STEPS = 20;
    private static final int ANIM_STEP_SIZE = 6;
    private static final int ANIM_SIZE = ANIM_STEPS * ANIM_STEP_SIZE;

    /** Les animations d'un monstre : onze options de vingt images de onze octets. */
    private static final int ALIEN_OPTIONS = 11;
    private static final int ALIEN_FRAMES = 20;
    private static final int ALIEN_FRAME_SIZE = 11;
    private static final int ALIEN_OPTION_SIZE = ALIEN_FRAMES * ALIEN_FRAME_SIZE;
    private static final int ALIEN_ANIM_SIZE = ALIEN_OPTIONS * ALIEN_OPTION_SIZE;

    // --- dans la definition d'un objet ---
    private static final int ODEF_BEHAVIOUR = 0;
    private static final int ODEF_GFX_TYPE = 2;
    private static final int ODEF_ACTIVE_TIMEOUT = 4;
    private static final int ODEF_HIT_POINTS = 6;
    private static final int ODEF_COLLIDE_RADIUS = 14;
    private static final int ODEF_COLLIDE_HEIGHT = 16;
    private static final int ODEF_FLOOR_CEILING = 18;
    private static final int ODEF_LOCK_TO_WALL = 20;
    private static final int ODEF_SFX = 24;

    // --- dans la definition d'un monstre ---
    private static final int ALIEN_GFX_TYPE = 0;
    private static final int ALIEN_DEFAULT_BEHAVIOUR = 2;
    private static final int ALIEN_REACTION_TIME = 4;
    private static final int ALIEN_DEFAULT_SPEED = 6;
    private static final int ALIEN_RESPONSE_BEHAVIOUR = 8;
    private static final int ALIEN_RESPONSE_SPEED = 10;
    private static final int ALIEN_RESPONSE_TIMEOUT = 12;
    private static final int ALIEN_DAMAGE_TO_RETREAT = 14;
    private static final int ALIEN_DAMAGE_TO_FOLLOWUP = 16;
    private static final int ALIEN_FOLLOWUP_BEHAVIOUR = 18;
    private static final int ALIEN_FOLLOWUP_SPEED = 20;
    private static final int ALIEN_FOLLOWUP_TIMEOUT = 22;
    private static final int ALIEN_RETREAT_BEHAVIOUR = 24;
    private static final int ALIEN_RETREAT_SPEED = 26;
    private static final int ALIEN_RETREAT_TIMEOUT = 28;
    private static final int ALIEN_BUL_TYPE = 30;
    private static final int ALIEN_HIT_POINTS = 32;
    private static final int ALIEN_HEIGHT = 34;
    private static final int ALIEN_GIRTH = 36;
    private static final int ALIEN_SPLAT_TYPE = 38;
    private static final int ALIEN_AUXILIARY = 40;

    // --- dans la definition d'un projectile ---
    private static final int BUL_HIT_SCAN = 0;
    private static final int BUL_GRAVITY = 4;
    private static final int BUL_LIFETIME = 8;
    private static final int BUL_AMMO_IN_CLIP = 12;
    private static final int BUL_BOUNCE_HORIZ = 16;
    private static final int BUL_BOUNCE_VERT = 20;
    private static final int BUL_HIT_DAMAGE = 24;
    private static final int BUL_EXPLOSIVE_FORCE = 28;
    private static final int BUL_SPEED = 32;
    private static final int BUL_ANIM_FRAMES = 36;
    private static final int BUL_POP_FRAMES = 40;
    private static final int BUL_BOUNCE_SFX = 44;
    private static final int BUL_IMPACT_SFX = 48;
    private static final int BUL_GRAPHIC_TYPE = 52;
    private static final int BUL_IMPACT_GRAPHIC_TYPE = 56;
    private static final int BUL_ANIM_DATA = 60;
    private static final int BUL_POP_DATA = 180;

    private DatabaseReader() {
    }

    /**
     * Lit la base depuis l'arborescence des donnees d'origine.
     *
     * @param mediaRoot racine des donnees depackees, celle qui contient {@code Includes/}
     */
    public static Database read(Path mediaRoot) throws IOException {
        Path f = mediaRoot.resolve("Includes").resolve("test.lnk");
        return parse(new Blob(Files.readAllBytes(f), f.toString()));
    }

    /** Variante pour un fichier deja en memoire. */
    public static Database parse(Blob f) {
        List<Database.LevelInfo> levels = new ArrayList<>(NUM_LEVELS);
        for (int i = 0; i < NUM_LEVELS; i++) {
            levels.add(new Database.LevelInfo(i, (char) ('a' + i),
                    f.text(LEVEL_NAMES + i * LEVEL_NAME_LENGTH, LEVEL_NAME_LENGTH),
                    f.text(LEVEL_MUSIC + i * FILENAME_LENGTH, FILENAME_LENGTH)));
        }

        List<String> sounds = new ArrayList<>(NUM_SFX);
        for (int i = 0; i < NUM_SFX; i++) {
            sounds.add(f.text(SFX_FILENAMES + i * SFX_NAME_LENGTH, SFX_NAME_LENGTH));
        }

        List<Integer> ambient = new ArrayList<>(NUM_AMBIENT_SFX);
        for (int i = 0; i < NUM_AMBIENT_SFX; i++) {
            ambient.add(f.s16(AMBIENT_SFX + i * 2));
        }

        List<Database.FloorType> floors = new ArrayList<>(NUM_FLOOR_TYPES);
        for (int i = 0; i < NUM_FLOOR_TYPES; i++) {
            floors.add(new Database.FloorType(i,
                    f.s16(FLOOR_DATA + i * 4), f.s16(FLOOR_DATA + i * 4 + 2)));
        }

        List<Database.Shoot> alienShots = new ArrayList<>(NUM_ALIEN_DEFS);
        for (int i = 0; i < NUM_ALIEN_DEFS; i++) {
            alienShots.add(readShoot(f, ALIEN_SHOOT_DEFS + i * SHOOT_SIZE));
        }

        return new Database(
                List.copyOf(levels),
                resources(f, WALL_GFX_NAMES, NUM_WALL_TEXTURES),
                f.text(FLOOR_FILENAME, FILENAME_LENGTH),
                f.text(TEXTURE_FILENAME, FILENAME_LENGTH),
                resources(f, OBJ_GFX_NAMES, NUM_OBJECT_DEFS),
                readFrames(f),
                resources(f, VECTOR_NAMES, NUM_OBJECT_DEFS),
                readObjects(f),
                readAliens(f),
                readGuns(f),
                List.copyOf(alienShots),
                readBullets(f),
                List.copyOf(floors),
                List.copyOf(ambient),
                List.copyOf(sounds),
                f.s16(PLAYER1_GRAPHIC),
                f.s16(PLAYER2_GRAPHIC));
    }

    /**
     * Les tables d'images : trente feuilles de trente-deux images.
     *
     * <p>Le rang de feuille que porte une chose a dessiner sert d'index ici comme dans la
     * table des fichiers : les deux avancent du meme pas.
     */
    private static List<List<Database.Frame>> readFrames(Blob f) {
        List<List<Database.Frame>> out = new ArrayList<>();
        for (int g = 0; g < NUM_OBJECT_DEFS; g++) {
            List<Database.Frame> une = new ArrayList<>();
            for (int i = 0; i < FRAMES_PER_SHEET; i++) {
                int at = FRAME_DATA + g * FRAMES_PER_SHEET * 8 + i * 8;
                une.add(new Database.Frame(f.u16(at), f.u16(at + 2), f.u16(at + 4),
                        f.u16(at + 6)));
            }
            out.add(List.copyOf(une));
        }
        return List.copyOf(out);
    }

    /** Une table de noms de fichiers, un par rang. */
    private static List<Database.Resource> resources(Blob f, int at, int count) {
        List<Database.Resource> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new Database.Resource(i, f.text(at + i * FILENAME_LENGTH, FILENAME_LENGTH)));
        }
        return List.copyOf(out);
    }

    private static List<Database.ObjectDef> readObjects(Blob f) {
        List<Database.ObjectDef> out = new ArrayList<>(NUM_OBJECT_DEFS);
        for (int i = 0; i < NUM_OBJECT_DEFS; i++) {
            int d = OBJECT_DEFS + i * OBJECT_DEF_SIZE;
            out.add(new Database.ObjectDef(i,
                    f.text(OBJECT_NAMES + i * NAME_LENGTH, NAME_LENGTH),
                    f.s16(d + ODEF_BEHAVIOUR),
                    f.s16(d + ODEF_GFX_TYPE),
                    f.s16(d + ODEF_ACTIVE_TIMEOUT),
                    f.s16(d + ODEF_HIT_POINTS),
                    f.s16(d + ODEF_COLLIDE_RADIUS),
                    f.s16(d + ODEF_COLLIDE_HEIGHT),
                    f.s16(d + ODEF_FLOOR_CEILING),
                    f.s16(d + ODEF_LOCK_TO_WALL),
                    f.s16(d + ODEF_SFX),
                    words(f, AMMO_GIVE + i * NUM_INVENTORY_CONSUMABLES * 2,
                            NUM_INVENTORY_CONSUMABLES),
                    words(f, GUN_GIVE + i * NUM_INVENTORY_ITEMS * 2, NUM_INVENTORY_ITEMS),
                    // Le mot de TROP, celui que le test d'inventaire lit au-dela de la table.
                    f.u16(GUN_GIVE + i * NUM_INVENTORY_ITEMS * 2 + NUM_INVENTORY_ITEMS * 2),
                    readAnim(f, OBJECT_IDLE_ANIMS + i * ANIM_SIZE),
                    readAnim(f, OBJECT_ACTIVE_ANIMS + i * ANIM_SIZE)));
        }
        return List.copyOf(out);
    }

    private static List<Database.AlienDef> readAliens(Blob f) {
        List<Database.AlienDef> out = new ArrayList<>(NUM_ALIEN_DEFS);
        for (int i = 0; i < NUM_ALIEN_DEFS; i++) {
            int d = ALIEN_DEFS + i * ALIEN_DEF_SIZE;
            out.add(new Database.AlienDef(i,
                    f.text(ALIEN_NAMES + i * NAME_LENGTH, NAME_LENGTH),
                    f.s16(d + ALIEN_GFX_TYPE),
                    f.s16(ALIEN_BRIGHTS + i * 2),
                    f.s16(d + ALIEN_HIT_POINTS),
                    f.s16(d + ALIEN_HEIGHT),
                    f.s16(d + ALIEN_GIRTH),
                    f.s16(d + ALIEN_BUL_TYPE),
                    f.s16(d + ALIEN_SPLAT_TYPE),
                    f.s16(d + ALIEN_AUXILIARY),
                    f.s16(d + ALIEN_REACTION_TIME),
                    f.s16(d + ALIEN_DEFAULT_BEHAVIOUR),
                    f.s16(d + ALIEN_DEFAULT_SPEED),
                    f.s16(d + ALIEN_RESPONSE_BEHAVIOUR),
                    f.s16(d + ALIEN_RESPONSE_SPEED),
                    f.s16(d + ALIEN_RESPONSE_TIMEOUT),
                    f.s16(d + ALIEN_FOLLOWUP_BEHAVIOUR),
                    f.s16(d + ALIEN_FOLLOWUP_SPEED),
                    f.s16(d + ALIEN_FOLLOWUP_TIMEOUT),
                    f.s16(d + ALIEN_RETREAT_BEHAVIOUR),
                    f.s16(d + ALIEN_RETREAT_SPEED),
                    f.s16(d + ALIEN_RETREAT_TIMEOUT),
                    f.s16(d + ALIEN_DAMAGE_TO_RETREAT),
                    f.s16(d + ALIEN_DAMAGE_TO_FOLLOWUP),
                    readAlienAnims(f, ALIEN_ANIMS + i * ALIEN_ANIM_SIZE)));
        }
        return List.copyOf(out);
    }

    private static List<Database.Gun> readGuns(Blob f) {
        List<Database.Gun> out = new ArrayList<>(NUM_GUN_DEFS);
        for (int i = 0; i < NUM_GUN_DEFS; i++) {
            out.add(new Database.Gun(i,
                    f.text(GUN_NAMES + i * NAME_LENGTH, NAME_LENGTH),
                    readShoot(f, SHOOT_DEFS + i * SHOOT_SIZE),
                    f.s16(GUN_OBJECTS + i * 2)));
        }
        return List.copyOf(out);
    }

    private static Database.Shoot readShoot(Blob f, int at) {
        return new Database.Shoot(f.s16(at), f.s16(at + 2), f.s16(at + 4), f.s16(at + 6));
    }

    private static List<Database.BulletDef> readBullets(Blob f) {
        List<Database.BulletDef> out = new ArrayList<>(NUM_BULLET_DEFS);
        for (int i = 0; i < NUM_BULLET_DEFS; i++) {
            int b = BULLET_DEFS + i * BULLET_DEF_SIZE;
            out.add(new Database.BulletDef(i,
                    f.text(BULLET_NAMES + i * NAME_LENGTH, NAME_LENGTH),
                    f.s32(b + BUL_HIT_SCAN),
                    f.s32(b + BUL_GRAVITY),
                    f.s32(b + BUL_LIFETIME),
                    f.s32(b + BUL_AMMO_IN_CLIP),
                    f.s32(b + BUL_BOUNCE_HORIZ),
                    f.s32(b + BUL_BOUNCE_VERT),
                    f.s32(b + BUL_HIT_DAMAGE),
                    f.s32(b + BUL_EXPLOSIVE_FORCE),
                    f.s32(b + BUL_SPEED),
                    f.s32(b + BUL_ANIM_FRAMES),
                    f.s32(b + BUL_POP_FRAMES),
                    f.s32(b + BUL_BOUNCE_SFX),
                    f.s32(b + BUL_IMPACT_SFX),
                    f.s32(b + BUL_GRAPHIC_TYPE),
                    f.s32(b + BUL_IMPACT_GRAPHIC_TYPE),
                    readAnim(f, b + BUL_ANIM_DATA),
                    readAnim(f, b + BUL_POP_DATA)));
        }
        return List.copyOf(out);
    }

    /** Un script d'animation d'objet ou de projectile : toujours vingt pas, lus en entier. */
    private static List<Database.AnimStep> readAnim(Blob f, int at) {
        List<Database.AnimStep> out = new ArrayList<>(ANIM_STEPS);
        for (int k = 0; k < ANIM_STEPS; k++) {
            int s = at + k * ANIM_STEP_SIZE;
            out.add(new Database.AnimStep(f.s8(s), f.u8(s + 1), f.s16(s + 2),
                    f.s8(s + 4), f.u8(s + 5)));
        }
        return List.copyOf(out);
    }

    /**
     * Les onze options d'animation d'un monstre.
     *
     * <p>Chacune s'arrete a son terminateur, qu'on GARDE : c'est lui qui dit ou l'animation
     * reboucle. Faute de terminateur, on s'arrete au vingtieme.
     */
    private static List<List<Database.AlienFrame>> readAlienAnims(Blob f, int at) {
        List<List<Database.AlienFrame>> out = new ArrayList<>(ALIEN_OPTIONS);
        for (int opt = 0; opt < ALIEN_OPTIONS; opt++) {
            int base = at + opt * ALIEN_OPTION_SIZE;
            List<Database.AlienFrame> frames = new ArrayList<>();
            for (int i = 0; i < ALIEN_FRAMES; i++) {
                int s = base + i * ALIEN_FRAME_SIZE;
                frames.add(new Database.AlienFrame(f.s8(s), f.s8(s + 1), f.s16(s + 2),
                        f.u8(s + 5), f.u8(s + 6), f.u8(s + 7),
                        f.s8(s + 8), f.s8(s + 9), f.s8(s + 10)));
                if (f.s8(s) < 0) {
                    break;
                }
            }
            out.add(List.copyOf(frames));
        }
        return List.copyOf(out);
    }

    /** Une suite de mots non signes. */
    private static List<Integer> words(Blob f, int at, int count) {
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(f.u16(at + i * 2));
        }
        return List.copyOf(out);
    }
}
