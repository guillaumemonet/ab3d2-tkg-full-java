package ab3d2.full.render;

import ab3d2.full.level.Level;

import java.util.ArrayList;
import java.util.List;

import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.s16;

/**
 * Un pan de mur, de la carte a l'ecran.
 *
 * <p>Le pan est un segment vertical : deux sommets du plan, un haut et un bas communs. Le
 * projeter devrait etre une affaire de deux divisions — sauf que le haut et le bas d'un mur ne
 * varient pas lineairement d'un bout a l'autre, et qu'interpoler en ligne droite entre les deux
 * bouts tordrait les murs vus de biais.
 *
 * <p>Le jeu s'en tire sans jamais diviser par colonne : il <b>coupe le pan en deux, puis encore
 * en deux</b>, jusqu'a ce que chaque morceau soit assez court pour qu'une droite y passe sans
 * qu'on le voie. Chaque coupe est une moyenne — une addition et un decalage — et seuls les
 * points de coupe coutent une division. Entre eux, tout est interpole.
 *
 * <p>Combien de coupes ? Cela depend de ce qu'on regarde : un mur large a l'ecran, ou tres
 * oblique, ou tres proche, en demande davantage. Une quinzaine de comparaisons en decident, et
 * elles sont aussi arbitraires que le resultat est juste — c'est un reglage, pas une formule.
 *
 * <p>Deux choses ne se devinent pas :
 *
 * <ul>
 *   <li>l'abscisse de texture et la luminosite sont rangees COTE A COTE dans un meme mot double,
 *       et les coupes successives les moyennent <b>ensemble</b>. La retenue de l'une deborde
 *       donc sur l'autre. Ce n'est pas un accident qu'on puisse nettoyer : les deux valeurs
 *       sortent legerement liees, et le rendu les separe a la fin en ne gardant qu'un octet de
 *       la luminosite ;</li>
 *   <li>la toute premiere coupe, elle, les moyenne separement. Le code d'origine la fait a la
 *       main avant d'entrer dans la boucle, et ne les accole qu'ensuite.</li>
 * </ul>
 *
 * <p>Ce que cette classe rend n'est pas une image mais la <b>geometrie</b> : une bande par
 * colonne d'ecran, avec son haut, son bas, sa distance et ou lire la texture. Le placage des
 * pixels vient apres.
 */
public final class Wall {

    /**
     * Une colonne d'ecran couverte par le mur.
     *
     * <p>Toutes les valeurs sauf {@code x} sont des accumulateurs en virgule fixe, dont la
     * partie entiere est le mot de poids fort : c'est ainsi que le rendu les additionne d'une
     * colonne a la suivante sans jamais arrondir.
     *
     * @param x        l'abscisse a l'ecran
     * @param u        ou lire la texture, en abscisse
     * @param distance la distance au point de vue, qui servira a l'eclairage
     * @param top      le haut du pan sur cette colonne
     * @param bottom   le bas du pan
     * @param bright   la luminosite, doublee par le code d'origine
     */
    public record Strip(int x, int u, int distance, int top, int bottom, int bright) {

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

    /** Un sommet de la subdivision : trois mots doubles. */
    private static final int MAX_SEGMENTS = 600;

    /** La table qui dit, pour une largeur a l'ecran, en combien de pas la parcourir. */
    private final int[] iterations;

    // Les deux tampons entre lesquels la subdivision fait des allers-retours.
    private int[] srcX = new int[MAX_SEGMENTS];
    private int[] srcZ = new int[MAX_SEGMENTS];
    private int[] srcUB = new int[MAX_SEGMENTS];
    private int[] dstX = new int[MAX_SEGMENTS];
    private int[] dstZ = new int[MAX_SEGMENTS];
    private int[] dstUB = new int[MAX_SEGMENTS];
    private int count;

    private final List<Strip> strips = new ArrayList<>();
    private final List<Strip> pending = new ArrayList<>();

    /** Combien de morceaux du dernier pan debordaient de la table d'iterations. */
    private int outOfTable;

    // L'etat que le rendu promene d'un morceau au suivant : le morceau courant reprend le haut
    // et le bas la ou le precedent les a laisses, sans les recalculer.
    private int stripTop;
    private int stripBottom;

    // La pose, posee une fois par mur.
    private int topOfWall;
    private int bottomOfWall;
    private int centreX;
    private int centreY;
    private int rightX;
    private int leftClip;
    private int rightClip;

    public Wall(int[] iterations) {
        this.iterations = iterations;
    }

    /** Les bandes du dernier mur traite, de gauche a droite. */
    public List<Strip> strips() {
        return strips;
    }

