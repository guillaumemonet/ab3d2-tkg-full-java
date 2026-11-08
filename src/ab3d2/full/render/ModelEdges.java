package ab3d2.full.render;

import ab3d2.full.level.VectorModel;

import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Les BORDS d'une face : pour chaque colonne de l'ecran, ou le polygone commence et ou il
 * finit.
 *
 * <p>Port de {@code draw_PutInLines} et {@code draw_PutInLinesGouraud} (objdrawhires.s:3090
 * et 3357), avec le decoupage qui leur fait suite dans {@code doapoly} (2338).
 *
 * <h2>Le polygone est peint en COLONNES</h2>
 *
 * <p>C'est le point qu'il faut tenir avant tout le reste. Un polygone n'est pas rempli ligne
 * par ligne comme on s'y attendrait, mais colonne par colonne, de gauche a droite — et c'est
 * la meme orientation que les murs, le sol et le ciel, de sorte que tout le rendu du jeu
 * balaie l'ecran dans le meme sens.
 *
 * <p>Les deux tables sont donc indexees par la COLONNE, et chacune de leurs entrees porte le
 * bord d'un trait vertical : son ordonnee, ses deux coordonnees de texture, et sa clarte
 * quand la face est en degrade. {@link #topY} retient le bord nomme haut, {@link #botY} le
 * bord nomme bas.
 *
 * <p>Lequel est lequel ne se decide pas en comparant des ordonnees : il se decide au SENS de
 * l'arete. Une arete qui va vers la DROITE nourrit la table du haut, une arete qui va vers la
 * gauche celle du bas, et une arete verticale ne nourrit rien. Comme le contour tourne
 * toujours dans le meme sens — c'est ce que la face a prouve en montrant une aire positive —
 * cela suffit a separer les deux bords.
 *
 * <p>Ecarter les aretes verticales n'est pas une precaution de principe : elles sont cent
 * vingt-neuf mille huit cent soixante-dix-huit dans le corpus, dont quarante et un mille cent
 * vingt-six tombent dans la fenetre. Sans ce test, autant de divisions par zero.
 *
 * <h2>Les bornes de l'objet</h2>
 *
 * <p>Avant la premiere arete, les deux bornes sont posees a l'ENVERS : la gauche a neuf cent
 * soixante, trois fois la largeur de l'ecran, et la droite a moins dix. Chaque arete les
 * resserre, et si aucune ne le fait elles restent croisees — ce qui ne porte pas a
 * consequence, car une arete qui resserre leve aussi {@link #drawn()}, et c'est ce drapeau
 * qu'on regarde.
 *
 * <h2>Une colonne de trop</h2>
 *
 * <p>Quand une arete deborde a droite, le jeu ecrit jusqu'a la colonne de decoupe INCLUSE,
 * alors qu'il ne relira jamais que jusqu'a la colonne d'avant. En plein ecran, cela veut dire
 * qu'il ecrit a la colonne trois cent vingt d'une table qui en compte trois cent vingt. Ce
 * n'est pas un accident : l'original laisse justement un blanc de quatre cent quatre-vingts
 * mots devant la table du bas, en annoncant dans un commentaire qu'elle recoit des
 * deplacements negatifs. Le portage garde le meme debordement, avec une colonne de rab,
 * parce qu'effacer l'ecriture serait un choix que la source ne fait pas. Mesure : cinq cent
 * vingt-quatre ecritures a cette colonne-la, et pas une lecture.
 *
 * <h2>Le decoupage qui ne decoupe rien</h2>
 *
 * <p>Apres le trace, {@code doapoly} ramene les deux bornes dans la fenetre : quatre
 * comparaisons, deux bornages, et le compte des colonnes. De tout cela, une seule ligne agit
 * — celle qui jette une face large d'une seule colonne.
 *
 * <p>Le reste est redondant, et cela se demontre : chaque arete pose la borne gauche a
 * {@code max(xLo, leftClip)} et la borne droite a {@code min(xHi, rightClip - 1)}. Quand le
 * decoupage les relit, elles sont donc DEJA dans la fenetre, et ni ses comparaisons ni ses
 * bornages ne peuvent mordre. La mesure le confirme : la borne gauche depasse la decoupe de
 * zero a trois cent dix-neuf colonnes, jamais en deca, et la borne droite reste de une a
 * trois cent dix-neuf colonnes en deca de la decoupe, jamais au-dela.
 *
 * <p>Le drapeau lui-meme est redondant une seconde fois : une face qu'aucune arete n'a
 * touchee garde ses bornes a l'envers, et la borne droite a moins dix est deja plus petite
 * que n'importe quelle decoupe de gauche. L'ecarter par le drapeau ou par les bornes revient
 * au meme.
 *
 * <p>Tout cela est porte tel quel : ce sont les instructions du jeu, et les supprimer donnerait
 * a lire un rendu qui n'est pas le sien.
 *
 * <h2>Le compte qui n'est jamais negatif</h2>
 *
 * <p>Le trace compte ses colonnes, puis renonce si le compte est negatif. Il ne l'est jamais,
 * et pas par chance : une arete arrivee la a son extremite gauche avant la decoupe de droite
 * et son extremite droite apres celle de gauche, de sorte que le compte vaut au moins une
 * colonne dans les quatre combinaisons de debord. Mesure : de une a deux cent quarante-quatre
 * colonnes, jamais zero, jamais moins.
 *
 * <h2>La seconde variable que personne ne lit</h2>
 *
 * <p>Apres {@code polybright}, voici {@code firstpt} : remis a zero avant chaque partie du
 * modele, declare juste a cote, et lu nulle part dans aucun fichier du jeu. Non porte.
 *
 * <h2>Pourquoi une seule methode pour quatre copies de code</h2>
 *
 * <p>La source ecrit quatre fois la meme chose : deux sens d'arete, multiplies par le degrade
 * present ou absent. Les quatre copies ne different que par la table visee et par l'ECHANGE
 * des deux extremites — ce que la copie du bas fait avec {@code exg}, et qui se retrouve
 * ensuite dans l'ordre ou elle lit les coordonnees de texture et les clartes. Le portage
 * nomme l'extremite de gauche et celle de droite une fois pour toutes, et les quatre copies
 * se rejoignent sans qu'un seul calcul change.
 */
public final class ModelEdges {

    /**
     * Combien de colonnes les tables portent.
     *
     * <p>Une de plus que l'ecran : voir la colonne de trop, plus haut.
     */
    public static final int COLUMNS = Screen.WIDTH + 1;

    /** La borne gauche avant toute arete : trois fois la largeur de l'ecran. */
    private static final int BEYOND_RIGHT = 960;

    /** La borne droite avant toute arete. */
    private static final int BEYOND_LEFT = -10;

    private final int[] topY = new int[COLUMNS];
    private final int[] topU = new int[COLUMNS];
    private final int[] topV = new int[COLUMNS];
    private final int[] topBright = new int[COLUMNS];
    private final int[] botY = new int[COLUMNS];
    private final int[] botU = new int[COLUMNS];
    private final int[] botV = new int[COLUMNS];
    private final int[] botBright = new int[COLUMNS];

    private int left;
    private int right;
    private int offLeftBy;
    private boolean drawn;
    private int first;
    private int span;

    /** L'ordonnee du bord haut a la colonne donnee. */
    public int topY(int x) {
        return topY[x];
    }

    /** L'abscisse de texture du bord haut, en seize-seize. */
    public int topU(int x) {
        return topU[x];
    }

    /** L'ordonnee de texture du bord haut, en seize-seize. */
    public int topV(int x) {
        return topV[x];
    }

    /** La clarte du bord haut ; n'a de sens que si la face est en degrade. */
    public int topBright(int x) {
        return topBright[x];
    }

    /** L'ordonnee du bord bas a la colonne donnee. */
    public int botY(int x) {
        return botY[x];
    }

    /** L'abscisse de texture du bord bas, en seize-seize. */
    public int botU(int x) {
        return botU[x];
    }

    /** L'ordonnee de texture du bord bas, en seize-seize. */
    public int botV(int x) {
        return botV[x];
    }

    /** La clarte du bord bas ; n'a de sens que si la face est en degrade. */
    public int botBright(int x) {
        return botBright[x];
    }

    /** La colonne la plus a gauche qu'une arete ait touchee. */
    public int left() {
        return left;
    }

    /** La colonne la plus a droite qu'une arete ait touchee. */
    public int right() {
        return right;
    }

    /** Vrai si au moins une arete a nourri les tables. */
    public boolean drawn() {
        return drawn;
    }

    /** La premiere colonne a peindre, une fois le decoupage fait. */
    public int first() {
        return first;
    }

    /**
     * Combien de colonnes a peindre MOINS UNE, une fois le decoupage fait.
     *
     * <p>C'est le compteur de l'original, qui boucle une fois de plus qu'il ne compte.
     */
    public int span() {
        return span;
    }

    /**
     * Remet les bornes a l'envers, avant la premiere arete d'une face.
     *
     * <p>L'original pose les deux bornes en tete de {@code doapoly} et n'abaisse le drapeau
     * qu'une vingtaine d'instructions plus loin, juste avant d'appeler le trace. Rien ne lit
     * ni l'un ni l'autre entre les deux.
     */
    public void start() {
        left = BEYOND_RIGHT;                        // move.w #960,draw_Left_w
        right = BEYOND_LEFT;                        // move.w #-10,draw_Right_w
        drawn = false;                              // clr.b drawit
    }

    /**
     * Pose dans les tables tous les bords d'une face.
     *
     * @param face      la face, dont le contour se ferme sur une copie de son premier point
     * @param pts       les points du modele, projetes et eclaires
     * @param leftClip  la premiere colonne qu'on a le droit de peindre
     * @param rightClip la premiere colonne qu'on n'a PLUS le droit de peindre
     * @param gouraud   vrai si la face est en degrade, auquel cas les clartes sont interpolees
     */
    public void lines(VectorModel.Face face, ModelPoints pts, int leftClip, int rightClip,
            boolean gouraud) {
        int[] at = face.point();
        int[] u = face.u();
        int[] v = face.v();
        for (int i = 0; i < face.lines(); i++) {    // dbra d7,draw_PutInLines
            int a = at[i];                          // move.w (a1),d0
            int b = at[i + 1];                      // move.w 4(a1),d1
            int xa = s16(pts.screenX(a));           // move.w (a3,d0.w*4),d2
            int ya = s16(pts.screenY(a));           // move.w 2(a3,d0.w*4),d3
            int xb = s16(pts.screenX(b));           // move.w (a3,d1.w*4),d4
            int yb = s16(pts.screenY(b));           // move.w 2(a3,d1.w*4),d5
            if (xb == xa) {                         // cmp.w d2,d4 ; beq this_line_flat
                continue;
            }
            if (xb > xa) {                          // bgt this_line_on_top
                edge(topY, topU, topV, topBright,
                        xa, ya, u[i], v[i], pts.bright(a),
                        xb, yb, u[i + 1], v[i + 1], pts.bright(b),
                        leftClip, rightClip, gouraud);
            } else {
                // exg d2,d4 ; exg d3,d5 : l'arete va vers la gauche, on la retourne.
                edge(botY, botU, botV, botBright,
                        xb, yb, u[i + 1], v[i + 1], pts.bright(b),
                        xa, ya, u[i], v[i], pts.bright(a),
                        leftClip, rightClip, gouraud);
            }
        }
    }

    /**
     * Une arete, deja retournee pour aller de la gauche vers la droite.
     *
     * <p>Les deux extremites sont nommees par leur COLONNE et non par leur ordonnee :
     * l'abscisse de droite est strictement plus grande que celle de gauche, ce qui garantit
     * que les quatre divisions qui suivent ont un diviseur non nul.
     *
     * <h2>Les troncatures au mot qui ne tronquent rien</h2>
     *
     * <p>L'original travaille en mots, et le portage le suit ; mais le garde-fou de la face a
     * deja borne toutes les coordonnees a huit mille cent quatre-vingt-onze, de sorte qu'aucun
     * de ces ecarts ne peut deborder. Mesure : le debord de droite de -2938 a 319, celui de
     * gauche de 0 a 1132, l'ecart d'ordonnees de -4690 a 5330, l'ecart d'abscisses de 1 a
     * 3990, et les ecarts de texture et de clarte de -64 a 64. Les ordonnees rangees vont de
     * -7993 a 8130, et les abscisses de texture ne sortent jamais de leurs huit bits.
     *
     * <p>Les huit mutations qui effacent ces troncatures survivent donc toutes, et c'est
     * demontrable plutot que mesure. On les garde parce que l'original les ecrit.
     */
    private void edge(int[] y, int[] u, int[] v, int[] bright,
            int xLo, int yLo, int uLo, int vLo, int bLo,
            int xHi, int yHi, int uHi, int vHi, int bHi,
            int leftClip, int rightClip, boolean gouraud) {
        if (xLo >= rightClip) {                     // cmp.w draw_RightClipB_w,d2 ; bge
            return;
        }
        if (xHi <= leftClip) {                      // cmp.w draw_LeftClipB_w,d4 ; ble
            return;
        }
        int over = s16(rightClip - xHi);            // move.w draw_RightClipB_w,d6 ; sub.w d4,d6
        int kept;                                   // ce que l'original empile
        if (over <= 0) {                            // ble.s .clip_right
            kept = over;                            // move.w d6,-(a7)
            right = s16(rightClip - 1);             // sub.w #1,draw_Right_w
        } else {
            kept = 0;                               // move.w #0,-(a7)
            if (xHi > right) {                      // cmp.w draw_Right_w,d4 ; ble
                right = xHi;                        // move.w d4,draw_Right_w
            }
        }
        offLeftBy = 0;                              // move.w #0,draw_OffLeftBy_w
        int from = xLo;                             // move.w d2,d6
        if (xLo < leftClip) {                       // cmp.w draw_LeftClipB_w,d6 ; bge .okt
            offLeftBy = s16(leftClip - xLo);        // sub.w d2,d6 ; move.w d6,draw_OffLeftBy_w
            from = s16(offLeftBy + xLo);            // add.w d2,d6
        }
        drawn = true;                               // st drawit
        if (from < left) {                          // cmp.w draw_Left_w,d6 ; bge .no_new_top
            left = from;                            // move.w d6,draw_Left_w
        }

        int dy = s16(yHi - yLo);                    // sub.w d3,d5
        int py = yLo << 16;                         // swap d3 ; clr.w d3
        int dx = s16(xHi - xLo);                    // sub.w d2,d4 ; ext.l d4
        int stepY = (dy << 16) / dx;                // swap d5 ; clr.w d5 ; divs.l d4,d5

        int pu = uLo << 16;                         // swap d6 ; clr.w d6
        int stepU = (s16(uHi - uLo) << 16) / dx;    // sub.w d6,d2 ; swap d2 ; divs.l d4,d2

        int pv = vLo << 16;                         // swap d2 ; clr.w d2
        int stepV = (s16(vHi - vLo) << 16) / dx;    // sub.w d2,d5 ; swap d5 ; divs.l d4,d5

        int pb = bLo << 16;                         // swap d1 ; clr.w d1
        int stepB = 0;
        if (gouraud) {
            stepB = (s16(bHi - bLo) << 16) / dx;    // sub.w d1,d0 ; swap d0 ; divs.l d4,d0
        }

        int count = s16(setw(dx, dx + kept) - offLeftBy); // add.w (a7)+,d4 ; sub.w ...,d4
        if (count < 0) {                            // blt this_line_flat
            return;
        }
        for (int i = 0; i < offLeftBy; i++) {       // .calc_no_draw
            py += stepY;                            // add.l a5,d3
            pu += stepU;                            // add.l a6,d6
            pv += stepV;                            // add.l d5,d2
            pb += stepB;                            // add.l d0,d1
        }
        int x = from;
        for (int i = 0; i <= count; i++) {          // dbra d4,.put_in_line
            y[x] = py >> 16;                        // swap d3 ; move.w d3,(a4)+
            u[x] = pu;                              // move.l d6,(a4)+
            v[x] = pv;                              // move.l d2,(a4)+
            if (gouraud) {
                bright[x] = pb >> 16;               // swap d1 ; move.w d1,(a4)
            }
            x++;                                    // addq #6,a4
            py += stepY;
            pu += stepU;
            pv += stepV;
            pb += stepB;
        }
    }

    /**
     * Le decoupage d'ecran qui suit le trace des bords (objdrawhires.s:2338).
     *
     * <p>Les bornes que les aretes ont laissees debordent peut-etre de la fenetre : on les y
     * ramene, et ce qui reste donne la plage de colonnes a peindre. Rend faux quand il n'en
     * reste rien — ce que l'original nomme {@code polybehind}.
     *
     * <p>La derniere colonne est ramenee au sens LARGE sur la borne de droite, alors que le
     * trace, lui, y ecrit une colonne de plus. Les deux se rejoignent : une arete qui deborde
     * pose la borne droite une colonne en deca, de sorte que la colonne de trop n'est jamais
     * relue.
     */
    public boolean clip(int leftClip, int rightClip) {
        if (!drawn) {                               // tst.b drawit ; beq polybehind
            return false;
        }
        int from = left;                            // move.w draw_Left_w,d1
        int to = right;                             // move.w draw_Right_w,d7
        if (to <= leftClip) {                       // cmp.w d3,d7 ; ble polybehind
            return false;
        }
        if (from >= rightClip) {                    // cmp.w d4,d1 ; bge polybehind
            return false;
        }
        if (from < leftClip) {                      // cmp.w d3,d1 ; bge .notop
            from = leftClip;                        // move.w d3,d1
        }
        if (to > rightClip) {                       // cmp.w d4,d7 ; ble .nobot
            to = rightClip;                         // move.w d4,d7
        }
        int wide = s16(to - from);                  // sub.w d1,d7
        if (wide <= 0) {                            // ble polybehind
            return false;
        }
        first = from;                               // move.w d1,a2
        span = wide;
        return true;
    }
}
