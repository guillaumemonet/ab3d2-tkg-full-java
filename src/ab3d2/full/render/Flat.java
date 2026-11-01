package ab3d2.full.render;

import ab3d2.full.level.Level;

import java.util.ArrayList;
import java.util.List;

import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.divsReg;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.mulu;
import static ab3d2.full.M68k.s16;

/**
 * Une surface horizontale — sol, plafond ou plan d'eau — de la carte a l'ecran.
 *
 * <p>Un mur se projette colonne par colonne ; une surface horizontale, LIGNE par ligne. Et la
 * difference n'est pas qu'un changement d'axe : sur une ligne d'ecran donnee, un sol est a
 * distance CONSTANTE du point de vue. Toute la ligne partage donc une seule distance, un seul
 * eclairage, un seul pas de texture — et c'est ce qui rend les sols tenables sur un 68000.
 *
 * <p>Le travail se fait en deux temps, et les deux sont inhabituels :
 *
 * <ol>
 *   <li>on <b>trace le contour</b> du polygone a l'ecran. Pas pour le dessiner : pour remplir
 *       deux tables, une d'abscisses gauches et une d'abscisses droites, une case par ligne
 *       d'ecran. Chaque arete sait de quel cote elle tombe — si son bout bas est a droite de son
 *       bout haut, elle borne a droite — et les deux tables se remplissent par un trace de
 *       segment entier, sans aucune division par ligne ;</li>
 *   <li>on <b>balaie les lignes</b> entre le haut et le bas ainsi obtenus. Pour chacune, la
 *       distance se lit dans une table d'inverses, et la position de depart dans la texture
 *       vient d'une rotation du regard. Rien ne se divise ici non plus.</li>
 * </ol>
 *
 * <p>Trois choses ne se devinent pas :
 *
 * <ul>
 *   <li>la hauteur du sol au-dessus de l'oeil est multipliee par <b>107</b> en plein ecran, la
 *       ou la vue encadree se contente d'un decalage de six bits. C'est la meme correction
 *       d'aspect que {@link View}, posee autrement ;</li>
 *   <li>cette hauteur corrigee est ensuite TRONQUEE A UN MOT avant de rencontrer la table
 *       d'inverses, parce que la multiplication d'origine est une multiplication de mots. Un sol
 *       tres haut au-dessus de la tete rebouclera donc, et c'est visible ;</li>
 *   <li>un polygone dont aucun sommet n'est devant la camera n'est pas forcement invisible : s'il
 *       a des sommets a gauche ET a droite de la fenetre, il la traverse. Le code d'origine le
 *       decide par un OU EXCLUSIF entre deux drapeaux, ce qui est exactement ce test.</li>
 * </ul>
 *
 * <p>Ce que cette classe rend n'est pas une image mais la <b>geometrie</b> : une tranche par
 * ligne d'ecran, avec ses deux bords, sa distance, son eclairage et ou lire la texture. Le
 * placage des pixels vient apres, et il differe selon la surface — l'eau fait onduler la ligne
 * avant de la lire, le sol la lit telle quelle.
 *
 * <p>Deux chemins du code d'origine ne sont PAS portes ici. Le premier est le rendu <i>gouraud</i>
 * des sols, qui interpole l'eclairage entre les sommets au lieu de le deduire de la distance :
 * il lit les luminosites de points que l'eclairage dynamique calcule, et il viendra avec lui —
 * exactement comme pour les murs. Le second est le mode demi-hauteur, une variante de resolution
 * que ce depot ne cible pas.
 */
public final class Flat {

    /**
     * Une ligne d'ecran couverte par la surface.
     *
     * @param y        la ligne a l'ecran
     * @param left     l'abscisse ou la ligne commence, incluse
     * @param right    celle ou elle finit, exclue
     * @param distance la distance au point de vue, commune a toute la ligne
     * @param bright   le rang de la table d'ombrage, de 0 a 28
     * @param texture  ou lire la texture au depart de la ligne : deux accumulateurs en virgule
     *                 fixe accoles dans un meme mot double, l'ordonnee en haut et l'abscisse en
     *                 bas
     * @param step     de combien avancer dans la texture a chaque pixel, range de meme
     */
    public record Span(int y, int left, int right, int distance, int bright,
            int texture, int step) {

        /** La largeur de la tranche, en pixels. */
        public int width() {
            return right - left;
        }
    }

