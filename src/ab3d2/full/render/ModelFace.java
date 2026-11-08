package ab3d2.full.render;

import ab3d2.full.level.VectorModel;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.negw;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Une FACE d'un modele : faut-il la peindre, et de quelle couleur.
 *
 * <p>Port de la tete de {@code doapoly} (objdrawhires.s:2220). Trois questions s'y decident
 * avant qu'un seul pixel ne soit pose : la face est-elle a l'ecran, tourne-t-elle le dos, et
 * dans quelle teinte la peindre.
 *
 * <h2>Le garde-fou</h2>
 *
 * <p>Un point qui se projette loin hors de l'ecran donne des pentes enormes, et le trace les
 * calcule en virgule fixe. Plutot que de borner les pentes, le jeu jette la face entiere des
 * qu'un seul de ses points sort de huit mille cent quatre-vingt-onze, dans un sens ou dans
 * l'autre. C'est brutal — une face qui traverse l'ecran de part en part disparait d'un coup —
 * mais c'est aussi ce qui ecarte les points DERRIERE l'oeil, a qui la projection a donne
 * trente-deux mille sept cent soixante-sept.
 *
 * <p>Mesure sur six cent sept mille faces : les coordonnees eprouvees vont de -32718 a 32767,
 * et pas une seule ne tombe EXACTEMENT sur la borne — zero fois +8191, zero fois -8191, mais
 * huit fois le cran d'apres. Que la borne soit prise au sens large ou au sens strict ne change
 * donc rien a ce corpus ; qu'elle vaille 8191 ou 8192, si.
 *
 * <h2>Le dos tourne</h2>
 *
 * <p>Trois premiers points, produit en croix a l'ecran, et la face s'en va si l'aire est nulle
 * ou negative. Les sommets tournent donc tous dans le meme sens, et c'est ce qui dispense le
 * jeu de dessiner l'interieur des objets. C'est le plus gros du travail : sur six cent sept
 * mille faces, quatre cent dix-sept mille tournent le dos — plus des deux tiers.
 *
 * <p>Les ecarts mesures tiennent entre -8000 et 5760, ce qui est attendu puisque le garde-fou
 * vient de borner les coordonnees : la multiplication en mots du processeur ne tronque donc
 * jamais rien ici.
 *
 * <h2>Le calcul que personne ne lit</h2>
 *
 * <p>Entre le dos tourne et la suite, la source refait le meme produit en croix sur les points
 * en RELIEF et range le resultat dans {@code polybright} — une vingtaine d'instructions par
 * face. Rien ne lit jamais cette variable, dans aucun fichier du jeu. Elle n'est pas portee.
 *
 * <h2>La teinte</h2>
 *
 * <p>La face porte sa propre clarte, en pourcentage, que le jeu convertit en rang de palette :
 * cent pour cent donne le rang moins un, c'est-a-dire la pleine lumiere, et zero pour cent le
 * rang trente-et-un, c'est-a-dire le noir. A cela s'ajoute ce que l'eclairage du secteur dit de
 * l'orientation de la face — et c'est la que les quatre chemins divergent :
 *
 * <ul>
 *   <li>une face a TROUS puise dans les anneaux, et interpole entre celui du sol et celui du
 *       plafond selon son elevation ;
 *   <li>une face en DEGRADE n'a pas de teinte propre : chaque sommet porte la sienne ;
 *   <li>une face qui MIROITE lit une palette a elle, cinq cent douze octets AVANT la palette
 *       des textures, et la teinte qu'on vient de lui calculer est jetee ;
 *   <li>toutes les autres lisent la grille seize par seize, comme les points.
 * </ul>
 *
 * <h2>Le quatorzieme qui n'en est pas un</h2>
 *
 * <p>Le chemin des faces a trous interpole entre deux anneaux selon l'elevation, de zero a
 * quatorze. La division par quatorze a ete remplacee par une multiplication par
 * soixante-treize sur mille vingt-quatre — mais l'elevation est AJOUTEE a soixante-treize au
 * lieu d'etre multipliee par elle. Au lieu d'aller du sol au plafond, l'interpolation reste
 * donc a un quatorzieme du chemin, quelle que soit l'elevation.
 *
 * <p>Combien cela change-t-il ? Les ecarts d'anneaux mesures tiennent entre -45 et 33, et sur
 * cette plage l'elevation ne deplace le rang de palette que d'un cran, jamais plus, et le plus
 * souvent pas du tout. L'interpolation est pour ainsi dire INERTE : une face a trous prend la
 * clarte du sol, a un rang pres. C'est un defaut de la source, pas du portage ; il ne touche
 * que les SEPT faces a trous de tout le jeu, et on le porte tel quel.
 */
public final class ModelFace {

    /** Au-dela, le trace deborderait : la face entiere s'en va. */
    public static final int GUARDBAND = 8191;

    /** Jetee par le garde-fou. */
    public static final int GUARDED = 0;

    /** Elle tourne le dos. */
    public static final int TURNED = 1;

