package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.level.Level;

import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;

/**
 * La transformation de vue : du plan du niveau a l'ecran.
 *
 * <p>C'est le premier maillon du rendu, et le plus sensible : tout ce qui suit — les murs, les
 * sols, les objets — se place a partir d'ici. Une erreur d'un bit ne fait pas une image un peu
 * fausse, elle fait une image fausse partout.
 *
 * <p>Le calcul tient en trois gestes par point. On le ramene d'abord dans le repere du joueur en
 * retranchant sa position ; on le TOURNE de l'angle de vue, ce qui donne une abscisse laterale
 * et une profondeur ; puis on divise l'une par l'autre, ce qui est toute la perspective. Le
 * resultat est une abscisse a l'ecran, et c'est la seule chose que le rendu gardera du plan.
 *
 * <p>Deux details qui n'en sont pas :
 *
 * <ul>
 *   <li>la profondeur subit une correction d'<b>aspect</b> en plein ecran — six cinquiemes,
 *       obtenus par une multiplication entiere plutot qu'une division — parce que les pixels de
 *       l'Amiga ne sont pas carres. En vue encadree, la correction est un simple decalage ;</li>
 *   <li>un point DERRIERE la camera ne se projette pas : le jeu le rabat sur un bord, a droite
 *       s'il etait a droite, a gauche sinon. Ce n'est pas un decoupage, c'est ce qui rend le
 *       decoupage possible plus tard.</li>
 * </ul>
 *
 * <p>Le jeu ne transforme d'ordinaire que les points qu'il va voir. On les transforme TOUS. Ce
 * n'est pas une approximation : les deux chemins du code d'origine appliquent exactement la meme
 * arithmetique, l'un sur un sous-ensemble, l'autre sur la totalite — seul le temps passe change.
 */
public final class View {

    /** La largeur de l'ecran, en plein ecran. */
    public static final int FULL_WIDTH = 320;
    /** La largeur de la vue encadree par le tableau de bord. */
    public static final int SMALL_WIDTH = 192;
    /** La hauteur de l'image, en plein ecran. */
    public static final int FULL_HEIGHT = 240;
    /** La hauteur de la vue encadree. */
    public static final int SMALL_HEIGHT = 160;

    /**
     * La correction d'aspect du plein ecran.
     *
     * <p>Le code d'origine veut multiplier la profondeur par six cinquiemes et n'a pas de
     * division a y consacrer : il multiplie par 1229 et retranche onze bits, ce qui vaut
     * 0,600097 au lieu de 0,6. On garde le chiffre et l'erreur, parce que c'est elle qui donne
     * sa geometrie a l'image.
     */
    private static final int ASPECT = 1229;

    private final SinCos sinCos;

    /** Largeur de la vue, et son milieu : l'abscisse ou se projette ce qui est droit devant. */
    private int rightX;
    private int centreX;
    private boolean fullScreen;

    /** Par point : son abscisse laterale et sa profondeur, apres rotation. */
    private int[] rotX = new int[0];
    private int[] rotZ = new int[0];
    /** Par point : son abscisse a l'ecran. */
    private int[] screenX = new int[0];

    public View(SinCos sinCos) {
        this.sinCos = sinCos;
        screen(true);
    }

    /** Choisit la vue : plein ecran, ou encadree par le tableau de bord. */
    public void screen(boolean plein) {
        this.fullScreen = plein;
        this.rightX = plein ? FULL_WIDTH : SMALL_WIDTH;
        this.centreX = rightX / 2;
    }

    public boolean fullScreen() {
        return fullScreen;
    }

    /** La hauteur de la vue courante. */
    public int height() {
        return fullScreen ? FULL_HEIGHT : SMALL_HEIGHT;
    }

    /** Le milieu de la vue : l'abscisse de ce qui est droit devant. */
    public int centreX() {
        return centreX;
    }

    /** Le bord droit de la vue. */
    public int rightX() {
        return rightX;
    }

    /** L'abscisse laterale du point apres rotation, avant perspective. */
    public int rotX(int point) {
        return rotX[point];
    }

    /** La profondeur du point : positive devant la camera, nulle ou negative derriere. */
    public int rotZ(int point) {
        return rotZ[point];
    }

    /** L'abscisse du point a l'ecran. */
    public int screenX(int point) {
        return screenX[point];
    }

    /** Vrai si le point est devant la camera, donc reellement projete. */
    public boolean inFront(int point) {
        return rotZ[point] > 0;
    }

    /**
     * Transforme tous les points du niveau pour une pose donnee.
     *
     * @param angle   l'orientation, en deplacement d'octets dans la table de sinus
     * @param xWobble le balancement lateral du pas, ajoute a l'abscisse de chaque point : c'est
     *                lui qui fait tanguer l'image quand on marche
     */
    public void update(Level level, int playerX, int playerZ, int angle, int xWobble) {
        int n = level.points().size();
        if (rotX.length < n) {
            rotX = new int[n];
            rotZ = new int[n];
            screenX = new int[n];
        }
        int sin = sinCos.sin(angle);
        int cos = sinCos.cos(angle);
        int px = s16(playerX);
        int pz = s16(playerZ);

        for (int i = 0; i < n; i++) {
            Level.Point p = level.points().get(i);
            int dx = s16(p.x() - px);                  // sub.w d4,d0
            int dz = s16(p.z() - pz);                  // sub.w d5,d1

            // x' = x*cos - z*sin, garde sur 32 bits puis ramene de huit : la rotation travaille
            // en virgule fixe et ne redescend qu'une fois les deux termes reunis.
            int x = (muls(dx, cos) - muls(dz, sin)) >> 8;
            x += xWobble;
            rotX[i] = x;

            // z' = x*sin + z*cos, puis la correction d'aspect.
            int z = muls(dx, sin) + muls(dz, cos);
            if (fullScreen) {
                // Le code d'origine decale de deux bits, ECHANGE les deux moities du registre
                // et multiplie le mot bas : il ne garde donc que les seize bits de poids fort
                // du decalage. On reproduit cette troncature, elle fait partie du resultat.
                z = ((s16((z << 2) >>> 16) * ASPECT) >> 8) >> 4;
            } else {
                z = (z >> 8) >> 7;
            }
            rotZ[i] = z;

            // La perspective, et le rabattement de ce qui est derriere. Une division dont le
            // quotient deborde laisse le dividende en place, et l'addition se fait sur un mot.
            int sx;
            if (z > 0) {
                sx = s16(divs(x, z) + centreX);
            } else if (x > 0) {
                sx = rightX;
            } else {
                sx = 0;
            }
            screenX[i] = sx;
        }
    }
}
