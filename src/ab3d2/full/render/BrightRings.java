package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.level.Level;

import java.util.Arrays;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * D'ou vient la lumiere : seize directions, quatre anneaux.
 *
 * <p>Les murs, les sols et les sprites ordinaires prennent leur luminosite a l'endroit ou ils
 * sont. Un objet en relief ne le peut pas : ses faces regardent chacune ailleurs, et c'est de
 * cela qu'on le voit rond. Il lui faut donc savoir non pas combien il y a de lumiere, mais de
 * quel COTE elle est — et c'est ce que cette classe repond.
 *
 * <h2>La boussole</h2>
 *
 * <p>Le tour est coupe en seize, et l'on tient quatre rangs de seize octets. Les deux premiers
 * sont la lumiere du secteur ou l'on se tient, au sol et au plafond. Les deux autres sont celle
 * des secteurs VOISINS, vus par les ouvertures — plus un mur plein, qui n'eclaire rien et
 * s'inscrit a quarante-huit, la valeur du noir dans cette echelle.
 *
 * <p>Chaque point de bord d'un secteur est une source : on prend l'angle sous lequel on le voit
 * depuis l'objet, on le ramene a l'un des seize rangs, et l'on y ecrit sa luminosite. Deux
 * points tombant dans le meme rang, le dernier gagne — le jeu ne moyenne rien.
 *
 * <h2>Les trous, et comment on les bouche</h2>
 *
 * <p>Seize rangs pour une poignee de points : la plupart restent vides, marques moins cent
 * vingt-huit. {@link #tween} les remplit en interpolant d'un rang connu au suivant, EN ROND —
 * c'est un anneau, pas une ligne, et le dernier connu rejoint le premier par l'autre bout.
 *
 * <h2>La soustraction finale</h2>
 *
 * <p>Les quatre rangs ne font pas quatre resultats. Les deux derniers — les voisins — sont
 * RETOURNES (quarante-huit moins leur valeur, de moitie) puis retranches des deux premiers, et
 * le reste est garde seulement s'il est negatif. Autrement dit : un cote n'est clair que si
 * l'on y voit clair ET que rien n'y fait obstacle. Un mur plein, inscrit a quarante-huit,
 * donne zero une fois retourne : il ne retranche rien, mais il n'ajoute rien non plus, et le
 * cote reste aussi sombre que le secteur.
 *
 * <p>Le resultat tient dans les deux premiers rangs, en octets SIGNES : plus la valeur est
 * basse, plus ce cote-la est clair.
 *
 * <h2>Ce que l'epreuve ne separe pas</h2>
 *
 * <p>Quarante-et-une mutations, trente-sept mordent. Les quatre qui survivent tiennent toutes a
 * la meme mesure : cette echelle est MINUSCULE. Sur les quatre-vingt-quinze mille quatre cents
 * luminosites de points des seize niveaux, l'octet brut va de moins vingt a dix-huit, si bien
 * qu'une fois mise a l'echelle et les trois cents otes, la valeur ne depasse jamais
 * vingt-sept — trente-et-un en comptant ce que le clignotement y ajoute. Ce que
 * {@link #shade} en tire tient dans [0, 46], et quarante-huit — le mur plein — se tient juste
 * au-dessus de ce plafond.
 *
 * <p>D'ou quatre choses qu'on ne peut pas montrer : le plancher a zero (la valeur n'est jamais
 * negative : zero cas), la moitie prise sur l'octet plutot que sur le mot (rien ne depasse cent
 * vingt-sept), les octets de l'anneau lus signes plutot que non signes (rien n'atteint cent
 * vingt-huit), et la soustraction finale comparee en mot plutot qu'en octet (elle reste dans
 * [-46, 24]). Les quatre sont portees comme l'original les ecrit, et mesurees plutot
 * qu'excusees.
 *
 * <h2>Ce qui n'est pas porte</h2>
 *
 * <p>{@code HeadTowardsAng} est, dans le jeu, une routine de DEPLACEMENT dont on ne reprend ici
 * que l'angle. Elle bouge aussi le point vise et pose trois autres resultats globaux ; aucun
 * n'est relu sur ce chemin, puisque le point vise est reecrit a chaque tour de boucle. Sa
 * racine carree est recopiee plutot que partagee avec {@code sim.Aliens} : ce sont deux ports
 * de la meme routine, pas un appel de l'un a l'autre.
 */
