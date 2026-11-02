package ab3d2.full.render;

/**
 * La table des pas de texture, celle que le jeu fabrique au demarrage et ne rouvre jamais.
 *
 * <p>Plaquer une image revient a avancer dans la texture d'un pas constant par pixel d'ecran.
 * Ce pas est une division — combien de texels pour combien de pixels —, et le jeu refuse de la
 * faire en cours de dessin. Il prepare donc, une fois pour toutes, huit mille cent
 * quatre-vingt-douze pas tout faits, et se contente ensuite d'y entrer par un index.
 *
 * <p>Chaque entree en porte DEUX valeurs, et la seconde est la plus curieuse :
 *
 * <ul>
 *   <li>le PAS, en virgule fixe a seize bits de fraction ;</li>
 *   <li>un decalage de depart, obtenu en prenant la moitie du rapport, en lui otant deux mille
 *       cinq cent soixante, puis en le multipliant par le pas. Deux mille cinq cent soixante,
 *       c'est quarante fois soixante-quatre — la demi-largeur de l'ecran en quarts de pixel.
 *       Cette valeur recentre donc le plaquage sur le milieu de l'ecran, et le jeu s'en sert
 *       pour les sols et les murs degrades.</li>
 * </ul>
 *
 * <p>La table se remplit pour des diviseurs de un a huit mille cent quatre-vingt-douze, mais
 * elle commence a l'index ZERO : l'entree {@code i} vaut pour le diviseur {@code i + 1}. Le
 * rendu, qui calcule un rapport puis entre directement dans la table, demande donc toujours un
 * pas un peu plus grand qu'il ne le croit.
 *
 * <p>Les deux divisions sont ENTIERES et se suivent : on divise, on arrondit, on redivise le
 * resultat arrondi. Le pas n'est donc pas la fraction exacte, et c'est cette double troncature
 * qu'il faut reproduire — pas la formule qu'elle approche.
 */
public final class ScaleTable {

    /** Combien d'entrees. */
    public static final int SIZE = 8192;

    /** Le numerateur du rapport : seize mille trois cent quatre-vingt-quatre fois soixante-quatre. */
    private static final int RATIO = 16384 * 64;

    /** Le numerateur du pas : soixante-quatre au carre, en virgule fixe. */
    private static final long STEP = 64L * 64 * 65536;

    /** La demi-largeur de l'ecran, en quarts de pixel. */
    private static final int HALF_SCREEN = 40 * 64;

    private final int[] step = new int[SIZE];
    private final int[] start = new int[SIZE];

    public ScaleTable() {
        for (int i = 0; i < SIZE; i++) {
            int divisor = i + 1;                       // moveq #1,d0 ; addq #1,d0 a chaque tour
            int ratio = RATIO / divisor;               // divs.l d0,d2
            int pas = (int) (STEP / ratio);            // divs.l d2,d3
            step[i] = pas;
            int moitie = (ratio >> 1) - HALF_SCREEN;   // asr.l #1,d2 ; sub.l #40*64,d2
            start[i] = (moitie * pas) >> 6;            // muls.l d3,d2 ; asr.l #6,d2
        }
    }

    /** Le pas de texture de l'entree {@code i}, en virgule fixe a seize bits. */
    public int step(int i) {
        return step[i];
    }

    /** Le decalage de depart de l'entree {@code i}. */
    public int start(int i) {
        return start[i];
    }
}
