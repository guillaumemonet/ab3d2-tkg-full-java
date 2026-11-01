package ab3d2.full.render;

import ab3d2.full.level.Level;

import java.util.ArrayList;
import java.util.List;

import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

/**
 * Un pan de mur dont la luminosite varie du haut en bas, de la carte a l'ecran.
 *
 * <p>Un mur a QUATRE coins, et donc quatre luminosites. {@link Wall} n'en connait que deux :
 * quand le haut et le bas d'un meme bout s'accordent — a moins de trois niveaux pres — le jeu
 * les confond et dessine le pan avec une seule luminosite par colonne. Des qu'ils s'ecartent,
 * il passe ici.
 *
 * <p>La geometrie est la meme et les deux classes se la partagent : le tri de face
 * ({@link Wall#facingAway}), le nombre de coupes ({@link Wall#cuts}), le debordement de la
 * table d'iterations ({@link Wall#beyond}). Ce qui change tient en une phrase : une
 * <b>cinquieme valeur</b> voyage avec chaque point de coupe, la luminosite du HAUT, et elle
 * voyage accolee a la PROFONDEUR.
 *
 * <p>C'est la tout l'interet de porter ce chemin a part. Dans {@link Wall}, l'abscisse de
 * texture et la luminosite partagent un mot double et se contaminent au fil des moyennes ; ici
 * la profondeur et la luminosite du haut en partagent un AUSSI. La retenue de la luminosite
 * haute entre donc dans la profondeur, et le bit de poids faible de la profondeur descend dans
 * la luminosite haute — ou un masque le reprend aussitot. Le code d'origine masque ce mot a
 * {@code 0x7FFF} apres chaque coupe, et c'est ce masque, pas une precaution de portage, qui
 * empeche la luminosite de devenir negative et d'emprunter a la profondeur a la moyenne
 * suivante. Et il l'empeche si bien que la retenue ne traverse JAMAIS : deux luminosites
 * inferieures a seize mille ne peuvent pas porter leur somme au-dela d'un mot, de sorte que
 * moyenner les deux champs ensemble ou separement donne le meme resultat. Ce n'est pas une
 * approximation mais une egalite, et le masque en est la raison — le retirer casse tout.
 *
 * <p>Les quatre luminosites arrivent BIAISEES DE TROIS CENTS, comme l'eclairage les depose, et
 * ne gardent qu'un octet au moment de servir : voir {@link Wall} pour le detail, il est le
 * meme.
 */
public final class GouraudWall {

    /**
     * Une colonne d'ecran couverte par le mur, avec ses deux luminosites.
     *
     * <p>Les memes champs que {@link Wall.Strip}, plus celle du haut. Les deux luminosites sont
     * des accumulateurs en virgule fixe comme les autres : elles glissent d'une colonne a la
     * suivante, et c'est le placage qui en tire les deux bouts du degrade vertical.
     *
     * @param x           l'abscisse a l'ecran
     * @param u           ou lire la texture, en abscisse
     * @param distance    la distance au point de vue
     * @param top         le haut du pan sur cette colonne
     * @param bottom      le bas du pan
     * @param bright      la luminosite du BAS, doublee par le code d'origine
     * @param upperBright celle du HAUT, doublee de meme
     */
    public record Strip(int x, int u, int distance, int top, int bottom, int bright,
            int upperBright) {

        /** La partie entiere d'un accumulateur. */
        private static int whole(int accumulator) {
            return (short) (accumulator >> 16);
        }

        public int topY() {
            return whole(top);
        }

        public int bottomY() {
            return whole(bottom);
        }

        public int textureX() {
            return whole(u);
        }

        public int depth() {
            return whole(distance);
        }
    }

    /** Un sommet de la subdivision : trois mots doubles, comme dans {@link Wall}. */
    private static final int MAX_SEGMENTS = 600;

    private final int[] iterations;

    // Les deux tampons entre lesquels la subdivision fait des allers-retours. Le deuxieme porte
    // la profondeur ET la luminosite du haut, accolees.
    private int[] srcX = new int[MAX_SEGMENTS];
    private int[] srcZU = new int[MAX_SEGMENTS];
    private int[] srcUB = new int[MAX_SEGMENTS];
    private int[] dstX = new int[MAX_SEGMENTS];
    private int[] dstZU = new int[MAX_SEGMENTS];
    private int[] dstUB = new int[MAX_SEGMENTS];
    private int count;

    private final List<Strip> strips = new ArrayList<>();
    private final List<Strip> pending = new ArrayList<>();

    private int outOfTable;

    private int stripTop;
    private int stripBottom;

    private int topOfWall;
    private int bottomOfWall;
    private int centreX;
    private int centreY;
    private int rightX;
    private int leftClip;
    private int rightClip;
    private int steps;

    public GouraudWall(int[] iterations) {
        this.iterations = iterations;
    }

    /** Les bandes du dernier mur traite, de gauche a droite. */
    public List<Strip> strips() {
        return strips;
    }