    /** Le milieu de la table d'ombrage, le plus clair qu'une ligne puisse etre. */
    private static final int MAX_BRIGHT = 28;

    /**
     * La correction d'aspect du plein ecran, posee ici en deux temps.
     *
     * <p>La hauteur de l'oeil au-dessus du sol est multipliee par {@link #ASPECT_HEIGHT}, et
     * l'abscisse de depart dans la texture par {@link #ASPECT_LEFT} avant d'etre ramenee de onze
     * bits. Ce sont deux facons d'ecrire six cinquiemes, l'une comme l'autre approchees.
     */
    private static final int ASPECT_HEIGHT = 107;
    private static final int ASPECT_LEFT = 1229;

    /** Le facteur qui ramene le pas de texture a l'echelle du plein ecran. */
    private static final int ASPECT_STEP = 77;

    /**
     * Les deux tables d'abscisses, telles qu'elles se suivent en memoire.
     *
     * <p>Le code d'origine leur reserve mille vingt-quatre lignes chacune et n'en verifie
     * aucune. Un sommet qui se projette tres bas deborde donc de la premiere dans la seconde, et
     * ce debordement fait partie du resultat : on garde les deux bout a bout pour le reproduire.
     * Ce qui deborde des DEUX, en revanche, tomberait dans les tables de luminosite du rendu
     * gouraud, qui ne sont pas portees : on le compte plutot que de l'ecrire.
     */
    private static final int TABLE = 1024;
    private static final int LEFT = 0;
    private static final int RIGHT = TABLE;
    private final int[] edges = new int[2 * TABLE];

    /**
     * La table des inverses : seize mille trois cent quatre-vingt-quatre divise par le rang.
     *
     * <p>Le rang est un nombre de lignes d'ecran, donc toujours plus petit que la hauteur de
     * l'image. Le jeu ne verifie rien et n'a pas a le faire.
     */
    private static final int[] ONE_OVER_N = new int[512];

    static {
        for (int n = 1; n < ONE_OVER_N.length; n++) {
            ONE_OVER_N[n] = 16384 / n;
        }
    }

    private final List<Span> spans = new ArrayList<>();

    // La pose, posee une fois par image.
    private View view;
    private int playerX;
    private int playerZ;
    private int floorYOff;
    private int wobbleX;
    private int wobbleZ;
    private int sin;
    private int cos;
    private int centreY;
    private int bottomY;
    private int topClip;
    private int bottomClip;

    // Le secteur en cours, pose une fois par secteur.
    private int topOfRoom;
    private int bottomOfRoom;
    private int zoneBright;
    private int leftClip;
    private int rightClip;
    private boolean ownZone;

    // L'etat d'une surface, du trace du contour au balayage des lignes.
    private int minZ;
    private int bottomLine;
    private int yPos;
    private int eyeToFloor;
    private int top;
    private int bottom;
    private boolean drawIt;
    private boolean anyClipping;
    private int leftEdge;
    private int rightEdge;
    private int offTable;

    /** La teinte d'eau plein ecran, que le balayage pose quand il renonce a dessiner. */
    private int waterTint;

    /** Vrai si le niveau a de l'eau du tout : sans cela, aucun plan d'eau ne se dessine. */
    private boolean anyWater = true;