public final class BrightRings {

    /** En combien de directions le tour est coupe. */
    public static final int RING = 16;

    /** Combien d'anneaux : sol et plafond du secteur, puis sol et plafond des voisins. */
    public static final int RINGS = 4;

    /** Le rang du sol, celui du plafond. */
    public static final int FLOOR = 0;
    public static final int ROOF = 1;

    /** Ce qu'un rang vaut tant que personne n'y a ecrit. */
    private static final byte EMPTY = (byte) -128;

    /** Ce qu'inscrit un mur plein : le noir de cette echelle. */
    private static final int SOLID = 48;

    /** Le masque d'un tour complet, en octets. */
    private static final int TURN = 8191;

    /** Dix points de bord par secteur, quatre luminosites chacun. */
    private static final int POINTS = Lights.POINTS;
    private static final int SLOTS = Lights.SLOTS;

    /** Seize sur N, multiplie par mille six cent trente-huit : le pas d'une interpolation. */
    private static final int[] ONE_OVER_N = new int[RING + 1];

    static {
        for (int n = 1; n < ONE_OVER_N.length; n++) {
            ONE_OVER_N[n] = 16384 / n;
        }
    }

    private final Level level;
    private final Lights lights;
    private final SinCos sinCos;

    /** Les points de bord, a plat : un debordement retombe sur le secteur suivant. */
    private final int[] border;

    /** Les quatre anneaux, bout a bout. */
    private final byte[] ring = new byte[RINGS * RING];

    /**
     * Le dernier angle trouve.
     *
     * <p>Il PERSISTE d'un point a l'autre et d'un appel a l'autre, parce que c'est une variable
     * globale du jeu : un point de bord qui tombe exactement sur l'objet ne calcule aucun angle
     * et laisse ecrire sa luminosite dans le rang du point PRECEDENT.
     */
    private int angRet;

    private int offTable;
    private int emptyRings;
    private int stale;

    public BrightRings(Level level, Lights lights, SinCos sinCos) {
        this.level = level;
        this.lights = lights;
        this.sinCos = sinCos;
        border = new int[POINTS * level.zones().size()];
        for (Level.Zone z : level.zones()) {
            java.util.List<Integer> pts = z.borderPoints();
            for (int i = 0; i < pts.size(); i++) {
                border[z.id() * POINTS + i] = pts.get(i);
            }
        }
    }

    /** Les soixante-quatre octets, tels que le rendu les lit. */
    public byte[] rings() {
        return ring;
    }

    /** La luminosite d'une direction, dans l'un des quatre anneaux. */
    public int bright(int ringNo, int direction) {
        return ring[ringNo * RING + direction];
    }

    /**
     * Combien de fois un point de bord a designe hors des tables.
     *
     * <p>Le jeu ne verifie rien et lirait la memoire voisine ; ce n'est pas ce qu'il y a ici.
     */
    public int offTable() {
        return offTable;
    }

    /**
     * Combien d'anneaux entierement vides ont ete rencontres.
     *
     * <p>L'original boucle alors sans fin, faute de borner sa recherche du premier rang rempli.
     * Un anneau vide n'a rien a interpoler : on sort. C'est la meme garde que porte le portage
     * fidele.
     */
    public int emptyRings() {
        return emptyRings;
    }

    /**
     * Combien de points sont tombes EXACTEMENT sur l'objet.
     *
     * <p>C'est le seul cas ou l'angle precedent sert : aucun n'est calcule, et la luminosite du
     * point s'inscrit dans la direction du point d'avant. Compte pour savoir si cela arrive.
     */
    public int stale() {
        return stale;
    }