    /**
     * Combien de morceaux du dernier pan etaient plus larges que la table ne le prevoit.
     *
     * <p>Zero presque toujours. Quand ce n'est pas zero, ce pan est le seul endroit du rendu ou
     * l'on s'ecarte sciemment du code d'origine : voir {@link #beyond}.
     */
    public int outOfTable() {
        return outOfTable;
    }

    /**
     * Projette un pan de mur et en tire ses bandes.
     *
     * @param playerY     la hauteur de l'oeil, que le haut et le bas du pan lui sont relatifs
     * @param centreY     l'horizon, qui descend ou monte quand le joueur leve les yeux
     * @param leftClip    la fenetre de dessin du secteur, bornes de {@link ZoneClip}
     * @param leftBright  la luminosite du coin gauche, celle du coin droit
     * @param goodRender  le reglage de finesse : il multiplie le nombre de coupes
     */
    public void draw(View view, Level.Wall wall, int playerY, int centreY,
            int leftClip, int rightClip, int leftBright, int rightBright, boolean goodRender) {
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

        if (facingAway(x1, z1, x2, z2, centreX, rightX)) {
            return;
        }

        Cuts coupes = cuts(x1, z1, x2, z2, goodRender);
        this.steps = coupes.steps();
        subdivide(x1, z1, x2, z2, 0, wall.length(), leftBright, rightBright, coupes.passes());
        walk(steps - 1);
    }

    // ===== le mur est-il face a nous ? =====

    /**
     * Le pan tourne-t-il le dos a la camera, ou tombe-t-il entierement hors du cadre ?
     *
     * <p>Ce tri precede le choix du rastériseur : le rendu simple et le rendu <i>gouraud</i>
     * s'en remettent au meme, comme le code d'origine qui le fait avant de se brancher.
     */
    static boolean facingAway(int x1, int z1, int x2, int z2, int centreX, int rightX) {
        if (z1 > 0) {
            if (z2 <= 0) {
                return rightBehind(x1, z1, x2, z2);
            }
            return crossedOver(x1, z1, x2, z2, centreX, rightX);
        }
        if (z2 <= 0) {
            return true;                               // les deux bouts derriere la camera
        }
        return leftBehind(x1, z1, x2, z2);
    }

    /**
     * Le bout gauche est derriere la camera : on coupe le segment sur le plan de la camera et
     * l'on regarde de quel cote le morceau restant tombe.
     */
    static boolean leftBehind(int x1, int z1, int x2, int z2) {
        int cut = (int) ((long) (x1 - x2) / s16(z1 - z2));
        cut = -(cut * z2) + x2;
        return cut >= (int) ((long) x2 / z2);
    }

    /** Le bout droit est derriere la camera : la meme chose, de l'autre cote. */
    static boolean rightBehind(int x1, int z1, int x2, int z2) {
        int cut = (int) ((long) (x2 - x1) / s16(z2 - z1));
        cut = -(cut * z1) + x1;
        return cut <= (int) ((long) x1 / z1);
    }

    /**
     * Les deux bouts sont devant : le mur ne compte que si son bout gauche se projette bien a
     * gauche du droit, et dans l'ecran.
     *
     * <p>Un bout gauche a droite du bout droit veut dire qu'on voit le mur par derriere.
     */
    static boolean crossedOver(int x1, int z1, int x2, int z2, int centreX, int rightX) {
        int screenLeft = (int) ((long) x1 / z1) + centreX;
        int screenRight = (int) ((long) x2 / z2) + centreX;
        if (screenLeft >= rightX) {
            return true;
        }
        if (screenLeft >= screenRight) {
            return true;
        }
        return screenRight < 0;
    }

    // ===== combien de coupes =====

    /** Le nombre de pas que la subdivision produira, pose par {@link #cuts}. */
    private int steps;

    /**
     * Ce que {@link #cuts} decide : combien de fois couper, et combien de pas en sortiront.
     *
     * @param passes combien de fois le pan est coupe en deux
     * @param steps  combien de pas la bande d'ecran comptera
     */
    record Cuts(int passes, int steps) { }