    /**
     * Pose le point de vue, pour toute une image.
     *
     * @param playerY   la hauteur de l'oeil, au deux-cent-cinquante-sixieme
     * @param wobbleX   le balancement du pas, deja projete : il deplace le point de vue
     *                  PERPENDICULAIREMENT au regard, et c'est pourquoi il s'ajoute en croix
     * @param centreY   l'horizon, qui monte et descend quand le joueur leve les yeux
     * @param margin    les bandes noires en haut et en bas de l'image, un reglage du joueur
     */
    public void eye(View view, int playerX, int playerZ, int playerY,
            int wobbleX, int wobbleZ, int sin, int cos, int centreY, int margin) {
        this.view = view;
        this.playerX = playerX;
        this.playerZ = playerZ;
        this.floorYOff = s16(playerY >> 6);            // move.l Plr_YOff_l,d0 ; asr.l #6,d0
        this.wobbleX = wobbleX;
        this.wobbleZ = wobbleZ;
        this.sin = sin;
        this.cos = cos;
        this.centreY = centreY;
        this.bottomY = view.height();
        this.topClip = margin;
        this.bottomClip = s16(bottomY - margin);
    }

    /**
     * Pose le secteur en cours de dessin.
     *
     * @param topOfRoom    le plafond et le plancher du secteur, qui bornent ce qu'il peut
     *                     montrer : une surface en dehors appartient a un autre etage
     * @param bright       la luminosite du secteur, qui s'ajoute a celle de la surface
     * @param leftClip     la fenetre de dessin du secteur, bornes de {@link ZoneClip}
     * @param ownZone      vrai si c'est le secteur ou le joueur se tient, ce qui decide seul de
     *                     la teinte d'eau plein ecran
     */
    public void room(int topOfRoom, int bottomOfRoom, int bright,
            int leftClip, int rightClip, boolean ownZone) {
        this.topOfRoom = topOfRoom;
        this.bottomOfRoom = bottomOfRoom;
        this.zoneBright = bright;
        this.leftClip = leftClip;
        this.rightClip = rightClip;
        this.ownZone = ownZone;
    }

    /** Dit si le niveau comporte de l'eau. Sinon, aucun plan d'eau n'est dessine. */
    public void water(boolean any) {
        this.anyWater = any;
    }

    /** Les tranches de la derniere surface traitee, de haut en bas. */
    public List<Span> spans() {
        return spans;
    }

    /**
     * La teinte d'eau a passer sur toute l'image.
     *
     * <p>Zero sans teinte, quinze quand le joueur voit la surface de l'eau d'en dessous ou
     * d'au-dessus, et tout a un quand il a exactement les yeux dedans. Elle s'arme en RENONCANT
     * a dessiner le plan d'eau, et ne se remet a zero qu'au debut de l'image.
     */
    public int waterTint() {
        return waterTint;
    }

    /** Remet la teinte d'eau a zero, au debut d'une image. */
    public void clearWaterTint() {
        waterTint = 0;
    }

    /**
     * Combien de lignes du dernier contour tombaient hors des deux tables d'abscisses.
     *
     * <p>Zero attendu : un contour deborde quand un de ses sommets se projette a plus de deux
     * mille lignes de l'horizon, ce que la geometrie des niveaux n'atteint pas.
     */
    public int offTable() {
        return offTable;
    }