    public void clearOffTable() {
        offTable = 0;
        emptyRings = 0;
        stale = 0;
    }

    /**
     * Efface les quatre anneaux, et n'eclaire que le secteur ou l'objet se tient.
     *
     * <p>Port de {@code draw_ResetAngleBrights} (1383). Les quatre anneaux partent a
     * {@code $80}, la marque du RIEN — pas une luminosite nulle, une absence —, puis le
     * secteur courant seul y pose ses points.
     *
     * <p>Ce n'est pas une etape de {@link #calc} qu'on aurait extraite par commodite : les
     * SPRITES ECLAIRES s'arretent ici. Ils ne voient ni les secteurs voisins, ni les murs
     * pleins, ni le lissage, ni la fusion des quatre anneaux en deux. Leur eclairage est donc
     * bien plus pauvre que celui des modeles en relief, et c'est l'original qui le veut :
     * {@code draw_bitmap_lighted} appelle cette routine-ci, pas {@code draw_CalcBrightRings}.
     *
     * @param zone  le secteur ou l'objet est dessine
     * @param upper vrai s'il est a l'etage haut
     * @param x     sa position
     * @param z     sa position
     */
    public void reset(int zone, boolean upper, int x, int z) {
        Arrays.fill(ring, EMPTY);                      // 16 × move.l #$80808080,(a2)+
        zoneRing(zone, upper, x, z, 0);                // bsr draw_CalcBrightsInZone
    }

    /**
     * Refait les quatre anneaux pour un objet.
     *
     * @param zone  le secteur ou l'objet est dessine
     * @param upper vrai s'il est a l'etage haut : les luminosites de points changent de moitie
     * @param x     sa position
     * @param z     sa position
     */
    public void calc(int zone, boolean upper, int x, int z) {
        reset(zone, upper, x, z);                      // bsr draw_ResetAngleBrights
        int at = 2 * RING;                             // move.l #draw_AngleBrights_vl+32,a2

        // ---- draw_CalcBrightRings (1415), .do_all_walls ----
        for (int e : level.zones().get(zone).outline()) {
            int off = s16(setw(0, e << 4));            // asl.w #4,d0 ; lea (a1,d0.w),a3
            int idx = off >> 4;
            if (idx != e || idx >= level.edges().size()) {
                offTable++;
                continue;
            }
            Level.Edge edge = level.edges().get(idx);
            if (edge.joinZone() >= 0) {                // blt.s .solid_wall
                zoneRing(edge.joinZone(), upper, x, z, at);
                continue;                              // bra .do_all_walls
            }
            // .solid_wall : le point vise est le milieu du mur tourne d'un quart de tour.
            int newX = s16(s16(x) - edge.dz());        // move.w oldx,newx ; sub.w d2,newx
            int newZ = s16(s16(z) + edge.dx());        // move.w oldz,newz ; add.w d1,newz
            head(x, z, newX, newZ);                    // jsr HeadTowardsAng
            int dir = direction();
            ring[at + dir] = SOLID;                    // move.b #48,(a2,d1.w)
            ring[at + RING + dir] = SOLID;             // move.b #48,16(a2,d1.w)
        }

        // .no_more_walls
        tween(0);                                      // 4 × bsr draw_TweenBrights
        tween(RING);
        tween(2 * RING);
        tween(3 * RING);
        sum();
    }

