package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.level.VectorModel;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Les points d'un modele en relief : tournes, projetes, et eclaires.
 *
 * <p>Trois choses arrivent ici, dans cet ordre, et chacune a besoin de la precedente.
 *
 * <h2>La grille d'eclairage</h2>
 *
 * <p>{@link BrightRings} a dit d'ou vient la lumiere en seize directions, au sol et au
 * plafond. Cela ne suffit pas a une face qui regarde vers le haut : il faut aussi savoir ce
 * qu'elle recoit d'EN HAUT. On fabrique donc une grille de seize par seize — seize elevations
 * par seize azimuts — en interpolant entre les deux anneaux.
 *
 * <p>Le centre de la grille, les rangs trois a dix, va du sol vers le plafond. Au-dessus, les
 * rangs deux a zero vont vers l'anneau OPPOSE, celui de la direction d'en face, parce qu'une
 * face tournee vers le haut voit l'autre cote de la piece. En dessous, les rangs onze a
 * quatorze font de meme pour le plafond. **Le rang quinze n'est jamais ecrit** : il vaut zero,
 * et c'est ce qui donne la pleine lumiere aux points dont la normale est {@code $ff}.
 *
 * <h2>La rotation</h2>
 *
 * <p>L'objet tourne sur lui-meme par rapport a la vue : son angle, moins un quart de tour,
 * moins l'angle du regard. L'abscisse et la profondeur tournent ; la hauteur, non — un modele
 * ne bascule jamais. L'abscisse garde neuf bits de fraction, la hauteur six, la profondeur
 * aucune : trois echelles differentes, et c'est voulu.
 *
 * <h2>Le rang quinze, et les quarante-deux pour cent</h2>
 *
 * <p>Sur les treize mille huit cent quatre-vingt-trois normales des vingt modeles, toutes poses
 * confondues, **cinq mille huit cent quatre-vingt-onze — quarante-deux pour cent — designent le
 * rang quinze**, celui que la grille n'ecrit jamais. Quatre modeles le font pour TOUS leurs
 * points : le generateur, la boite a lueur, l'interrupteur, le chargeur. Ils ne s'ombrent
 * jamais, et c'est voulu : {@code $ff} est la facon d'ecrire « pleine lumiere ».
 *
 * <h2>La projection</h2>
 *
 * <p>La profondeur de l'objet est multipliee par TROIS, et les abscisses par cinq tiers. C'est
 * la correction d'aspect du plein ecran, et le code d'origine la commente d'un aveu : si on la
 * corrige proprement, les modeles se mettent a nager par rapport au decor.
 *
 * <p>Deux divisions, et deux chemins pour les faire. Tant que la profondeur tient sous un tiers
 * de mot, on multiplie par cinq et l'on divise par trois fois la profondeur. Au-dela cela
 * deborderait, et le jeu retombe sur son ancienne recette — un facteur approche de trois virgule
 * trois, pris en deux temps pour ne pas deborder non plus.
 *
 * <h2>Ce que l'epreuve ne separe pas</h2>
 *
 * <p>Cinquante-et-une mutations, quarante-neuf mordent. Les deux qui survivent sont la meme :
 * les anneaux et la grille sont lus dans [0, 45] et pas une valeur n'y est NEGATIVE, sur deux
 * cent trente-six mille points. C'est une consequence de la soustraction finale des anneaux,
 * qui ne garde que des restes negatifs et les NIE : ce qui en sort ne peut pas etre negatif.
 * Les deux gardes — celle de la grille, celle de la luminosite — sont portees comme l'original
 * les ecrit, et mesurees plutot qu'excusees. La butee HAUTE, elle, sert : la grille monte a
 * quarante-cinq et la luminosite s'arrete a trente-et-un.
 *
 * <p>Un point derriere l'oeil recoit {@code 32767} dans les deux coordonnees. Un point au-dela
 * de seize mille trois cent quatre-vingt-trois fait ABANDONNER l'objet entier, au milieu de la
 * boucle : les points deja projetes restent, les autres gardent ce que l'objet precedent y
 * avait laisse, et rien n'est dessine.
 */
public final class ModelPoints {

    /** Seize elevations, seize azimuts. */
    public static final int RING = BrightRings.RING;

    /** Au-dela de cette profondeur, l'objet entier est abandonne. */
    public static final int MAX_Z = 16383;