    /**
     * Combien de fois couper le pan en deux.
     *
     * <p>Quinze comparaisons, qui montent la finesse quand le mur est large a l'ecran, tres
     * oblique ou tres proche, et la baissent quand il est loin. Les deux compteurs avancent
     * ensemble : {@code steps} vaut toujours deux puissance {@code passes + 1}.
     *
     * <p>Le rendu <i>gouraud</i> se branche apres ce calcul et le partage tel quel : voir
     * {@link GouraudWall}.
     */
    static Cuts cuts(int x1, int z1, int x2, int z2, boolean goodRender) {
        int step = 16;
        int pass = 2;
        if (goodRender) {
            step = 64;
            pass = 4;
        } else {
            int width = x2 - x1;
            if (width < 0) {
                width = -width;
            }
            if (width >= 256 * 128) {
                step += step;
                pass += 1;
                if (width >= 512 * 128) {
                    step += step;
                    pass += 1;
                }
            }
        }

        int slant = s16(z2 - z1);
        if (slant < 0) {
            slant = s16(-slant);
        }
        if (slant >= 512 && goodRender) {
            step += step;
            pass = s16(pass + 1);
        } else {
            if (slant <= 256) {
                step = s16(step) >> 1;
                pass = s16(pass - 1);
            }
            if (slant <= 128) {
                step = s16(step) >> 1;
                pass = s16(pass - 1);
            }
        }

        int near = z2 < z1 ? z1 : z2;
        if (near <= 32) {
            pass = s16(pass + 1);
            step += step;
        }
        if (near <= 64) {
            pass = s16(pass + 1);
            step += step;
        }
        boolean floored = false;
        if (near >= 128) {
            step = s16(step) >> 1;
            pass = s16(pass - 1);
            if (pass < 0) {
                floored = true;
            } else if (near >= 256) {
                step = s16(step) >> 1;
                pass = s16(pass - 1);
                if (pass < 0) {
                    floored = true;
                }
            }
        }
        if (!floored && near >= 512) {
            step = s16(step) >> 1;
            pass = s16(pass - 1);
        }
        if (s16(step) > 128) {
            step = 128;
            pass = 5;
        }
        return new Cuts(pass, s16(step));
    }

    // ===== la subdivision =====

    /**
     * Coupe le pan en deux, puis chaque moitie en deux, et ainsi de suite.
     *
     * <p>On part de trois points — les deux bouts et leur milieu — et chaque passe en intercale
     * un entre chaque paire. Le milieu de depart est calcule champ par champ ; les suivants le
     * sont deux champs a la fois, texture et luminosite accolees dans un meme mot double. C'est
     * cette difference qui fait que le premier milieu n'a pas la retenue des autres.
     */
    private void subdivide(int x1, int z1, int x2, int z2, int u1, int u2,
            int bright1, int bright2, int passes) {
        srcX[0] = x1;
        srcZ[0] = z1;
        srcUB[0] = pack(u1, bright1);
        srcX[1] = (x1 + x2) >> 1;
        srcZ[1] = s16(s16(z1 + z2)) >> 1;
        srcUB[1] = pack(s16(s16(u1 + u2)) >> 1, s16(s16(bright1 + bright2)) >> 1);
        srcX[2] = x2;
        srcZ[2] = z2;
        srcUB[2] = pack(u2, bright2);
        count = 3;

        for (int pass = 0; pass <= passes; pass++) {
            int out = 0;
            for (int i = 0; i + 1 < count; i++) {
                dstX[out] = srcX[i];
                dstZ[out] = srcZ[i];
                dstUB[out] = srcUB[i];
                out++;
                dstX[out] = (srcX[i] + srcX[i + 1]) >> 1;
                dstZ[out] = s16(s16(srcZ[i] + srcZ[i + 1])) >> 1;
                dstUB[out] = (srcUB[i] + srcUB[i + 1]) >> 1;
                out++;
            }
            dstX[out] = srcX[count - 1];
            dstZ[out] = srcZ[count - 1];
            dstUB[out] = srcUB[count - 1];
            out++;

            int[] t;
            t = srcX; srcX = dstX; dstX = t;
            t = srcZ; srcZ = dstZ; dstZ = t;
            t = srcUB; srcUB = dstUB; dstUB = t;
            count = out;
        }
    }

    private static int pack(int u, int bright) {
        return (u << 16) | (bright & 0xFFFF);
    }

    /**
     * Ce que la table dirait si elle allait plus loin.
     *
     * <p>Elle ne porte que cinq cent douze largeurs, et un morceau de mur peut en depasser :
     * il suffit qu'un de ses bouts se projette tres loin a gauche pendant que l'autre reste
     * dans le cadre. Le code d'origine lit alors AU-DELA de sa table, c'est-a-dire ce qui s'y
     * trouve ; dans le portage de reference ce sont des zeros, et le morceau disparait tout
     * entier, laissant un trou dans le mur.
     *
     * <p>Il n'y a rien a reproduire la : ce que lit le jeu d'origine depend de ce que
     * l'assembleur avait range a cet endroit, et le portage n'a pas la meme disposition. On
     * prolonge donc la regle que la table suit partout ailleurs — la puissance de deux
     * immediatement superieure a la largeur — ce qui redonne un pas inferieur a la colonne,
     * donc un mur sans trou. C'est le SEUL endroit du rendu des murs ou l'on s'ecarte, et
     * {@link #outOfTable()} le compte.
     */
    static int beyond(int width) {
        int shift = 0;
        while ((1 << shift) < width) {
            shift++;
        }
        return (((1 << shift) - 1) << 16) | shift;
    }