    /** Combien de morceaux debordaient de la table d'iterations ; voir {@link Wall#beyond}. */
    public int outOfTable() {
        return outOfTable;
    }

    /**
     * Projette un pan de mur et en tire ses bandes.
     *
     * @param leftBright      la luminosite du coin BAS gauche
     * @param rightBright     celle du coin bas droit
     * @param leftUpperBright celle du coin HAUT gauche
     * @param rightUpperBright celle du coin haut droit
     */
    public void draw(View view, Level.Wall wall, int playerY, int centreY,
            int leftClip, int rightClip, int leftBright, int rightBright,
            int leftUpperBright, int rightUpperBright, boolean goodRender) {
        strips.clear();
        outOfTable = 0;

        this.topOfWall = wall.top() - playerY;
        this.bottomOfWall = wall.bottom() - playerY;
        if (topOfWall >= bottomOfWall) {
            return;                                    // le mur tourne le dos a la camera
        }
        this.centreX = view.centreX();
        this.centreY = centreY;
        this.rightX = view.rightX();
        this.leftClip = leftClip;
        this.rightClip = rightClip;

        int left = wall.leftPoint();
        int right = wall.rightPoint();
        int x1 = view.rotX(left);
        int z1 = s16(view.rotZ(left));
        int x2 = view.rotX(right);
        int z2 = s16(view.rotZ(right));

        if (Wall.facingAway(x1, z1, x2, z2, centreX, rightX)) {
            return;
        }

        Wall.Cuts coupes = Wall.cuts(x1, z1, x2, z2, goodRender);
        this.steps = coupes.steps();
        subdivide(x1, z1, x2, z2, 0, wall.length(), leftBright, rightBright,
                leftUpperBright, rightUpperBright, coupes.passes());
        walk(steps - 1);
    }

    // ===== la subdivision =====

    /**
     * Coupe le pan en deux, puis chaque moitie en deux, et ainsi de suite.
     *
     * <p>Comme dans {@link Wall}, le tout premier milieu est calcule champ par champ, et les
     * suivants deux champs a la fois. La difference est le masque qui suit chaque moyenne de la
     * paire profondeur/luminosite haute.
     */
    private void subdivide(int x1, int z1, int x2, int z2, int u1, int u2,
            int bright1, int bright2, int upper1, int upper2, int passes) {
        srcX[0] = x1;
        srcZU[0] = pack(z1, upper1);
        srcUB[0] = pack(u1, bright1);
        srcX[1] = (x1 + x2) >> 1;
        srcZU[1] = pack(s16(s16(z1 + z2)) >> 1, s16(s16(upper1 + upper2)) >> 1);
        srcUB[1] = pack(s16(s16(u1 + u2)) >> 1, s16(s16(bright1 + bright2)) >> 1);
        srcX[2] = x2;
        srcZU[2] = pack(z2, upper2);
        srcUB[2] = pack(u2, bright2);
        count = 3;

        for (int pass = 0; pass <= passes; pass++) {
            int out = 0;
            for (int i = 0; i + 1 < count; i++) {
                dstX[out] = srcX[i];
                dstZU[out] = srcZU[i];
                dstUB[out] = srcUB[i];
                out++;
                dstX[out] = (srcX[i] + srcX[i + 1]) >> 1;
                // Le masque : il efface le bit que la profondeur vient de laisser tomber dans
                // la luminosite haute, et avec lui toute chance qu'elle devienne negative —
                // donc toute chance qu'elle emprunte a la profondeur a la coupe suivante.
                int middle = (srcZU[i] + srcZU[i + 1]) >> 1;
                dstZU[out] = setw(middle, middle & 0x7FFF);
                dstUB[out] = (srcUB[i] + srcUB[i + 1]) >> 1;
                out++;
            }
            dstX[out] = srcX[count - 1];
            dstZU[out] = srcZU[count - 1];
            dstUB[out] = srcUB[count - 1];
            out++;

            int[] t;
            t = srcX; srcX = dstX; dstX = t;
            t = srcZU; srcZU = dstZU; dstZU = t;
            t = srcUB; srcUB = dstUB; dstUB = t;
            count = out;
        }
    }

    private static int pack(int high, int low) {
        return (high << 16) | (low & 0xFFFF);
    }

    private static int high(int packed) {
        return (short) (packed >> 16);
    }

    private static int low(int packed) {
        return (short) packed;
    }

    // ===== du plan a l'ecran, morceau par morceau =====