    /** Le facteur approche de trois virgule trois, sur mille vingt-quatre. */
    private static final int SLOW = 3413;

    private final SinCos sinCos;

    /** Seize rangs de seize : l'elevation en nibble haut, l'azimut en nibble bas. */
    private final byte[] grid = new byte[RING * RING];

    private int[] x3 = new int[0];
    private int[] y3 = new int[0];
    private int[] z3 = new int[0];
    private int[] screenX = new int[0];
    private int[] screenY = new int[0];
    private int[] bright = new int[0];
    private int count;
    private boolean abandoned;
    private int oddAngles;

    public ModelPoints(SinCos sinCos) {
        this.sinCos = sinCos;
    }

    /** Combien de points ce modele porte. */
    public int count() {
        return count;
    }

    /** Vrai si l'objet a ete abandonne en cours de projection : trop loin. */
    public boolean abandoned() {
        return abandoned;
    }

    /** La grille d'eclairage, seize par seize. */
    public byte[] grid() {
        return grid;
    }

    /** L'abscisse tournee du point, avant perspective. */
    public int x3(int i) {
        return x3[i];
    }

    /** La hauteur tournee du point. */
    public int y3(int i) {
        return y3[i];
    }

    /** La profondeur du point, en mot : c'est elle qui sert au tri des faces. */
    public int z3(int i) {
        return z3[i];
    }

    /** La colonne du point a l'ecran, ou {@code 32767} s'il est derriere. */
    public int screenX(int i) {
        return screenX[i];
    }

    /** La ligne du point a l'ecran, ou {@code 32767} s'il est derriere. */
    public int screenY(int i) {
        return screenY[i];
    }

    /** La luminosite du point, de zero (pleine lumiere) a trente-et-un. */
    public int bright(int i) {
        return bright[i];
    }

    /**
     * Combien de fois l'angle de rotation est tombe sur un demi-pas de la table.
     *
     * <p>Le jeu y lirait un mot a une adresse IMPAIRE, c'est-a-dire a cheval sur deux entrees.
     * Compte pour savoir si cela arrive.
     */
    public int oddAngles() {
        return oddAngles;
    }

    /**
     * Fabrique la grille d'eclairage a partir des anneaux.
     *
     * <p>Port de {@code MYacross} / {@code TOPPART} / {@code BOTPART} (objdrawhires.s:1726).
     */
    public void buildGrid(BrightRings rings) {
        byte[] ring = rings.rings();
        int d6 = 8;                                    // move.w #8,d6
        for (int column = 0; column < RING; column++) { // MYacross (dbra d7)
            // ---- le centre : du sol vers le plafond, rangs 3 a 10 ----
            int d4 = clamp(ring[RING + d6]);           // move.b 16(a0,d6.w),d4 ; bge ; moveq #0
            int d3 = clamp(ring[d6]);                  // move.b (a0,d6.w),d3  ; bge ; moveq #0
            d4 = setw(d4, d4 - d3);                    // sub.w d3,d4
            d3 = swap(d3);                             // swap d3
            d4 = swap(d4);                             // swap d4
            d4 = d4 >> 3;                              // asr.l #3,d4
            int d5 = 3 * RING;                         // moveq #3*16,d5
            for (int d2 = 7; d2 >= 0; d2--) {          // .down
                grid[d5 + column] = (byte) (d3 >>> 16); // swap d3 ; move.b d3,(a1,d5.w)
                d5 = s16(setw(d5, d5 + RING));         // add.w #16,d5
                d3 = d3 + d4;                          // add.l d4,d3
            }

            // ---- TOPPART : rangs 3 a 0, vers l'anneau d'EN FACE, de demi-pas ----
            d6 = d6 ^ 8;                               // bchg #3,d6
            d4 = clamp(ring[d6]);                      // move.b (a0,d6.w),d4
            d6 = d6 ^ 8;                               // bchg #3,d6
            d3 = clamp(ring[d6]);                      // move.b (a0,d6.w),d3
            d4 = setw(d4, d4 - d3);                    // sub.w d3,d4
            d3 = swap(d3);
            d4 = swap(d4);
            d4 = d4 >> 4;                              // asr.l #4,d4 : /8 puis la moitie
            d5 = 3 * RING;                             // moveq #3*16,d5
            for (int d2 = 3; d2 >= 0; d2--) {          // .down
                grid[d5 + column] = (byte) (d3 >>> 16);
                d5 = s16(setw(d5, d5 - RING));         // sub.w #16,d5
                d3 = d3 + d4;
            }

            // ---- BOTPART : rangs 11 a 14, de meme pour le plafond ----
            d6 = d6 ^ 8;                               // bchg #3,d6
            d4 = clamp(ring[RING + d6]);               // move.b 16(a0,d6.w),d4
            d6 = d6 ^ 8;                               // bchg #3,d6
            d3 = clamp(ring[RING + d6]);               // move.b 16(a0,d6.w),d3
            d4 = setw(d4, d4 - d3);                    // sub.w d3,d4
            d3 = swap(d3);
            d4 = swap(d4);
            d4 = d4 >> 4;                              // asr.l #4,d4
            d5 = 11 * RING;                            // move.w #11*16,d5
            for (int d2 = 3; d2 >= 0; d2--) {          // .down
                grid[d5 + column] = (byte) (d3 >>> 16);
                d5 = s16(setw(d5, d5 + RING));         // add.w #16,d5
                d3 = d3 + d4;
            }

            d6 = (d6 - 1) & 0xF;                       // subq #1,d6 ; and.w #$f,d6
        }
        // Le rang quinze n'est jamais ecrit : il reste a zero, la pleine lumiere.
    }