    /**
     * La part d'un secteur : chacun de ses points de bord ecrit sa luminosite dans la direction
     * ou on le voit.
     *
     * <p>Port de {@code draw_CalcBrightsInZone} (1592). Les deux tables — les numeros de points
     * et les luminosites — sont parcourues A PLAT : un secteur dont la liste n'a pas de fin
     * continue dans le suivant, et c'est le jeu qui le veut ainsi.
     */
    private void zoneRing(int zone, boolean upper, int x, int z, int at) {
        int bo = zone * POINTS;                        // add.l d1,a1   (d1 = zone*20 octets)
        int bp = zone * POINTS * SLOTS;                // lea (a0,d1.l*4),a0
        if (upper) {                                   // tst.b draw_InUpperZone_b
            bp += 2;                                   // adda.w #4,a0
        }
        int[] brights = lights.pointBrights();
        while (true) {                                 // .do_point_bright
            if (bo >= border.length) {
                offTable++;
                return;
            }
            int p = border[bo++];                      // move.w (a1)+,d0
            if (p < 0) {                               // blt .done_point_bright
                return;
            }
            if (p >= level.points().size() || bp + 1 >= brights.length) {
                offTable++;
                bp += SLOTS;
                continue;
            }
            Level.Point pt = level.points().get(p);
            head(x, z, pt.x(), pt.z());                // jsr HeadTowardsAng
            int dir = direction();
            ring[at + dir] = (byte) shade(brights[bp]);        // move.b d0,(a2,d1.w)
            ring[at + RING + dir] = (byte) shade(brights[bp + 1]); // move.b d0,16(a2,d1.w)
            bp += SLOTS;                               // adda.w #8,a0
        }
    }

    /**
     * Ce qu'une luminosite de point vaut dans un anneau.
     *
     * <p>Une valeur NEGATIVE est d'abord repliee — c'est la part que l'eclairage mobile a
     * poussee au-dela de sa butee. Puis trois cents sont otes, le plancher est a zero, et le
     * tout est augmente de MOITIE : une demi-fois plus pour que l'ecart entre deux cotes d'un
     * objet se voie.
     */
    private static int shade(int raw) {
        int d0 = s16(setw(0, raw));                    // move.w (a0),d0
        if (d0 < 0) {                                  // bge.s .okpos
            d0 = s16(d0 + 332);                        // add.w #332,d0
            d0 = asrw(setw(0, d0), 2);                 // asr.w #2,d0
            d0 = s16(-d0);                             // neg.w d0
            d0 = s16(d0 + 332);                        // add.w #332,d0
        }
        // .okpos
        d0 = s16(d0 - 300);                            // sub.w #300,d0
        if (d0 < 0) {                                  // bge.s .okpos3
            d0 = 0;                                    // move.w #0,d0
        }
        // .okpos3 — la suite ne travaille plus qu'en OCTETS.
        int lo = (byte) d0;                            // move.b d0,d2
        int d2 = (byte) (lo >> 1);                     // asr.b #1,d2
        return (byte) (lo + d2);                       // add.b d2,d0
    }

    /** L'angle courant, ramene a l'un des seize rangs. */
    private int direction() {
        int d1 = s16(-s16(angRet));                    // move.w AngRet,d1 ; neg.w d1
        d1 = d1 & TURN;                                // AMOD_I d1
        d1 = asrw(setw(0, d1), 8);                     // asr.w #8,d1
        return asrw(setw(0, d1), 1);                   // asr.w #1,d1
    }

