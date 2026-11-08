package ab3d2.full.render;

import ab3d2.full.level.ShadeTable;
import ab3d2.full.level.TextureMaps;

import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Le REMPLISSAGE d'une face : les pixels, enfin.
 *
 * <p>Port des quatre remplisseurs de polygones — {@code dopoly} (objdrawhires.s:2442),
 * {@code DOGLAREPOLY} (2580), {@code dopolyg} (2744) et {@code dopolyh} (2952). Ils partagent
 * la meme tete et ne different que par leur boucle de pixels.
 *
 * <h2>Une colonne a la fois</h2>
 *
 * <p>Les bords ont laisse, pour chaque colonne, l'ordonnee du haut et celle du bas avec les
 * coordonnees de texture qui vont avec. Le remplissage descend chaque colonne du haut vers le
 * bas en interpolant la texture, et c'est tout. Le decoupage, lui, est VERTICAL ici — l'autre
 * etait horizontal.
 *
 * <h2>La division qu'on ne fait pas</h2>
 *
 * <p>Interpoler demande de diviser l'ecart de texture par la hauteur de la colonne. Le jeu ne
 * divise pas : il lit {@code 16384 / N} dans une table de cinq cent douze entrees et multiplie.
 * Pour que le produit ne deborde pas, la valeur est d'abord reduite a son mot de poids fort,
 * multipliee en seize bits, puis redecalee de deux places — ce qui coute de la precision, et
 * la source le dit.
 *
 * <p>Au-dela de cinq cent onze lignes, la table n'a plus d'entree et le diviseur est RAMENE a
 * cinq cent onze. La source note que cela fausse les coordonnees de texture en gros plan, et
 * qu'il faudrait plutot retomber sur une vraie division. Porte tel quel : le corpus y tombe
 * quatorze mille neuf cent quatorze fois, pour des colonnes qui vont jusqu'a mille quatre cent
 * cinquante-neuf lignes.
 *
 * <p>La comparaison qui borne le diviseur est faite SANS SIGNE, de sorte qu'un diviseur
 * negatif serait ramene lui aussi plutot que de lire la table a l'envers. Il ne l'est jamais :
 * le diviseur mesure va de un a mille quatre cent cinquante-neuf, et cela se demontre — une
 * colonne arrivee la a son bas STRICTEMENT sous son haut.
 *
 * <h2>Deux decoupes qui ne decoupent rien</h2>
 *
 * <p>Avant tout calcul, la colonne est ecartee si son haut est sous la decoupe basse, ou son
 * bas au-dessus de la decoupe haute. Ni l'un ni l'autre ne peut mordre : dans les deux cas la
 * hauteur qu'on calcule ensuite serait nulle ou negative, et c'est elle qui ecarte. Comme pour
 * le decoupage des bords, la source fait le travail deux fois. Porte tel quel.
 *
 * <p>La hauteur nulle, en revanche, arrive quarante et un mille six cent cinquante et une
 * fois : des colonnes que le decoupage vertical reduit a rien.
 *
 * <h2>La troisieme variable que personne ne lit</h2>
 *
 * <p>Apres {@code polybright} et {@code firstpt}, voici {@code tstdca} : mis a zero dans
 * chacun des quatre remplisseurs, declare entre eux, et lu nulle part. Il sert pourtant a
 * quelque chose — mais par son ADRESSE, pas par sa valeur : {@code muls.l offtopby-2,d3} lit
 * un long dont le mot de poids fort est le remplissage qui suit {@code tstdca} et le mot de
 * poids faible est {@code offtopby}. C'est une multiplication par {@code offtopby} ecrite sans
 * passer par {@code ext.l}. La mise a zero, elle, ne sert a rien : l'octet qu'elle touche
 * n'entre pas dans ce long.
 *
 * <h2>Les quatre boucles</h2>
 *
 * <ul>
 *   <li>{@link #PLAIN} pose chaque texel a travers la rangee d'ombrage choisie ;
 *   <li>{@link #HOLES} fait de meme mais laisse le fond quand le texel vaut zero ;
 *   <li>{@link #GOURAUD} change de rangee d'ombrage A CHAQUE LIGNE, la clarte etant elle
 *       aussi interpolee le long de la colonne ;
 *   <li>{@link #GLARE} lit le pixel DEJA pose a l'ecran et s'en sert comme second index :
 *       c'est ce qui donne le reflet, qui depend de ce qu'il y a derriere.
 * </ul>
 *
 * <h2>L'index de rangee est SIGNE</h2>
 *
 * <p>{@code move.b (a1,d3.w),(a3)} etend le signe du mot avant de l'ajouter a l'adresse, et
 * cela compte : dans une face en degrade, la clarte interpolee passe SOUS ZERO en bas de
 * colonne — neuf cent seize fois dans le corpus. Le jeu lit alors une rangee d'ombrage AVANT
 * celle qu'il avait choisie, ce qui eclaircit le bas de la face. Prendre l'index sans signe
 * ferait pointer soixante-cinq mille octets plus loin, hors du fichier.
 *
 * <p>Pour le miroitement la question ne se pose pas : les texels mesures vont de zero a
 * trente et un, de sorte que {@code texel * 512} reste loin du bit de signe.
 *
 * <h2>Le degrade oublie la decoupe du haut</h2>
 *
 * <p>Quand une colonne est coupee en haut, la texture est avancee du nombre de lignes perdues
 * — c'est tout l'objet du detour par {@code offtopby}. La CLARTE, elle, ne l'est pas : elle
 * repart de la valeur du bord non coupe. Le degrade est donc decale vers le bas de toute la
 * hauteur perdue, et il peut sortir par le bas de sa rampe. Ce n'est pas une subtilite sans
 * suite : corriger ce decalage change quatre-vingt-six modeles du corpus.
 *
 * <p>Defaut de la source, <b>porte tel quel</b>.
 */
public final class ModelPaint {

    /** Le texel passe par la rangee d'ombrage. */
    public static final int PLAIN = 0;

    /** Le texel zero ne pose rien. */
    public static final int HOLES = 1;

    /** La clarte change a chaque ligne. */
    public static final int GOURAUD = 2;

    /** Le pixel deja pose sert de second index. */
    public static final int GLARE = 3;

    /** Jusqu'ou la table des inverses va. */
    public static final int MAX_ONE_OVER_N = 511;

    /** Seize mille trois cent quatre-vingt-quatre divise par le rang. */
    private static final int[] ONE_OVER_N = new int[MAX_ONE_OVER_N + 1];

    static {
        for (int n = 1; n <= MAX_ONE_OVER_N; n++) {
            ONE_OVER_N[n] = 16384 / n;                 // dc.w 16384/val
        }
    }

    /**
     * Ce qui reste d'une coordonnee de texture : six bits entiers et seize fractionnaires.
     *
     * <p>Le masque est applique a chaque ligne, de sorte qu'une texture se REPETE au lieu de
     * sortir de ses bornes — il n'y a aucun autre garde-fou.
     */
    private static final int WRAP = 0x3FFFFF;

    private int offTable;
    private int offScreen;
    private int top;
    private int clamped;
    private int columns;
    private int pixels;

    /** Combien de lectures sont tombees hors de la table d'ombrage. */
    public int offTable() {
        return offTable;
    }

    /** Combien d'ecritures seraient tombees hors du tampon. */
    public int offScreen() {
        return offScreen;
    }

    /** Combien de fois le diviseur a ete ramene a la longueur de la table. */
    public int clamped() {
        return clamped;
    }

    /** Combien de colonnes ont ete peintes. */
    public int columns() {
        return columns;
    }

    /** Combien de pixels ont ete poses. */
    public int pixels() {
        return pixels;
    }

    /** La premiere ligne qu'a couverte la derniere colonne peinte. */
    public int top() {
        return top;
    }

    /**
     * Peint une face dont les bords sont deja traces.
     *
     * @param edges   les deux tables de colonnes, et la plage que le decoupage a retenue
     * @param kind    {@link #PLAIN}, {@link #HOLES}, {@link #GOURAUD} ou {@link #GLARE}
     * @param texture le deplacement de la texture dans le bloc, seconde banque comprise
     * @param palette le deplacement de la rangee d'ombrage, qui vaut -512 pour le miroitement
     * @param topClip la premiere ligne qu'on a le droit de peindre
     * @param botClip la premiere ligne qu'on n'a PLUS le droit de peindre
     * @return combien de colonnes ont effectivement ete peintes
     */
    public int fill(ModelEdges edges, int kind, int texture, int palette,
            TextureMaps maps, ShadeTable shades, Screen screen, int topClip, int botClip) {
        int peintes = 0;
        int x = edges.first();                          // move.w d1,a2
        for (int i = 0; i <= edges.span(); i++) {       // dbra d7,dopoly
            if (column(edges, x, kind, texture, palette, maps, shades, screen,
                    topClip, botClip) > 0) {
                peintes++;
            }
            x++;                                        // addq #1,a2 ; adda.w #16,a4
        }
        return peintes;
    }

    /**
     * Une colonne : le decoupage vertical, les pentes, puis la boucle de pixels.
     *
     * @return combien de lignes elle a couvertes, zero si le decoupage l'a ecartee ; la
     *         premiere ligne se lit alors dans {@link #top()}
     */
    public int column(ModelEdges edges, int x, int kind, int texture, int palette,
            TextureMaps maps, ShadeTable shades, Screen screen, int topClip, int botClip) {
        int offTopBy = 0;                               // move.w #0,offtopby
        top = s16(edges.topY(x));                       // move.w (a4),d1
        if (top >= botClip) {                           // cmp.w draw_ObjClipB_w,d1 ; bge nodl
            return 0;
        }
        int bot = s16(edges.botY(x));                   // move.w BOT-TOP(a4),d2
        if (bot <= topClip) {                           // cmp.w draw_ObjClipT_w,d2 ; ble nodl
            return 0;
        }
        if (top < topClip) {                            // cmp.w draw_ObjClipT_w,d1 ; bge .nocl
            offTopBy = s16(topClip - top);              // sub.w d1,d3 ; move.w d3,offtopby
            top = topClip;                              // move.w draw_ObjClipT_w,d1
        }
        int raw = bot;                                  // move.w d2,d0
        if (bot > botClip) {                            // cmp.w draw_ObjClipB_w,d2 ; ble .nocr
            bot = botClip;                              // move.w draw_ObjClipB_w,d2
        }

        int du = edges.botU(x) - edges.topU(x);         // move.l 2+BOT(a4),d3 ; sub.l d5,d3
        int dv = edges.botV(x) - edges.topV(x);         // move.l 6+BOT(a4),d4 ; sub.l d6,d4
        int pu = edges.topU(x);                         // move.l 2(a4),d5
        int pv = edges.topV(x);                         // move.l 6(a4),d6

        int count = s16(bot - top);                     // sub.w d1,d2
        if (count <= 0) {                               // ble nodl
            return 0;
        }
        int n = s16(raw - top);                         // sub.w d1,d0

        if (offTopBy != 0) {                            // tst.w offtopby ; beq .notofftop
            n = s16(n + offTopBy);                      // add.w offtopby,d0
            int au = du * offTopBy;                     // muls.l offtopby-2,d3
            int av = dv * offTopBy;                     // muls.l offtopby-2,d4
            int inv = inverse(n);
            pu = pu + mulInv(inv, au);                  // MUL_INV_PAIR ; add.l d3,d5
            pv = pv + mulInv(inv, av);                  // add.l d4,d6
        }
        // .notofftop
        int inv = inverse(n);
        int stepU = mulInv(inv, du);                    // MUL_INV_PAIR d0,d3,d4
        int stepV = mulInv(inv, dv);

        int stepLight = 0;
        int light = 0;
        if (kind == GOURAUD) {
            int db = s16(edges.botBright(x) - edges.topBright(x));  // sub.w d7,d1
            light = setw(0, edges.topBright(x) << 8);   // asl.w #8,d7
            stepLight = s16(mulInv(inv, db << 16) >> 8);  // MUL_INV ; asr.l #8,d1
        }

        if (top < 0 || bot > Screen.HEIGHT) {
            offScreen++;                                // le jeu ne verifie rien
            return 0;
        }
        int at = screen.line(top) + x;                  // add.l ontoscr(pc,d1.w*4),a3
        byte[] out = screen.pixels();
        byte[] rows = shades.bytes();
        columns++;

        for (int i = 0; i < count; i++) {               // subq #1,d2 ; dbra d2,drawpol
            pu = pu & WRAP;                             // and.l d1,d5
            pv = pv & WRAP;                             // and.l d1,d6
            int uv = pv >> 8;                           // move.l d6,d0 ; asr.l #8,d0
            uv = (uv & ~0xFF) | ((pu >> 16) & 0xFF);    // swap d5 ; move.b d5,d0
            int texel = maps.texel(texture, s16(uv));   // move.b (a0,d0.w*4),d3
            pixels++;
            switch (kind) {
                case HOLES -> {
                    if (texel != 0) {                   // beq.s .dontplot
                        out[at] = shade(rows, palette + texel);
                    }
                }
                case GOURAUD -> {
                    int d3 = (light & ~0xFF) | texel;   // move.w d7,d3 ; move.b (a0,..),d3
                    out[at] = shade(rows, palette + s16(d3));
                    light = setw(light, light + stepLight);  // add.w d2,d7
                }
                case GLARE -> {
                    if (texel != 0) {                   // beq.s itsblack
                        int d3 = setw(0, texel << 8);   // lsl.w #8,d3
                        d3 = setw(d3, d3 + d3);         // add.w d3,d3
                        d3 = (d3 & ~0xFF) | (out[at] & 0xFF);  // move.b (a3),d3
                        out[at] = shade(rows, palette + s16(d3));
                    }
                }
                default -> out[at] = shade(rows, palette + texel);
            }
            pu = pu + stepU;                            // add.l a5,d5
            pv = pv + stepV;                            // add.l d4,d6
            at += Screen.WIDTH;                         // adda.w #SCREEN_WIDTH,a3
        }
        return count;
    }

    /** Un octet de la table d'ombrage, ou zero si le deplacement en sort. */
    private byte shade(byte[] rows, int at) {
        if (at < 0 || at >= rows.length) {
            offTable++;
            return 0;
        }
        return rows[at];
    }

    /**
     * L'inverse approche d'une hauteur de colonne.
     *
     * <p>La comparaison de l'original est {@code bls}, c'est-a-dire SANS SIGNE : un diviseur
     * negatif est donc ramene a la longueur de la table, et non pris pour un petit nombre.
     */
    private int inverse(int n) {
        int at = n & 0xFFFF;                            // cmp.w #MAX_ONE_OVER_N,d0 ; bls
        if (at > MAX_ONE_OVER_N) {
            at = MAX_ONE_OVER_N;                        // move.w #MAX_ONE_OVER_N,d0
            clamped++;
        }
        return ONE_OVER_N[at];                          // move.w OneOverN_vw(pc,d0.w*2),d0
    }

    /**
     * La multiplication par l'inverse, en seize bits sur seize.
     *
     * <p>{@code MUL_INV_PAIR} du Makefile, celle que l'assemblage choisit : la valeur est
     * reduite a son mot de poids fort, multipliee, puis redecalee de deux places. Les deux
     * places manquantes sont la precision qu'on paie.
     */
    private static int mulInv(int inv, int v) {
        return (s16(v >>> 16) * inv) << 2;              // swap ; muls.w ; lsl.l #2
    }
}