    /** Une valeur d'anneau, les negatives ramenees a zero. */
    private static int clamp(byte v) {
        return v < 0 ? 0 : v;                          // bge.s .okp ; moveq #0,dn
    }

    /**
     * Tourne, projette et eclaire tous les points d'une pose.
     *
     * <p>Port de {@code rotate_object} (1895), {@code fullscreen_conv} (1932) et
     * {@code .calc_point_angle_brightness_loop} (2072). Seul le PLEIN ECRAN est porte, comme
     * partout ailleurs dans ce depot.
     *
     * @param model       le modele
     * @param frameIndex  la pose
     * @param objectAngle l'orientation de l'objet dans le monde
     * @param viewAngle   l'orientation du regard
     * @param worldX      l'abscisse de l'objet vue du regard, a l'echelle du decor
     * @param depth       la profondeur de l'objet vue du regard
     * @param objY        la hauteur de l'objet dans le monde
     * @param eyeY        la hauteur de l'oeil, a son echelle de seize bits
     * @param centreX     le milieu de l'ecran
     * @param centreY     le milieu de l'ecran, celui des modeles
     * @return vrai si l'objet tient en entier ; faux s'il a fallu l'abandonner
     */
    public boolean project(VectorModel model, int frameIndex, int objectAngle, int viewAngle,
            int worldX, int depth, int objY, int eyeY, int centreX, int centreY) {
        VectorModel.Frame frame = model.frames().get(frameIndex);
        count = model.pointCount();
        if (x3.length < count) {
            x3 = new int[count];
            y3 = new int[count];
            z3 = new int[count];
            screenX = new int[count];
            screenY = new int[count];
            bright = new int[count];
        }
        abandoned = false;

        // ---- rotate_object (1895) ----
        int d2 = s16(objectAngle);                     // move.w draw_ObjectAng_w,d2
        d2 = s16(d2 - 2048);                           // sub.w #2048,d2
        d2 = s16(d2 - s16(viewAngle));                 // sub.w Vis_AngPos_w,d2
        d2 = d2 & (SinCos.MASK | 1);                   // AMOD_I d2
        if ((d2 & 1) != 0) {
            oddAngles++;
        }
        int d6 = sinCos.value(d2);                     // move.w (a5),d6     (sinus)
        int d7 = sinCos.value(d2 + SinCos.COSINE_OFFSET); // move.w COSINE_OFS(a5),d7 (cosinus)

        for (int i = 0; i < count; i++) {
            int px = frame.x()[i];                     // move.w (a3),d2
            int py = frame.y()[i];                     // move.w 2(a3),d3
            int pz = frame.z()[i];                     // move.w 4(a3),d4
            int a = muls(pz, d7);                      // muls d7,d4  (z*cos)
            int b = muls(px, d6);                      // muls d6,d2  (x*sin)
            x3[i] = (b - a) >> 8 >> 1;                 // sub.l d4,d2 ; asr.l #8 ; asr.l #1
            y3[i] = s16(py) << 6;                      // ext.l d3 ; asl.l #6,d3
            int c = muls(pz, d6);                      // muls d6,d4  (z*sin)
            int d = muls(px, d7);                      // muls d7,d2  (x*cos)
            z3[i] = s16(swap(c + d));                  // add.l d2,d4 ; swap d4 ; move.w d4,(a4)+
        }

        // ---- fullscreen_conv (1932) ----
        int d1 = s16(depth);                           // d1 = la profondeur de l'objet
        int d3w = s16(d1);                             // move.w d1,d3
        d1 = s16(d1 + d1);                             // add.w d1,d1
        d1 = s16(d1 + d3w);                            // add.w d3,d1   (fois trois)
        int dy = s16(objY) << 7;                       // ext.l d2 ; asl.l #7,d2
        dy = dy - eyeY;                                // sub.l Plr_YOff_l,d2
        dy = dy + dy;                                  // add.l d2,d2
        int dx = worldX + worldX;                      // add.l d0,d0

        for (int i = 0; i < count; i++) {              // .convert_to_screen
            int d3 = x3[i] + dx;                       // move.l (a2),d3 ; add.l d0,d3
            x3[i] = d3;                                // move.l d3,(a2)+
            int d4 = y3[i] + dy;                       // move.l (a2),d4 ; add.l d2,d4
            y3[i] = d4;                                // move.l d4,(a2)+
            int d5 = s16(setw(z3[i], z3[i] + d1));     // move.w (a2),d5 ; add.w d1,d5
            if (d5 <= 0) {                             // ble .point_behind
                z3[i] = d5;                            // move.w d5,(a2)+
                screenX[i] = 32767;                    // move.l #$7fff7fff,(a3)+
                screenY[i] = 32767;
                continue;
            }
            if (d5 > MAX_Z) {                          // cmp.w #DRAW_VECTOR_MAX_Z,d5 ; bgt
                abandoned = true;                      // bra no_more_parts
                return false;
            }
            z3[i] = d5;                                // move.w d5,(a2)+
            if (d5 <= 32767 / 3) {                     // cmp.w #32767/3,d5 ; bgt .old_scaler
                int d6b = s16(d5);                     // move.w d5,d6
                int d5b = s16(d5 + d5);                // add.w d5,d5  (deux fois)
                d6b = s16(d6b + d5b);                  // add.w d5,d6  (trois fois)
                d4 = divs(d4 * 5, d6b);                // muls.l #5,d4 ; divs d6,d4
                d3 = divs(d3 * 5, d6b);                // muls.l #5,d3 ; divs d6,d3
            } else {
                // .old_scaler : la multiplication par cinq deborderait.
                int d5b = s16(d5 + d5);                // add.w d5,d5
                d4 = ((d4 >> 2) * SLOW) >> 8;          // asr.l #2 ; muls.l #3413 ; asr.l #8
                d4 = divs(d4, d5b);                    // divs d5,d4
                d3 = ((d3 >> 2) * SLOW) >> 8;
                d3 = divs(d3, d5b);
            }
            // .done_scaler
            screenX[i] = s16(setw(d3, d3 + centreX));  // add.w Vid_CentreX_w,d3
            screenY[i] = s16(setw(d4, d4 + centreY));  // add.w draw_PolygonCentreY_w,d4
        }

        // ---- .calc_point_angle_brightness_loop (2072) ----
        int turn = s16(objectAngle) >> 8 >> 1;         // asr.w #8,d2 ; asr.w #1,d2
        for (int i = 0; i < count; i++) {
            int d0 = frame.normals()[i] & 0xFF;        // moveq #0,d0 ; move.b (a0)+,d0
            int d3 = (d0 + turn) & 0xF;                // move.b d0,d3 ; add.w d2,d3 ; and.w #$f
            int at = (d0 & 0xF0) + d3;                 // and.w #$f0,d0 ; add.w d3,d0
            int d1b = grid[at] & 0xFF;                 // moveq #0,d1 ; move.b (a2,d0.w),d1
            if ((byte) d1b < 0) {                      // bge.s .okpos
                d1b = 0;                               // moveq #0,d1
            }
            if (d1b > 31) {                            // cmp.w #31,d1 ; ble.s .oksmall
                d1b = 31;                              // move.w #31,d1
            }
            bright[i] = d1b;                           // move.w d1,(a6)+
        }
        return true;
    }

    /** {@code swap} : les deux moities d'un registre echangees. */
    private static int swap(int v) {
        return (v >>> 16) | (v << 16);
    }
}