    private static int unpackU(int packed) {
        return (short) (packed >> 16);
    }

    private static int unpackBright(int packed) {
        return (short) packed;
    }

    // ===== du plan a l'ecran, morceau par morceau =====

    /**
     * Parcourt les points de coupe et emet une bande d'ecran pour chaque morceau.
     *
     * <p>Les points derriere la camera sont sautes jusqu'au premier qui soit devant ; apres
     * quoi chaque paire de points consecutifs devient un morceau. Un morceau qui tombe
     * entierement a gauche de la fenetre est saute, et le premier qui commence au-dela du bord
     * droit arrete tout, les suivants etant plus a droite encore.
     *
     * <p>L'abscisse d'ecran d'un point de coupe est REECRITE par-dessus son abscisse du plan :
     * le morceau suivant la reprend telle quelle au lieu de refaire la division. Un point
     * derriere la camera n'est jamais reecrit, et le morceau qui le suit se retrouve donc a
     * comparer une abscisse du plan a une borne d'ecran. C'est ce que fait le code d'origine.
     */
    private void walk(int remaining) {
        int at = 0;

        // Le premier point devant la camera.
        while (s16(srcZ[at]) <= 0) {
            at++;
            remaining--;
            if (remaining < 0) {
                return;                                // pas deux points devant : rien a voir
            }
        }
        int leftZ = s16(srcZ[at]);
        int leftX = (int) ((long) srcX[at] / leftZ) + centreX;
        int leftU = unpackU(srcUB[at]);
        int leftBright = unpackBright(srcUB[at]);
        at++;

        stripTop = s16(divs(topOfWall, leftZ) + centreY);
        stripBottom = s16(divs(bottomOfWall, leftZ) + centreY);

        while (true) {
            int rightZ = s16(srcZ[at]);
            if (rightZ > 0) {
                int rightX = (int) ((long) srcX[at] / rightZ) + centreX;
                int leftTop = stripTop;
                int leftBottom = stripBottom;
                stripTop = s16(divs(topOfWall, rightZ) + centreY);
                stripBottom = s16(divs(bottomOfWall, rightZ) + centreY);
                srcX[at] = rightX;

                if (rightX >= leftClip) {
                    if (leftX >= rightClip) {
                        return;                        // tout le reste est hors du cadre
                    }
                    // Le haut et le bas sont ranges dans des MOTS, et la luminosite n'y garde
                    // qu'un octet : le decalage de trois cents la ramene autour de zero, et
                    // l'extension de l'octet efface ce que la subdivision y a fait deborder.
                    band((short) leftX, (short) rightX,
                            leftU, unpackU(srcUB[at]),
                            leftZ, rightZ,
                            leftTop, stripTop, leftBottom, stripBottom,
                            (byte) (leftBright - 300), (byte) (unpackBright(srcUB[at]) - 300));
                }
            }

            leftX = srcX[at];
            leftZ = s16(srcZ[at]);
            leftU = unpackU(srcUB[at]);
            leftBright = unpackBright(srcUB[at]);
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
     * <p>Rien n'y est divise. Chaque ecart devient un pas en virgule fixe, et l'on avance. Le
     * nombre de pas vient de la table d'iterations, qui donne pour chaque largeur la puissance
     * de deux immediatement superieure — ce qui permet au pas d'etre un simple decalage.
     *
     * <p>Plusieurs pas peuvent tomber dans la meme colonne quand le morceau est etroit : ceux-la
     * sont sautes. Et si le DERNIER pas y tombe, le code d'origine sort sans rien dessiner du
     * tout, perdant le morceau entier — on le reproduit, meme si le cas ne s'est presente
     * aucune fois sur les deux cent mille bandes du corpus de reference.
     */
    private void band(int leftX, int rightX, int leftU, int rightU, int leftDist, int rightDist,
            int leftTop, int rightTop, int leftBottom, int rightBottom,
            int leftBright, int rightBright) {
        int width = s16((rightX & 0xFFFF) - (leftX & 0xFFFF));
        if (width < 0) {
            return;
        }

        int table;
        if (width < iterations.length) {
            table = iterations[width];
        } else {
            table = beyond(width);
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
        // La luminosite est portee au double, pour garder un demi-niveau de precision.
        int dBright = (s16((rightBright - leftBright) * 2) << 16) >> shift;
        int bright = s16(leftBright * 2) << 16;

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
                pending.add(new Strip(column, u, dist, top, bottom, bright));
            }
            x += dx;
            u += du;
            dist += dDist;
            top += dTop;
            bottom += dBottom;
            bright += dBright;
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