    /** Gardee. */
    public static final int SHOWN = 2;

    /** Combien de rangs la palette ombree a. */
    public static final int ROWS = 32;

    /** Ou commence la palette ombree, depuis la palette des textures. */
    public static final int SHADED = 256 * ROWS;

    /** Ou commence la palette du miroitement, depuis la meme. */
    public static final int GLARE = -512;

    /** Quarante-et-un sur quatre mille quatre-vingt-seize tient lieu de un sur cent. */
    private static final int PERCENT = 41;

    /** Soixante-treize sur mille vingt-quatre tient lieu de un sur quatorze. */
    private static final int SLOPE = 73;

    private int guarded;
    private int turned;
    private int holed;
    private int smooth;
    private int shiny;
    private int plain;

    /** Combien de faces le garde-fou a jetees. */
    public int guarded() {
        return guarded;
    }

    /** Combien de faces tournaient le dos. */
    public int turned() {
        return turned;
    }

    /** Combien de faces a trous ont ete teintees. */
    public int holed() {
        return holed;
    }

    /** Combien de faces en degrade. */
    public int smooth() {
        return smooth;
    }

    /** Combien de faces qui miroitent. */
    public int shiny() {
        return shiny;
    }

    /** Combien de faces ordinaires. */
    public int plain() {
        return plain;
    }

    /**
     * La face est-elle a peindre.
     *
     * <p>Port de {@code checkbeh} et du produit en croix qui suit (objdrawhires.s:2230-2291).
     *
     * @param face la face
     * @param pts  les points du modele, deja projetes
     */
    public boolean visible(VectorModel.Face face, ModelPoints pts) {
        return sort(face, pts) == SHOWN;
    }

    /**
     * Le meme verdict, mais dit : {@link #GUARDED}, {@link #TURNED} ou {@link #SHOWN}.
     *
     * @param face la face
     * @param pts  les points du modele, deja projetes
     */
    public int sort(VectorModel.Face face, ModelPoints pts) {
        int[] at = face.point();
        for (int i = 0; i < face.lines(); i++) {    // dbra d7 : le mot de tete PLUS UN tours
            int p = at[i];                          // move.w (a1),d0
            // Le mot double lu d'un coup porte l'abscisse en haut et l'ordonnee en bas : le
            // jeu eprouve donc l'ordonnee d'abord, puis echange les deux moities.
            int y = s16(pts.screenY(p));            // move.l (a3,d0.w*4),d0
            if (y < -GUARDBAND || y > GUARDBAND) {  // cmp.w #-GUARDBAND ; blt / cmp #GUARDBAND ; bgt
                guarded++;
                return GUARDED;                     // .guard_clip
            }
            int x = s16(pts.screenX(p));            // swap d0
            if (x < -GUARDBAND || x > GUARDBAND) {  // blt .guard_clip / ble .notbeh
                guarded++;
                return GUARDED;
            }
        }
        int x0 = s16(pts.screenX(at[0]));           // move.w (a3,d0.w*4),d0
        int x1 = s16(pts.screenX(at[1]));           // move.w (a3,d1.w*4),d1
        int x2 = s16(pts.screenX(at[2]));           // move.w (a3,d2.w*4),d2
        int y0 = s16(pts.screenY(at[0]));           // move.w 2(a3,d0.w*4),d3
        int y1 = s16(pts.screenY(at[1]));           // move.w 2(a3,d1.w*4),d4
        int y2 = s16(pts.screenY(at[2]));           // move.w 2(a3,d2.w*4),d5
        int dx0 = s16(x0 - x1);                     // sub.w d1,d0
        int dx2 = s16(x2 - x1);                     // sub.w d1,d2
        int dy0 = s16(y0 - y1);                     // sub.w d4,d3
        int dy2 = s16(y2 - y1);                     // sub.w d4,d5
        int area = muls(dx2, dy0) - muls(dx0, dy2); // muls d3,d2 ; muls d5,d0 ; sub.l d0,d2
        if (area <= 0) {                            // ble polybehind
            turned++;
            return TURNED;
        }
        return SHOWN;
    }

    /**
     * Ou commence la texture de la face, depuis le debut de la premiere banque.
     *
     * <p>Port de {@code .notsec} (objdrawhires.s:2370). Une texture de la seconde banque se
     * reconnait a son mot NEGATIF : le bit de signe est la marque, le reste le deplacement.
     */
    public int texture(VectorModel.Face face) {
        return face.secondBank() ? 65536 + face.texture() : face.texture();
    }