    /** Projette une surface horizontale et en tire ses tranches. */
    public void draw(Level.Flat flat) {
        spans.clear();
        offTable = 0;

        // Le code de commande, tel que le dessinateur de secteur le passe : un pour un sol, deux
        // pour un plafond, TROIS pour un plan d'eau — et non le sept du fichier.
        int kind = switch (flat.kind()) {
            case FLOOR -> 1;
            case CEILING -> 2;
            case WATER -> 3;
        };
        boolean isWater = flat.kind() == Level.Surface.WATER;
        int above = 0;

        int y = s16(flat.y());
        if (isWater && !anyWater) {
            return;
        }
        // La hauteur du fichier se compte au soixante-quatrieme, celle du secteur au
        // deux-cent-cinquante-sixieme : six bits les mettent d'accord.
        if ((y << 6) < topOfRoom) {
            tint(isWater, 0x0F);                       // checkforwater
            return;
        }
        if ((y << 6) > bottomOfRoom) {
            return;
        }
        if (leftClip >= rightClip) {
            return;
        }

        int above2Eye = s16(y - floorYOff);
        int lines;
        if (above2Eye > 0) {
            // La surface est SOUS l'oeil : on la verra vers le bas de l'image.
            lines = s16(bottomClip - centreY);
            if (lines <= 0) {
                return;
            }
        } else if (above2Eye < 0) {
            // Au-dessus de l'oeil. Un sol vu par en dessous n'existe pas : seuls un plafond ou
            // un plan d'eau passent, et le second parce que son code partage le meme bit.
            tint(isWater, 0x0F);
            if ((kind & 2) == 0) {
                return;
            }
            lines = s16(centreY - topClip);
            if (lines <= 0) {
                return;
            }
            kind = 1;
            above = 1;
            above2Eye = s16(-above2Eye);
        } else {
            // L'oeil est exactement dans le plan : on ne voit rien de la surface, mais si c'est
            // l'eau du secteur ou l'on se tient, on a la tete au ras de l'eau.
            tint(isWater, 0xFF);
            return;
        }

        // Un plafond vu d'en dessous est devenu un sol par le detour ci-dessus ; un plafond vu
        // d'au-dessus, lui, tourne le dos a la camera.
        if ((kind & 1) == 0) {
            return;
        }

        eyeToFloor = above2Eye;
        yPos = above2Eye << 6;
        int mz = mulu(above2Eye, oneOver(s16(lines))) >>> 8;
        if (mz == 0 || mz > 32767) {
            return;                                    // la surface est au ras de l'horizon
        }
        minZ = mz;
        bottomLine = lines;

        if (!facesUs(flat)) {
            return;
        }

        top = 300;
        bottom = -1;
        drawIt = false;
        outline(flat);
        sweep(flat, above);
    }

    /** Arme la teinte d'eau plein ecran, si cette surface est celle du secteur du joueur. */
    private void tint(boolean isWater, int value) {
        if (isWater && ownZone) {
            waterTint = value;
        }
    }

    /**
     * Le polygone a-t-il une chance de toucher la fenetre de dessin ?
     *
     * <p>On classe chaque sommet : devant la camera et dans la fenetre, trop a gauche, trop a
     * droite, ou derriere. Un seul sommet dans la fenetre suffit. Sinon, le polygone n'est
     * ecarte que si tous ses sommets sont du MEME cote — s'il en a des deux cotes, il traverse
     * la fenetre de part en part.
     *
     * <p>Au passage, on note si un sommet quelconque sortait de la fenetre : si aucun n'en
     * sortait, le balayage des lignes se passera de tout recadrage.
     */
    private boolean facesUs(Level.Flat flat) {
        boolean inside = false;
        boolean offLeft = false;
        boolean offRight = false;
        anyClipping = false;
        for (Level.FlatPoint corner : flat.corners()) {
            int p = corner.point();
            if (s16(view.rotZ(p)) <= 0) {
                inside = true;                         // derriere : on ne peut rien en dire
                anyClipping = true;
                continue;
            }
            int x = s16(view.screenX(p));
            if (x <= leftClip) {
                offLeft = true;
                anyClipping = true;
            } else if (x >= rightClip) {
                offRight = true;
                anyClipping = true;
            } else {
                inside = true;
            }
        }
        return inside || offLeft == offRight;
    }

    // ===== le contour =====

    /**
     * Trace le contour du polygone dans les deux tables d'abscisses.
     *
     * <p>Chaque arete joint deux sommets consecutifs du contour, qui est referme : le dernier
     * point repete le premier. Une arete dont les deux bouts sont devant la camera se projette
     * directement ; une arete dont un bout est derriere se coupe sur le plan de profondeur
     * minimale, et le bout coupe descend jusqu'au bas de l'image.
     */
    private void outline(Level.Flat flat) {
        List<Level.FlatPoint> points = flat.outline();
        for (int i = 0; i + 1 < points.size(); i++) {
            int a = points.get(i).point();
            int b = points.get(i + 1).point();
            int za = s16(view.rotZ(a));
            int zb = s16(view.rotZ(b));

            int xa;
            int ya;
            int xb;
            int yb;
            if (za > minZ) {
                if (zb > minZ) {
                    // bothinfront
                    xa = view.screenX(a);
                    ya = divs(yPos, za);
                    xb = view.screenX(b);
                    yb = divs(yPos, zb);
                } else {
                    // Le bout DROIT passe derriere : on coupe, et le morceau coupe plonge.
                    xa = view.screenX(a);
                    ya = divs(yPos, za);
                    xb = s16(divs(cut(view.rotX(b) - view.rotX(a), s16(zb - za),
                            s16(minZ - za)) + view.rotX(a), minZ) + view.centreX());
                    yb = bottomLine;
                }
            } else if (zb > minZ) {
                // Le bout GAUCHE passe derriere.
                xa = s16(divs(cut(view.rotX(a) - view.rotX(b), s16(za - zb),
                        s16(minZ - zb)) + view.rotX(b), minZ) + view.centreX());
                ya = bottomLine;
                xb = view.screenX(b);
                yb = divs(yPos, zb);
            } else {
                continue;                              // bothbehind
            }
            segment(xa, ya, xb, yb);
        }
    }