    /**
     * Parcourt les points de coupe et emet une bande d'ecran pour chaque morceau.
     *
     * <p>Comme {@link Wall}, a une bizarrerie pres qui lui est propre : tant qu'aucune bande
     * n'a ete emise, les abscisses sont comparees aux bords du cadre sur un mot DOUBLE ; des la
     * premiere emise, elles le sont sur un MOT. Le rendu simple, lui, les compare sur un mot
     * double de bout en bout. La difference ne se voit que sur un pan dont un bout se projette
     * au-dela de trente-deux mille colonnes, ce qui arrive quand un sommet frole le plan de la
     * camera — aucun des mille neuf cent quatre-vingt-douze morceaux du corpus de reference n'y
     * parvient, et les deux largeurs y rendent donc le meme verdict.
     */
    private void walk(int remaining) {
        int at = 0;

        while (high(srcZU[at]) <= 0) {
            at++;
            remaining--;
            if (remaining < 0) {
                return;                                // pas deux points devant : rien a voir
            }
        }
        int leftZ = high(srcZU[at]);
        int leftX = (int) ((long) srcX[at] / leftZ) + centreX;
        int leftU = high(srcUB[at]);
        int leftBright = low(srcUB[at]);
        int leftUpper = low(srcZU[at]);
        at++;

        stripTop = s16(divs(topOfWall, leftZ) + centreY);
        stripBottom = s16(divs(bottomOfWall, leftZ) + centreY);

        boolean aucuneEncore = true;
        while (true) {
            int rightZ = high(srcZU[at]);
            if (rightZ > 0) {
                int rightX = (int) ((long) srcX[at] / rightZ) + centreX;
                int leftTop = stripTop;
                int leftBottom = stripBottom;
                stripTop = s16(divs(topOfWall, rightZ) + centreY);
                stripBottom = s16(divs(bottomOfWall, rightZ) + centreY);
                srcX[at] = rightX;

                boolean dansLeCadre = aucuneEncore ? rightX >= leftClip
                        : (short) rightX >= leftClip;
                if (dansLeCadre) {
                    if (aucuneEncore ? leftX >= rightClip : (short) leftX >= rightClip) {
                        return;                        // tout le reste est hors du cadre
                    }
                    aucuneEncore = false;
                    band((short) leftX, (short) rightX,
                            leftU, high(srcUB[at]),
                            leftZ, rightZ,
                            leftTop, stripTop, leftBottom, stripBottom,
                            (byte) (leftBright - 300), (byte) (low(srcUB[at]) - 300),
                            (byte) (leftUpper - 300), (byte) (low(srcZU[at]) - 300));
                }
            }

            leftX = srcX[at];
            leftZ = high(srcZU[at]);
            leftU = high(srcUB[at]);
            leftBright = low(srcUB[at]);
            leftUpper = low(srcZU[at]);
            at++;
            remaining--;
            if (remaining < 0) {
                return;
            }
        }
    }

    /**
     * Un morceau de mur : une bande d'ecran a remplir colonne par colonne.
     *
     * <p>Sept accumulateurs au lieu de cinq, et pas une division. Le reste — le saut des pas
     * qui retombent dans la meme colonne, et la perte du morceau entier quand c'est le DERNIER
     * pas qui y retombe — est mot pour mot ce que fait {@link Wall}.
     */
    private void band(int leftX, int rightX, int leftU, int rightU, int leftDist, int rightDist,
            int leftTop, int rightTop, int leftBottom, int rightBottom,
            int leftBright, int rightBright, int leftUpper, int rightUpper) {
        int width = s16((rightX & 0xFFFF) - (leftX & 0xFFFF));
        if (width < 0) {
            return;
        }

        int table;
        if (width < iterations.length) {
            table = iterations[width];
        } else {
            table = Wall.beyond(width);
            outOfTable++;
        }
        int shift = (short) table;
        int steps = (short) (table >> 16);

        int x = (leftX & 0xFFFF) << 16;
        int dx = (width << 16) >> shift;
        int u = (leftU & 0xFFFF) << 16;
        int du = (s16(rightU - leftU) << 16) >> shift;
        int dist = (leftDist & 0xFFFF) << 16;
        int dDist = (s16(rightDist - leftDist) << 16) >> shift;
        int top = (leftTop & 0xFFFF) << 16;
        int dTop = (s16(rightTop - leftTop) << 16) >> shift;
        int bottom = (leftBottom & 0xFFFF) << 16;
        int dBottom = (s16(rightBottom - leftBottom) << 16) >> shift;
        // Les deux luminosites sont portees au double, pour garder un demi-niveau de precision.
        int dBright = (s16((rightBright - leftBright) * 2) << 16) >> shift;
        int bright = s16(leftBright * 2) << 16;
        int dUpper = (s16((rightUpper - leftUpper) * 2) << 16) >> shift;
        int upper = s16(leftUpper * 2) << 16;

        pending.clear();
        int last = s16(leftClip - 1);
        int remaining = steps;
        while (true) {
            int column = (short) (x >> 16);
            boolean drawn = column > last;
            if (drawn) {
                last = column;
                if (column >= rightClip) {
                    strips.addAll(pending);            // on garde ce qui precede le bord
                    return;
                }
                pending.add(new Strip(column, u, dist, top, bottom, bright, upper));
            }
            x += dx;
            u += du;
            dist += dDist;
            top += dTop;
            bottom += dBottom;
            bright += dBright;
            upper += dUpper;
            remaining--;
            if (remaining < 0) {
                if (drawn) {
                    strips.addAll(pending);
                }
                return;                                // sinon le morceau entier est perdu
            }
        }
    }
}