    /**
     * Quelle palette la face prend, depuis la palette des textures.
     *
     * <p>Port de la teinte de face (objdrawhires.s:2396-2444), avec ses quatre chemins.
     *
     * @param face        la face
     * @param faceAngles  la table d'orientations de la pose, une par face du modele
     * @param objectAngle l'orientation de l'objet
     * @param pts         les points, pour leur grille d'eclairage
     * @param rings       les anneaux, dont seules les faces a trous se servent
     */
    public int palette(VectorModel.Face face, int[] faceAngles, int objectAngle,
            ModelPoints pts, BrightRings rings) {
        int d1 = setw(0, face.light());             // moveq #0,d1 ; move.b (a1)+,d1
        d1 = setw(d1, d1 << 5);                     // asl.w #5,d1
        d1 = muls(d1, PERCENT);                     // muls.w #41,d1
        d1 = d1 >> 8;                               // asr.l #8,d1
        d1 = d1 >> 4;                               // asr.l #4,d1
        d1 = negw(d1);                              // neg.w d1
        d1 = setw(d1, d1 + ROWS - 1);               // add.w #31,d1

        if (face.holes()) {                         // tst.b draw_Holes_b ; bne gotholesin
            holed++;
            d1 = setw(d1, d1 + tween(face, faceAngles, objectAngle, rings));
            return SHADED + (row(d1) << 8);         // asl.w #8,d1 ; palette + 256*32 + d1
        }
        if (face.gouraud()) {                       // tst.b draw_Gouraud_b ; bne gotlurvelyshading
            smooth++;
            return SHADED;                          // la teinte vient des sommets, pas d'ici
        }
        int d5 = grid(face, faceAngles, objectAngle, pts);
        d1 = setw(d1, d1 + d5);                     // add.w d5,d1
        int rank = row(d1);
        if (face.glare()) {                         // tst.b draw_PreGouraud_b ; bne predoglare
            shiny++;
            return GLARE;                           // le rang qu'on vient de calculer est jete
        }
        plain++;
        return SHADED + (rank << 8);
    }

    /**
     * Le rang de palette, borne aux deux bouts.
     *
     * <p>Le chemin des faces a trous borne le haut par {@code ble} et les autres par
     * {@code blt} : l'un laisse passer trente-et-un et l'autre le remplace par lui-meme, ce qui
     * revient au meme.
     */
    private static int row(int d1) {
        if (s16(d1) < 0) {                          // tst.w d1 ; bge.s toobright
            d1 = setw(d1, 0);                       // move.w #0,d1
        }
        if (s16(d1) >= ROWS - 1) {                  // cmp.w #31,d1 ; blt.s .toodark
            d1 = ROWS - 1;                          // moveq #31,d1
        }
        return s16(d1);
    }

    /**
     * Ce que la grille seize par seize dit de l'orientation de la face.
     *
     * <p>Meme lecture que pour les points : nibble haut l'elevation, nibble bas l'azimut, et
     * l'azimut tourne avec l'objet.
     */
    private static int grid(VectorModel.Face face, int[] faceAngles, int objectAngle,
            ModelPoints pts) {
        int d4 = asrw(asrw(setw(0, objectAngle), 8), 1); // move.w draw_ObjectAng_w,d4 ; asr.w #8 ; asr.w #1
        int d2 = faceAngles[face.normal()];         // move.b (a1,d2.w),d2
        int d3 = d2;                                // move.b d2,d3  (d3 etait nul)
        d3 = s16(d3 + d4) & 0xF;                    // add.w d4,d3 ; and.w #$f,d3
        d2 = d2 & 0xF0;                             // and.w #$f0,d2
        d2 = (d2 + d3) & 0xFF;                      // add.b d3,d2
        return pts.grid()[d2] & 0xFF;               // moveq #0,d5 ; move.b (a1,d2.w),d5
    }

    /**
     * Ce que les anneaux disent de l'orientation d'une face a trous.
     *
     * <p>Port de {@code gotholesin} (objdrawhires.s:2896), avec son interpolation en panne :
     * voir la note de classe.
     */
    private static int tween(VectorModel.Face face, int[] faceAngles, int objectAngle,
            BrightRings rings) {
        int d4 = asrw(asrw(setw(0, objectAngle), 8), 1); // asr.w #8 ; asr.w #1
        int d2 = faceAngles[face.normal()];         // move.b (a1,d2.w),d2
        int d3 = d2 >>> 4;                          // move.b d2,d3 ; lsr.b #4,d3
        d2 = (d2 + d4) & 0xF;                       // add.b d4,d2 ; and.w #$f,d2
        // La source appelle le premier anneau « top » et le second « bottom » ; ce sont bien
        // le SOL et le PLAFOND, dans l'ordre ou la grille des points les interpole.
        int top = rings.bright(BrightRings.FLOOR, d2) & 0xFF;   // move.b (a1,d2.w),d4
        int bot = rings.bright(BrightRings.ROOF, d2) & 0xFF;    // move.b 16(a1,d2.w),d5
        int d5 = setw(0, bot - top);                // sub.w d4,d5
        d3 = setw(d3, d3 + SLOPE);                  // add.w #73,d3   <- l'addition fautive
        d5 = muls(d5, d3);                          // muls d3,d5
        d5 = d5 >> 8;                               // asr.l #8,d5
        d5 = d5 >> 2;                               // asr.l #2,d5
        return s16(setw(d5, d5 + top));             // add.w d4,d5
    }
}