    /**
     * Ou une arete coupe le plan de profondeur minimale, en abscisse laterale.
     *
     * <p>La proportion se calcule sur des mots, ce qui oblige a descendre l'ecart d'abscisse de
     * sept bits avant la multiplication et a le remonter apres — et a repasser par un mot entre
     * les deux, ce qui limite le resultat.
     *
     * <p>Ce dernier passage ne se voit que si la division DEBORDE : le processeur laisse alors
     * son dividende en place, et seul le mot bas en est repris. Le corpus de reference ne
     * contient aucune arete dans ce cas.
     */
    private static int cut(int dx, int dz, int toPlane) {
        return s16(divs(muls(dx >> 7, toPlane), dz)) << 7;
    }

    /**
     * Inscrit une arete projetee dans l'une des deux tables.
     *
     * <p>Le sens decide de la table : une arete qui descend vers la droite borne la surface a
     * droite, une arete qui descend vers la gauche la borne a gauche. Une arete horizontale ne
     * borne rien et ne s'inscrit nulle part.
     */
    private void segment(int xa, int ya, int xb, int yb) {
        int x1 = s16(xa);
        int y1 = s16(ya);
        int x2 = s16(xb);
        int y2 = s16(yb);
        if (y2 == y1) {
            return;                                    // lineflat
        }
        drawIt = true;
        boolean onRight = y2 > y1;
        if (!onRight) {
            int t = y1;
            y1 = y2;
            y2 = t;
            t = x1;
            x1 = x2;
            x2 = t;
        }
        if (y1 < top) {
            top = y1;
        }
        if (y2 > bottom) {
            bottom = y2;
        }
        int dy = s16(y2 - y1);
        int dx = s16(x2 - x1);
        // Les quatre variantes du code d'origine se distinguent par deux choses, et deux
        // seulement : le sens dans lequel l'abscisse avance, et si la ligne courante s'inscrit
        // avant ou apres le pas. Une arete bornant a gauche inscrit AVANT quand elle va vers la
        // droite ; une arete bornant a droite, l'inverse. C'est ainsi que chaque table garde,
        // sur une ligne traversee en biais, l'abscisse la plus a l'exterieur.
        trace((onRight ? RIGHT : LEFT) + y1, x1, dx, dy, onRight == (dx < 0));
    }

    /**
     * Trace un segment, une ligne d'ecran a la fois.
     *
     * <p>C'est un trace de segment ordinaire, a ceci pres que la division qui l'amorce rend d'un
     * seul geste le pas entier ET le reste qui amorce l'accumulateur.
     *
     * @param storeFirst si la ligne courante s'inscrit avant le pas plutot qu'apres
     */
    private void trace(int at, int x, int dx, int dy, boolean storeFirst) {
        boolean back = dx < 0;
        int reg = divsReg(back ? s16(-dx) : dx, dy);
        int step = s16(reg);                           // le pas entier
        int next = s16(step + 1);                      // le pas quand l'accumulateur deborde
        int rest = s16(reg >> 16);                     // ce qui reste a rattraper par ligne
        int acc = dy;
        for (int i = 0; i < dy; i++) {
            if (storeFirst) {
                write(at + i, x);
            }
            acc = s16(acc - rest);
            if (acc >= 0) {
                x = back ? s16(x - step) : s16(x + step);
            } else {
                x = back ? s16(x - next) : s16(x + next);
                acc = s16(acc + dy);
            }
            if (!storeFirst) {
                write(at + i, x);
            }
        }
    }

