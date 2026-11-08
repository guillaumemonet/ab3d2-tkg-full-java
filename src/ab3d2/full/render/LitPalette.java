package ab3d2.full.render;

import ab3d2.full.level.SpriteSheet;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.negw;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * La palette qu'une image ECLAIREE se fabrique, a chaque fois qu'on la dessine.
 *
 * <p>Port de la tete de {@code draw_bitmap_lighted} (objdrawhires.s:1107), jusqu'a la boucle
 * {@code .make_pals_loop} incluse. La boucle de pixels qui vient ensuite est dans
 * {@link BitmapPaint#paintLit}.
 *
 * <h2>Pourquoi tout ce travail</h2>
 *
 * <p>Les trois autres sortes de sprites lisent une palette TOUTE FAITE : une rampe choisie par
 * la distance, ou une table de melange. Celle-ci n'en lit aucune. Elle en CONSTRUIT une, de
 * deux cent trente-deux octets, pour cette image-la et pour cette image seulement — et elle
 * recommence au sprite suivant.
 *
 * <p>Ce qu'elle y met, c'est une tache de lumiere. L'image porte sur elle, non pas une teinte
 * uniforme, mais un reflet qui se deplace selon l'endroit d'ou la lumiere vient et selon la
 * direction du regard. Les aliens sont les seuls a en beneficier, et c'est ce qui leur donne
 * ce relief que les autres sprites n'ont pas.
 *
 * <h2>Les cinq etapes</h2>
 *
 * <ol>
 *   <li>on parcourt les SEIZE directions des deux premiers anneaux d'eclairage, en retenant de
 *       chacun la plus claire et en cumulant un vecteur pondere — la somme des deux vecteurs
 *       donne la direction d'ou vient la lumiere ;</li>
 *   <li>{@link #roughAngle} ramene ce vecteur a l'une des seize directions, sans aucune
 *       division ni table de tangentes : quatre comparaisons et un tableau de seize entrees ;
 *       </li>
 *   <li>l'ecart entre les deux anneaux donne une HAUTEUR, qui dit a quelle rangee du fichier
 *       {@code guff} on va puiser ;</li>
 *   <li>on y lit une grille de sept sur sept, decalee de l'angle du regard moins celui de la
 *       lumiere : c'est la tache ;</li>
 *   <li>on y ajoute un noyau fixe, {@link #HALO}, puis on traduit les quarante-neuf cases en
 *       vingt-neuf rampes, et chaque rampe en huit couleurs.</li>
 * </ol>
 *
 * <h2>Deux cent trente-deux octets sur trois cent quatre-vingt-douze</h2>
 *
 * <p>Le tampon du jeu fait trois cent quatre-vingt-douze octets ; la boucle n'en remplit que
 * deux cent trente-deux. L'octet du sprite, lui, va jusqu'a deux cent cinquante-cinq. Les
 * vingt-quatre valeurs du dessus lisent donc ce que le sprite PRECEDENT y avait laisse — la
 * source elle-meme l'a remarque, et a note en face de cette ligne {@code FIXME: causing
 * enforcer hits in Level C, illegal reads}.
 *
 * <p>Ce portage garde le tampon d'un appel a l'autre, sans l'effacer, pour que cette trainee-la
 * se produise comme dans l'original.
 */
public final class LitPalette {

    /** Les seize directions d'un anneau d'eclairage. */
    public static final int RING = BrightRings.RING;

    /** Le cote de la grille de lumiere. */
    public static final int SIDE = 7;

    /** Les quarante-neuf cases de la grille. */
    public static final int CELLS = SIDE * SIDE;

    /** Les rampes que la palette construite porte. */
    public static final int RAMPS = 29;

    /** Ce que pese le tampon du jeu, tres au-dela de ce qu'on y ecrit. */
    public static final int SIZE = 2 * CELLS * 4;

    /** La marque du RIEN dans un anneau : pas une luminosite nulle, une absence. */
    private static final int EMPTY = 0x80;

    /** Ce dont on retranche une luminosite pour en faire une clarte. */
    private static final int FULL = 48;

    /** Un tour complet d'angle. */
    private static final int TURN = 8192;

    /** Ce que la source appelle {@code SINE_SIZE} : un DEMI-tour. */
    private static final int HALF_TURN = TURN / 2;

    /** Une ligne du fichier {@code guff}. */
    private static final int LINE = 16;

    /** Ce que pese une rangee du fichier {@code guff} : sept lignes. */
    private static final int BAND = SIDE * LINE;

    /**
     * Les seize vecteurs des directions d'un anneau ({@code draw_XZAngs_vw}).
     *
     * <p>Ils ne sont pas sur un cercle : les quatre diagonales valent vingt-trois, les axes
     * seize et seize. C'est une rosace grossiere, et elle suffit — on ne cherche ici qu'a
     * savoir de quel cote la lumiere penche.
     */
    private static final int[] XZ = {
        0, 23, 10, 20, 16, 16, 20, 10,
        23, 0, 20, -10, 16, -16, 10, -20,
        0, -23, -10, -20, -16, -16, -20, -10,
        -23, 0, -20, 10, -16, 16, -10, 20,
    };

    /**
     * De l'octant et de sa moitie vers la direction ({@code draw_MapToAng_vw}).
     *
     * <p>{@link #roughAngle} compose un nombre de zero a quinze a coups de bits — un par
     * comparaison —, et ce nombre n'est pas la direction : c'est un chemin dans l'arbre des
     * comparaisons. Cette table-ci fait la traduction.
     */
    private static final int[] MAP_TO_ANG = {
        3, 2, 0, 1, 4, 5, 7, 6,
        12, 13, 15, 14, 11, 10, 8, 9,
    };

    /**
     * Le noyau fixe qu'on ajoute a la tache ({@code willybright}).
     *
     * <p>Zero au centre, trente au bord : c'est un creux, et comme une valeur basse veut dire
     * CLAIR, ce creux est un point brillant. Il ne bouge jamais ; c'est la tache tiree de
     * {@code guff} qui se deplace autour de lui.
     */
    private static final int[] HALO = {
        30, 30, 30, 30, 30, 30, 30,
        30, 20, 20, 20, 20, 20, 30,
        30, 20, 6, 3, 6, 20, 30,
        30, 20, 6, 0, 6, 20, 30,
        30, 20, 6, 6, 6, 20, 30,
        30, 20, 20, 20, 20, 20, 30,
        30, 30, 30, 30, 30, 30, 30,
    };

    /**
     * Quelles cases de la grille donnent les vingt-neuf rampes ({@code draw_Brights_vw}).
     *
     * <p>Sept groupes de tailles inegales — un, cinq, cinq, sept, cinq, cinq, un —, qui
     * dessinent un disque dans le carre de sept sur sept. Les coins n'y sont pas.
     */
    private static final int[] BRIGHTS = {
        3,
        8, 9, 10, 11, 12,
        15, 16, 17, 18, 19,
        21, 22, 23, 24, 25, 26, 27,
        29, 30, 31, 32, 33,
        36, 37, 38, 39, 40,
        45,
    };

    /** La meme chose pour une image RETOURNEE ({@code draw_Brights2_vw}) : chaque groupe a l'envers. */
    private static final int[] BRIGHTS_FLIPPED = {
        3,
        12, 11, 10, 9, 8,
        19, 18, 17, 16, 15,
        27, 26, 25, 24, 23, 22, 21,
        33, 32, 31, 30, 29,
        40, 39, 38, 37, 36,
        45,
    };

    private final byte[] guff;

    /** La grille de lumiere ({@code willy}), refaite a chaque appel. */
    private final int[] grid = new int[CELLS];

    /** Le tampon de palette ({@code draw_Pals_vl}), JAMAIS efface entre deux appels. */
    private final byte[] pals = new byte[SIZE];

    /** Les vingt-neuf clartes retenues, une par rampe. */
    private final int[] ramps = new int[RAMPS];

    private int base;
    private int offGuff;
    private int offPal;
    private int clamped;

    public LitPalette(byte[] guff) {
        this.guff = guff;
    }

    /** Ou, dans le fichier de la grille, la tache a ete puisee. */
    public int base() {
        return base;
    }

    /** La clarte retenue pour une rampe, deja bornee entre zero et trente et un. */
    public int ramp(int i) {
        return ramps[i];
    }

    /** Combien de fois la tache a ete cherchee hors du fichier {@code guff}. */
    public int offGuff() {
        return offGuff;
    }

    /** Combien de fois une couleur a ete cherchee hors de la palette de la feuille. */
    public int offPal() {
        return offPal;
    }

    /** Combien de fois une clarte a du etre ramenee entre zero et trente et un. */
    public int clamped() {
        return clamped;
    }

    public void clearCounts() {
        offGuff = 0;
        offPal = 0;
        clamped = 0;
    }

    /** L'octet que cette teinte pose a l'ecran. */
    public int at(int texel) {
        return pals[texel] & 0xFF;
    }

    /** Les deux cent trente-deux octets utiles, plus la trainee ; le tableau INTERNE. */
    public byte[] bytes() {
        return pals;
    }

    /**
     * Refait la palette pour une image eclairee.
     *
     * <p>Les anneaux doivent avoir ete refaits par {@link BrightRings#reset} — et par
     * {@code reset}, non par {@code calc} : voir la raison la-bas. On ne prend ici que les
     * SOIXANTE-QUATRE OCTETS, car c'est tout ce dont le calcul a besoin : la verification peut
     * donc les lui donner tels que l'oracle les a releves, sans refaire le chemin qui y mene.
     *
     * @param ring        les soixante-quatre octets des quatre anneaux
     * @param viewAngle   l'angle du regard ({@code Plr1_TmpAngPos_w})
     * @param flip        vrai si l'image est retournee : la tache l'est aussi
     * @param brightToAdd la luminosite de distance, AVANT qu'elle ne soit ramenee a zero
     * @param lightPal    laquelle des quatre palettes eclairees de la feuille
     * @param sheet       la feuille de sprites, dont la palette doit faire mille vingt-quatre
     *                    octets au moins
     */
    public void build(byte[] ring, int viewAngle, boolean flip, int brightToAdd,
            int lightPal, SpriteSheet sheet) {
        // ---- average_angle : la direction d'ou vient la lumiere ----
        int upX = 0;                                   // a2
        int upZ = 0;                                   // a3
        int loX = 0;                                   // a4
        int loZ = 0;                                   // a5
        int best1 = 0;                                 // d1 : le plus clair de l'anneau du haut
        int best0 = 0;                                 // d0 : le plus clair de celui du bas
        for (int i = 0; i < RING; i++) {               // move.w #15,d7 ; dbra
            int d4 = ring[RING + i] & 0xFF;            // moveq #0,d4 ; move.b 16(a1),d4
            if ((byte) d4 != (byte) EMPTY) {           // cmp.b #$80,d4 ; beq.s .nobright
                d4 = negw(d4);                         // neg.w d4
                d4 = setw(d4, d4 + FULL);              // add.w #48,d4
                if ((byte) d4 > (byte) best1) {        // cmp.b d1,d4 ; ble.s .no_brightest
                    best1 = (best1 & ~0xFF) | (d4 & 0xFF);  // move.b d4,d1
                }
                upX += muls(XZ[i * 2], d4);            // move.w (a0),d5 ; muls d4,d5 ; add.l d5,a2
                upZ += muls(XZ[i * 2 + 1], d4);        // move.w 2(a0),d6 ; muls d4,d6 ; add.l d6,a3
            }
            // BOTTYL : le meme travail sur l'anneau du bas, a une comparaison pres.
            int d4b = ring[i] & 0xFF;                  // moveq #0,d4 ; move.b (a1),d4
            if ((byte) d4b != (byte) EMPTY) {          // cmp.b #$80,d4 ; beq.s .nobright
                d4b = negw(d4b);                       // neg.w d4
                d4b = setw(d4b, d4b + FULL);           // add.w #48,d4
                if ((byte) d4b >= (byte) best0) {      // cmp.b d0,d4 ; blt.s .no_brightest
                    best0 = (best0 & ~0xFF) | (d4b & 0xFF);  // move.b d4,d0
                }
                loX += muls(XZ[i * 2], d4b);           // add.l d5,a4
                loZ += muls(XZ[i * 2 + 1], d4b);       // add.l d6,a5
            }
        }

        int angle = roughAngle(loX + upX, loZ + upZ);  // add.l d2,d4 ; add.l d3,d5 ; bsr

        // ---- foundang : de quelle HAUTEUR on puise dans la grille ----
        int d2 = setw(0, SIDE);                        // move.w #7,d2
        int d3 = setw(0, best1);                       // move.w d1,d3
        if (s16(best1) != s16(best0)) {                // cmp.w d0,d1 ; beq.s INMIDDLE
            if (!(s16(best1) > s16(best0))) {          // bgt.s .okpicked
                d3 = setw(d3, best0);                  // move.w d0,d3
            }
            // .okpicked
            d2 = setw(d2, best0);                      // move.w d0,d2
            d2 = setw(d2, d2 + best1);                 // add.w d1,d2 : la clarte totale
            int d1 = setw(best1, best1 << 4);          // asl.w #4,d1
            d1 = setw(d1, d1 - 1);                     // subq #1,d1
            d1 = divs(d1, d2);                         // divs d2,d1
            d2 = setw(d2, d1);                         // move.w d1,d2
        }

        // ---- INMIDDLE : la tache, lue dans guff ----
        d3 = negw(d3);                                 // neg.w d3
        d3 = setw(d3, d3 + FULL);                      // add.w #48,d3
        // muls.l offtopby-2,d3 n'a pas d'equivalent ici : c'est un simple muls.w #112.
        int from = muls(d2, BAND);                     // muls #7*16,d2 ; add.l d2,a1
        base = from;
        // draw_TempPtr_l vaudrait ici : elle est declaree dc.l 0 et JAMAIS ecrite.

        int d0 = setw(0, viewAngle);                   // move.w Plr1_TmpAngPos_w,d0
        d0 = negw(d0);                                 // neg.w d0
        d0 = setw(d0, d0 + HALF_TURN);                 // add.w #SINE_SIZE,d0
        d0 = setw(d0, d0 & (TURN - 1));                // AMOD_I d0
        d0 = asrw(d0, 8);                              // asr.w #8,d0
        d0 = asrw(d0, 1);                              // asr.w #1,d0
        d0 = (d0 & ~0xFF) | ((d0 - 3) & 0xFF);         // sub.b #3,d0
        d0 = (d0 & ~0xFF) | ((d0 + angle) & 0xFF);     // add.b d4,d0
        d0 = setw(d0, d0 & (LINE - 1));                // and.w #15,d0

        int cell = 0;
        for (int col = SIDE; col > 0; col--) {         // .across_loop (move.w #6,d1 ; dbra)
            int d5 = setw(0, d0);                      // move.w d0,d5
            for (int k = SIDE; k > 0; k--) {           // .down_loop (move.w #6,d2 ; dbra)
                int d4 = guff(from + (d5 & 0xFFFF));   // move.b (a1,d5),d4
                grid[cell] = (byte) (d4 + d3);         // add.b d3,d4 ; ext.w d4 ; move.w d4,(a0)+
                cell++;
                d5 = setw(d5, d5 + 1);                 // addq #1,d5
                d5 = setw(d5, d5 & (LINE - 1));        // and.w #15,d5
            }
            from += LINE;                              // add.w #16,a1
        }

        // ---- .add_it_in : le noyau fixe, et la distance ----
        for (int i = 0; i < CELLS; i++) {              // move.w #48,d1 ; dbra
            int d2b = setw(0, brightToAdd);            // move.w d0,d2
            d2b = setw(d2b, d2b + HALO[i]);            // add.w (a1)+,d2
            if (s16(d2b) > 0) {                        // ble.s .nopos SAUTE le clear
                d2b = 0;                               // moveq #0,d2 : seul le POSITIF s'annule
            }
            grid[i] = s16(grid[i] + d2b);              // add.w d2,(a0)+
        }

        // ---- .make_pals_loop : vingt-neuf rampes, huit couleurs chacune ----
        int[] which = flip ? BRIGHTS_FLIPPED : BRIGHTS;  // tst.b draw_FlipIt_b
        for (int i = 0; i < RAMPS; i++) {              // move.w #28,d0 ; dbra
            int d1 = setw(0, which[i]);                // move.w (a0)+,d1
            d1 = setw(0, grid[s16(d1)]);               // move.w (a2,d1.w*2),d1
            if (s16(d1) < 0) {                         // bge.s .okpos
                d1 = 0;                                // moveq #0,d1
                clamped++;
            } else if (s16(d1) >= 31) {                // cmp.w #31,d1 ; blt.s .okneg
                d1 = setw(d1, 31);                     // move.w #31,d1
                clamped++;
            }
            ramps[i] = s16(d1);
            for (int k = 0; k < SpriteSheet.LIT_RAMP; k++) {
                int c = sheet.lit(lightPal, s16(d1), k);  // move.l (a1,d1.w*8),(a3)+
                if (c < 0) {
                    offPal++;
                } else {
                    pals[i * SpriteSheet.LIT_RAMP + k] = (byte) c;
                }
            }
            pals[i * SpriteSheet.LIT_RAMP] = 0;        // move.b #0,-4(a3) : la transparence
        }
    }

    /** Un octet de la grille, ou zero hors du fichier. */
    private int guff(int at) {
        if (at < 0 || at >= guff.length) {
            offGuff++;
            return 0;
        }
        return guff[at] & 0xFF;
    }

    /**
     * Laquelle des seize directions, pour un vecteur quelconque.
     *
     * <p>Port de {@code draw_FindRoughAngle} (objdrawhires.s:1339). Pas de division, pas de
     * table de tangentes : on replie le plan en huit par trois comparaisons de signe et de
     * grandeur, puis une quatrieme — celle qui compare la moitie du grand cote au petit —
     * coupe chaque octant en deux. Les quatre bits ainsi composes ne sont pas la direction mais
     * le chemin parcouru dans l'arbre ; {@link #MAP_TO_ANG} les traduit.
     *
     * <p>La quatrieme comparaison place la frontiere a la demi-pente, non a la tangente de
     * vingt-deux degres et demi. Les seize secteurs ne sont donc pas egaux — et pour choisir une
     * rangee dans une grille de sept sur sept, cela n'a aucune importance.
     */
    private static int roughAngle(int d4, int d5) {
        d5 = -d5;                                      // neg.l d5
        int d7 = 0;                                    // moveq #0,d7
        if (d4 < 0) {                                  // tst.l d4 ; bge.s .no8
            d7 += 8;                                   // add.w #8,d7
            d4 = -d4;                                  // neg.l d4
        }
        if (d5 < 0) {                                  // tst.l d5 ; bge.s .no4
            d5 = -d5;                                  // neg.l d5
            d7 += 4;                                   // add.w #4,d7
        }
        if (d4 < d5) {                                 // cmp.l d5,d4 ; bge.s .no2
            d7 += 2;                                   // addq #2,d7
            int t = d4;                                // exg d4,d5
            d4 = d5;
            d5 = t;
        }
        d4 >>= 1;                                      // asr.l #1,d4
        if (d4 < d5) {                                 // cmp.l d5,d4 ; bge.s .no1
            d7 += 1;                                   // addq #1,d7
        }
        return MAP_TO_ANG[d7];                         // move.w draw_MapToAng_vw(pc,d7.w*2),d4
    }
}