    /**
     * Bouche les trous d'un anneau, en rond.
     *
     * <p>Port de {@code draw_TweenBrights} (1521). Le pas se calcule en virgule fixe sur
     * seize bits, avec la table des inverses : pas de division.
     */
    private void tween(int at) {
        int d0 = 0;                                    // moveq #0,d0
        while (ring[at + (d0 & 0xFFFF)] == EMPTY) {    // .backinto
            d0 = setw(d0, d0 + 1);                     // addq #1,d0
            if ((d0 & 0xFFFF) >= RING) {
                // L'original ne borne pas cette recherche : un anneau entierement vide le fait
                // tourner sans fin. Rien a interpoler, on sort.
                emptyRings++;
                return;
            }
        }
        // .okbr
        int d7 = d0 & 0xFF;                            // move.b d0,d7  (depart)
        int d1 = d0 & 0xFF;                            // move.b d0,d1  (precedent)
        while (true) {
            do {                                       // .findnext
                d0 = setw(d0, d0 + 1);                 // addq #1,d0
                d0 = setw(d0, d0 & 15);                // and.w #15,d0
            } while (ring[at + (d0 & 0xFFFF)] == EMPTY);
            int d2 = ring[at + (d1 & 0xFFFF)] & 0xFF;  // move.b (a0,d1.w),d2
            int d3 = ring[at + (d0 & 0xFFFF)] & 0xFF;  // move.b (a0,d0.w),d3
            d3 = setw(d3, d3 - d2);                    // sub.w d2,d3 (le HAUT reste nul)
            int d4 = s16(s16(d0) - s16(d1));           // move.w d0,d4 ; sub.w d1,d4
            if (d4 <= 0) {                             // bgt.s .okpos
                d4 = s16(d4 + RING);                   // add.w #16,d4
            }
            // .okpos
            d2 = (d2 >>> 16) | (d2 << 16);             // swap d2
            d3 = (d3 >>> 16) | (d3 << 16);             // swap d3
            if (d3 != 0) {                             // beq .skip_zero_dividend
                int gap = d4 & 0xFFFF;                 // move.w d4,-2(sp)
                d4 = s16(ONE_OVER_N[gap]);             // move.w OneOverN_vw(pc,d4.w*2),d4 ; ext.l
                d3 = d3 >> 7;                          // asr.l #7,d3
                d3 = d3 * d4;                          // muls.l d4,d3
                d3 = d3 >> 7;                          // asr.l #7,d3
                d4 = setw(d4, gap);                    // move.w -2(sp),d4
            }
            // .skip_zero_dividend
            d4 = setw(d4, d4 - 1);                     // subq #1,d4
            while (true) {                             // .put_in_tween_loop
                int whole = (d2 >>> 16) | (d2 << 16);  // swap d2
                ring[at + (d1 & 0xFFFF)] = (byte) whole; // move.b d2,(a0,d1.w)
                d2 = d2 + d3;                          // swap d2 ; add.l d3,d2
                d1 = setw(d1, d1 + 1);                 // addq #1,d1
                d1 = setw(d1, d1 & 15);                // and.w #15,d1
                d4 = setw(d4, d4 - 1);
                if (s16(d4) == -1) {                   // dbra d4,.put_in_tween_loop
                    break;
                }
            }
            if ((d0 & 0xFF) == d7) {                   // cmp.b d0,d7 ; beq.s .done_all
                return;
            }
            d1 = setw(d1, d0);                         // move.w d0,d1
        }
    }

    /**
     * Les deux rangs des voisins sont retranches des deux premiers.
     *
     * <p>Port de {@code .sum_brights_loop} (1485). Tout s'y fait en OCTETS, y compris la
     * comparaison : c'est pourquoi une valeur qui deborde revient par l'autre bout.
     */
    private void sum() {
        for (int i = 0; i < RING; i++) {               // move.b #15,d0 ; dbra
            int d3 = ring[2 * RING + i] & 0xFF;        // move.b 32(a0),d3
            int d4 = ring[3 * RING + i] & 0xFF;        // move.b 48(a0),d4
            d3 = s16(s16(-d3) + SOLID);                // neg.w d3 ; add.w #48,d3
            d4 = s16(s16(-d4) + SOLID);                // neg.w d4 ; add.w #48,d4
            d4 = asrw(setw(0, d4), 1);                 // asr.w #1,d4
            d3 = asrw(setw(0, d3), 1);                 // asr.w #1,d3
            int d4b = (byte) (d4 - (ring[RING + i] & 0xFF)); // move.b 16(a0),d5 ; sub.b d5,d4
            if (d4b > 0) {                             // ble.s .ok2
                d4b = 0;                               // moveq #0,d4
            }
            // .ok2
            int d3b = (byte) (d3 - (ring[i] & 0xFF));  // move.b (a0),d5 ; sub.b d5,d3
            if (d3b > 0) {                             // ble.s .ok1
                d3b = 0;                               // moveq #0,d3
            }
            // .ok1
            ring[RING + i] = (byte) -d4b;              // neg.b d4 ; move.b d4,16(a0)
            ring[i] = (byte) -d3b;                     // neg.b d3 ; move.b d3,(a0)+
        }
    }