    private void write(int at, int x) {
        if (at < 0 || at >= edges.length) {
            offTable++;                                // voir le commentaire de edges
            return;
        }
        edges[at] = x & 0xFFFF;
    }

    /** Une abscisse de la table, ou zero si la ligne tombe en dehors. */
    private int read(int at) {
        if (at < 0 || at >= edges.length) {
            offTable++;
            return 0;
        }
        return s16(edges[at]);
    }

    /** L'inverse d'un ecart a l'horizon, ou zero si le rang sort de la table. */
    private int oneOver(int n) {
        if (n < 0 || n >= ONE_OVER_N.length) {
            offTable++;
            return 0;
        }
        return ONE_OVER_N[n];
    }

    // ===== le balayage des lignes =====

    /**
     * Balaie les lignes d'ecran entre le haut et le bas du contour.
     *
     * <p>C'est ici que la surface prend sa distance. La hauteur de l'oeil au-dessus d'elle est
     * fixe ; la distance d'une ligne n'est que cette hauteur divisee par son ecart a l'horizon,
     * et la division se lit dans une table.
     */
    private void sweep(Level.Flat flat, int above) {
        int scale = s16(flat.scale() + (flat.kind() == Level.Surface.WATER ? 2 : 1));
        int lightType = s16(flat.light() + zoneBright);

        // L'origine de la texture, prise sous les pieds du joueur. Le balancement du pas s'y
        // ajoute EN CROIX : il deplace le point de vue perpendiculairement au regard.
        int startX = scaled(((playerX + wobbleZ) & 0xFFFF) << 16 >> 1, scale);
        int startZ = scaled(((playerZ + wobbleX) & 0xFFFF) << 16 >> 1, scale);

        if (!drawIt) {
            return;
        }

        int first = top;
        int last = bottom;
        if (above != 0) {
            int toTop = s16(centreY - topClip);
            int toBottom = s16(centreY - bottomClip);
            if (first >= toTop || last < toBottom) {
                return;
            }
            if (first < toBottom) {
                first = toBottom;
            }
            if (last >= toTop) {
                last = toTop;
            }
        } else {
            int toBottom = s16(bottomClip - centreY);
            if (first >= toBottom) {
                return;
            }
            int toTop = s16(topClip - centreY);
            if (first < toTop) {
                first = toTop;
            }
            if (last <= toTop) {
                return;
            }
            if (last >= toBottom) {
                last = toBottom;
            }
        }
        int count = s16(last - first);
        if (count <= 0) {
            return;
        }

        // La hauteur de l'oeil au-dessus de la surface, corrigee de l'aspect de l'ecran.
        int height = view.fullScreen()
                ? muls(eyeToFloor, ASPECT_HEIGHT)
                : s16(eyeToFloor) << 6;

        // L'ecart a l'horizon de la premiere ligne. Zero n'a pas d'inverse : le code d'origine
        // le remplace par un, ce qui fait de la ligne d'horizon la plus lointaine de toutes.
        // Aucune des soixante-douze mille tranches du corpus de reference ne commence
        // exactement sur l'horizon, et ce remplacement n'y est donc jamais observe.
        int fromHorizon = first == 0 ? 1 : first;
        int y = above != 0 ? centreY - 1 - first : centreY + first;
        int dir = above != 0 ? -1 : 1;

        for (int i = 0; i < count; i++) {
            if (bounds(first + i)) {
                // La hauteur corrigee est tronquee a un mot avant la table des inverses : la
                // multiplication d'origine est une multiplication de mots. Le corpus de
                // reference ne la porte jamais plus haut que huit mille sept cent quarante-sept,
                // loin des soixante-cinq mille ou elle reboucle : il faudrait un sol sept fois
                // plus loin de l'oeil que le plus lointain des seize niveaux.
                int distance = mulu(height >>> 4, oneOver(fromHorizon)) >>> 8;
                spans.add(line(distance, lightType, scale, startX, startZ, y));
            }
            y += dir;
            fromHorizon = s16(fromHorizon + 1);
        }
    }