    /**
     * L'angle sous lequel on voit un point, par {@code HeadTowardsAng} (objectmove.s:1024).
     *
     * <p>La portee valant zero et la distance au moins un, le jeu passe toujours par la branche
     * qui AVANCE vers le point — ce qui ne change que le point vise, reecrit au tour suivant.
     * On n'en garde donc que l'angle.
     *
     * <p>Si le point est exactement sur l'objet, aucun angle n'est calcule et le precedent
     * reste : c'est une variable globale, pas une valeur de retour.
     */
    private void head(int oldX, int oldZ, int newX, int newZ) {
        int xdiff = s16(s16(newX) - s16(oldX));        // sub.w oldx,d1 ; move.w d1,xdiff
        int zdiff = s16(s16(newZ) - s16(oldZ));        // sub.w oldz,d2 ; move.w d2,zdiff
        int d2 = muls(zdiff, zdiff) + muls(xdiff, xdiff); // muls d1,d1 ; muls d2,d2 ; add.l d1,d2
        int d0 = 0;                                    // move.w #0,d0
        if (d2 != 0) {                                 // seq GotThere ; beq .nochange
            d0 = sqrt(d2);
        }
        if (s16(d0) == 0) {                            // tst.w d0 ; beq.s nocossin
            stale++;
            return;
        }
        d0 = s16(d0 + 1);                              // add.w #1,d0
        int sinRet = s16(divs((xdiff << 16) >> 1, d0)); // swap ; clr.w ; asr.l #1 ; divs d0,d1
        int cosRet = s16(divs((zdiff << 16) >> 1, d0));
        int d2a = 0;                                   // move.w #0,d2
        int d6 = SinCos.COSINE_OFFSET;                 // move.w #COSINE_OFS,d6
        for (int d5 = 3; d5 >= 0; d5--) {              // findanglop (dbra d5)
            int d3 = sinCos.value(d2a * 2);            // move.w (a2,d2.w*2),d3
            int d4 = sinCos.value(d2a * 2 + SinCos.COSINE_OFFSET); // move.w (a3,d2.w*2),d4
            d4 = muls(d4, sinRet);                     // muls d0,d4
            d3 = muls(d3, cosRet);                     // muls d1,d3
            if (d4 - d3 >= 0) {                        // sub.l d3,d4 ; blt.s subang
                d2a = s16(d2a + d6);                   // add.w d6,d2
                d2a = s16(d2a + d6);                   // add.w d6,d2
            }
            // subang
            d2a = s16(d2a - d6);                       // sub.w d6,d2
            d2a = d2a & 4095;                          // and.w #4095,d2
            d6 = asrw(setw(0, d6), 1);                 // asr.w #1,d6
        }
        angRet = s16(d2a + d2a);                       // add.w d2,d2 ; move.w d2,AngRet
    }

    /**
     * La racine carree entiere du jeu : trois pas de Newton depuis la puissance de deux la plus
     * proche. Jumelle de celle de {@code sim.Aliens}, portee de la meme routine.
     */
    private static int sqrt(int d2) {
        if (d2 == 0) {
            return 0;
        }
        int d0 = 31;                                   // move.w #31,d0
        while (d0 >= 0 && (d2 & (1 << d0)) == 0) {     // .findhigh : btst d0,d2 ; dbra
            d0--;
        }
        d0 = d0 >> 1;                                  // asr.w #1,d0
        d0 = 1 << d0;                                  // bset d0,d3
        for (int i = 0; i < 3; i++) {
            int d1 = s16(d0);
            d1 = muls(d1, d1);                         // muls d1,d1
            d1 -= d2;                                  // sub.l d2,d1
            d1 >>= 1;                                  // asr.l #1,d1
            d1 = divs(d1, d0);                         // divs d0,d1
            d0 = s16(d0 - d1);                         // sub.w d1,d0
            if (s16(d0) <= 0) {                        // bgt .stillnot0
                d0 = 1;                                // move.w #1,d0
            }
        }
        return s16(d0);
    }
}