    /**
     * Les deux bords de la ligne, recadres sur la fenetre de dessin.
     *
     * <p>Le bord droit retenu est celui de la table PLUS UN : la table garde la derniere colonne
     * couverte, et le rendu veut la premiere qui ne l'est pas.
     *
     * @return vrai s'il reste quelque chose a dessiner sur cette ligne
     */
    private boolean bounds(int line) {
        int right = s16(read(RIGHT + line) + 1);
        if (!anyClipping) {
            leftEdge = read(LEFT + line);
            rightEdge = right;
            return true;
        }
        if (right <= leftClip) {
            return false;
        }
        if (right > rightClip) {
            right = rightClip;
        }
        int left = read(LEFT + line);
        if (left >= rightClip) {
            return false;
        }
        if (left < leftClip) {
            left = leftClip;
        }
        if (right <= left) {
            return false;
        }
        leftEdge = left;
        rightEdge = right;
        return true;
    }

    /**
     * Une ligne : son eclairage, puis ou lire la texture.
     *
     * <p>L'eclairage ne depend que de la distance, parce que toute la ligne est a la meme. La
     * position dans la texture, elle, vient d'une rotation : le regard porte sur la surface deux
     * directions perpendiculaires, l'une qui avance avec la profondeur et l'autre qui balaie la
     * ligne. Les trois quarts qu'on voit apparaitre sont le rapport de hauteur entre les deux
     * axes de la texture.
     */
    private Span line(int distance, int lightType, int scale, int startX, int startZ, int y) {
        int bright = s16(s16(lightType + 5) + s16(distance >> 10));
        if (bright < 0) {
            bright = 0;
        } else if (bright > MAX_BRIGHT) {
            bright = MAX_BRIGHT;
        }

        int alongX = scaled(muls(distance, cos) >> 2, scale);
        int alongZ = scaled(-muls(distance, sin) >> 2, scale);

        int u = -(alongZ + threeQuarters(alongX)) + startX;
        int v = (alongX - threeQuarters(alongZ)) + startZ;

        // Le premier pixel de la ligne n'est pas forcement au milieu de l'ecran : on avance
        // d'autant de pas qu'il y a de colonnes jusqu'a lui.
        int left = leftEdge & 0xFFFF;
        if (left != 0) {
            int steps = view.fullScreen() ? muls(left, ASPECT_LEFT) >> 8 >> 3 : left;
            u += (alongX * steps) >> 7;
            v += (alongZ * steps) >> 7;
        }

        // Les deux accumulateurs s'accolent dans un seul mot double : l'ordonnee en haut,
        // l'abscisse en bas. Le masque les empeche de deborder l'un sur l'autre.
        int texture = (((v << 8) & 0xFFFF0000) | ((u >> 8) & 0xFFFF)) & 0x3FFF3FFF;

        int stepX;
        int stepZ;
        if (view.fullScreen()) {
            stepX = ((alongX >> 6) * ASPECT_STEP) >> 8;
            stepZ = ((alongZ >> 6) * ASPECT_STEP) >> 8;
        } else {
            stepX = alongX >> 7;
            stepZ = alongZ >> 7;
        }
        int step = ((stepZ << 8) & 0xFFFF0000) | ((stepX >> 8) & 0xFFFF);

        return new Span(y, leftEdge, rightEdge, distance, bright, texture, step);
    }

    /** Trois quarts d'une valeur, obtenus comme le code d'origine : deux decalages. */
    private static int threeQuarters(int v) {
        return (v + (v >> 1)) >> 1;
    }

    /** Le reglage de finesse de la texture : un decalage dans un sens ou dans l'autre. */
    private static int scaled(int v, int scale) {
        if (scale == 0) {
            return v;
        }
        return scale > 0 ? v << (scale & 31) : v >> (-scale & 31);
    }
}
