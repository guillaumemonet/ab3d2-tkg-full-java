package ab3d2.full.render;

import ab3d2.full.SinCos;

import java.util.List;

import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;

/**
 * La rotation des choses : ou se trouve, vu de l'oeil, chaque entite du niveau.
 *
 * <p>C'est le pendant de {@link View} pour ce qui bouge. Les deux font la meme rotation autour
 * du meme angle, mais pas avec la meme precision ni sur les memes donnees, et il ne faut pas
 * les confondre :
 *
 * <ul>
 *   <li>{@link View} tourne les sommets du DECOR, qui ne bougent jamais, et garde l'abscisse
 *       au huitieme de bit pres ;</li>
 *   <li>celle-ci tourne les points des CHOSES, qui bougent a chaque image, et ne garde de
 *       l'abscisse que les seize bits de poids fort avant de la remultiplier par cent
 *       vingt-huit. Le resultat est donc le meme nombre, mais arrondi au multiple de cent
 *       vingt-huit le plus proche en dessous.</li>
 * </ul>
 *
 * <p>Cet arrondi n'est pas une approximation du portage : il est dans l'original, et c'est le
 * prix d'un {@code swap} la ou le decor fait un decalage. Divise ensuite par la profondeur, il
 * vaut une fraction de pixel — invisible, mais reel.
 *
 * <h2>Les auxiliaires</h2>
 *
 * <p>Une entite auxiliaire n'a pas de point a elle : la boucle la SAUTE sans avancer dans la
 * table des points. Son rang de point est donc celui de l'entite qui la suit, et elle se
 * dessine la ou celle-ci se dessine, a un ecart pres. Les deux tables — entites et points —
 * avancent ensemble, et c'est le saut des auxiliaires qui les tient alignees.
 *
 * <h2>Les choses retirees</h2>
 *
 * <p>Une entite dont le secteur est negatif n'est pas dans le niveau : son point est mis a
 * ZERO, les trois valeurs d'un coup. Ce n'est pas une position valable, c'est une marque — et
 * le rendu la reconnait a sa profondeur nulle, qui tombe sous le plan rapproche.
 *
 * <h2>Trois troncatures qu'on garde sans qu'elles servent</h2>
 *
 * <p>La pose de l'oeil, les deux ecarts, et le mot intermediaire du rapport d'aspect sont
 * ramenes au mot alors qu'on pourrait s'en passer : chacune de ces valeurs ne sert ensuite qu'a
 * {@link ab3d2.full.M68k#muls}, qui tronque DEJA ses deux facteurs. Les supprimer ne change
 * aucun resultat, et c'est demontrable plutot que mesure — les trois mutations correspondantes
 * sont les seules du corpus a survivre, pour cette raison et non par manque de cas.
 *
 * <p>On les garde parce que l'original les ecrit : {@code sub.w} et {@code ext.l} sont la, et
 * les effacer donnerait a lire un calcul qui n'est pas celui du jeu. La troncature de la
 * profondeur dans la vue encadree, elle, est bien vivante : c'est la valeur rangee.
 */
public final class ObjectView {

    /**
     * Le rapport d'aspect du plein ecran, applique a la profondeur.
     *
     * <p>{@link View} fait la meme correction avec un autre couple de nombres, qui vaut le meme
     * rapport : quatre-vingt-cinq deux cent cinquante-sixiemes, soit a peu pres un tiers.
     */
    private static final int ASPECT = 85;

    private final SinCos sinCos;

    private int[] rotX = new int[0];
    private int[] rotZ = new int[0];
    private int[] worldX = new int[0];
    private int count;

    public ObjectView(SinCos sinCos) {
        this.sinCos = sinCos;
    }

    /** Combien de points ont ete tournes. */
    public int count() {
        return count;
    }

    /** L'abscisse tournee du point {@code i}, en mot. */
    public int rotX(int i) {
        return rotX[i];
    }

    /** La profondeur du point {@code i} : c'est elle qui sert au tri et a l'echelle. */
    public int rotZ(int i) {
        return rotZ[i];
    }

    /**
     * L'abscisse du point {@code i} avant la perspective, au centieme pres du decor.
     *
     * <p>C'est {@link #rotX} remis a l'echelle de {@link View#rotX}, le balancement du pas
     * compris. Le rendu la divise par la profondeur pour obtenir la colonne.
     */
    public int worldX(int i) {
        return worldX[i];
    }

    /**
     * Ce que la rotation a besoin de savoir d'une chose.
     *
     * <p>Le niveau n'en donne que la pose de depart ; en jeu, c'est la simulation qui les
     * fournit, une par entite et dans le MEME ordre — l'ordre decide du rang de point, donc de
     * tout le reste.
     *
     * <p>Elle en porte DEUX secteurs, et ce n'est pas une redondance. Le vrai, celui que la
     * simulation tient a jour, dit si la chose est dans le niveau. L'autre est une COPIE que
     * le jeu ne rafraichit qu'une image sur cinq, et c'est elle, et elle seule, qui decide
     * dans quel secteur la chose se DESSINE — de sorte qu'une chose qui change de secteur
     * reste jusqu'a quatre images dans l'ancien.
     *
     * @param x        position, en unites du monde
     * @param z        position
     * @param zone     secteur, negatif si la chose n'est pas dans le niveau
     * @param drawZone le secteur ou la chose se DESSINE : la copie, en retard d'au plus quatre
     *                 images sur le vrai
     * @param upper    l'etage, tel OCTET que le fichier le porte — voir
     *                 {@link ab3d2.full.level.Level.Entity}
     * @param aux      vrai si la chose est auxiliaire, auquel cas elle n'a pas de point
     * @param point    son rang dans la table des points
     */
    public record Thing(int x, int z, int zone, int drawZone, int upper, boolean aux,
            int point) { }

    /**
     * Les choses telles que le niveau les pose, avant que rien n'ait bouge.
     *
     * <p>Les deux secteurs y sont egaux : c'est l'etat ou la copie vient d'etre faite.
     */
    public static List<Thing> atRest(List<ab3d2.full.level.Level.Entity> entities) {
        return entities.stream()
                .map(e -> new Thing(e.x(), e.z(), e.zone(), e.zone(), e.upperZone(),
                        e.kind() == ab3d2.full.level.Level.Kind.AUX, e.point()))
                .toList();
    }

    /**
     * L'abscisse d'un ecart, une fois tourne : {@code x*cos - z*sin}.
     *
     * <p>Le resultat est DOUBLE puis echange de moities : on ne garde que ses seize bits de
     * poids fort, ce qui revient a le ramener de quinze bits.
     *
     * <p>Elle est a part parce que le SON s'en sert aussi : le jeu place ses bruits avec les
     * memes coordonnees tournees que son rendu ({@code ObjRotated_vl}).
     */
    public static int rotateX(int dx, int dz, int sin, int cos) {
        return s16((muls(dx, cos) - muls(dz, sin)) * 2 >>> 16);
    }

    /**
     * La profondeur d'un ecart, une fois tournee : {@code x*sin + z*cos}, ramenee de quatorze
     * bits, puis corrigee du rapport d'aspect quand la vue est en plein ecran.
     */
    public static int rotateZ(int dx, int dz, int sin, int cos, boolean full) {
        int z = muls(dx, sin) + muls(dz, cos);
        if (full) {
            return s16(muls(s16((z << 2) >>> 16), ASPECT) >> 8);
        }
        return s16((z * 2) >>> 16);
    }

    /**
     * Tourne les points des choses autour de l'oeil.
     *
     * @param things  les choses, dans l'ordre du niveau
     * @param points  combien de points tourner : la boucle s'arrete la, meme s'il reste des
     *                choses. Le niveau en donne le compte, et il vaut exactement le nombre de
     *                choses non auxiliaires
     * @param playerX la pose de l'oeil
     * @param playerZ la pose de l'oeil
     * @param angle   l'orientation, en deplacement d'octets dans la table de sinus
     * @param xWobble le balancement lateral du pas, ajoute a chaque abscisse
     * @param full    vrai en plein ecran : la profondeur y recoit la correction d'aspect
     */
    public void update(List<Thing> things, int points, int playerX, int playerZ, int angle,
            int xWobble, boolean full) {
        if (rotX.length < points) {
            rotX = new int[points];
            rotZ = new int[points];
            worldX = new int[points];
        }
        count = points;
        int sin = sinCos.sin(angle);
        int cos = sinCos.cos(angle);
        int px = s16(playerX);
        int pz = s16(playerZ);

        int at = 0;                                    // le rang du point en cours
        for (Thing t : things) {
            if (t.aux()) {
                continue;                              // .itaux : ni point ni avancee
            }
            if (at >= points) {
                break;                                 // dbra d7 : le compte est epuise
            }
            if (t.zone() < 0) {
                rotX[at] = 0;                          // .noworkout : clr.l (a1)+ deux fois
                rotZ[at] = 0;
                worldX[at] = 0;
                at++;
                continue;
            }

            int dx = s16(t.x() - px);                  // sub.w Plr_XOff_l,d0
            int dz = s16(t.z() - pz);                  // sub.w Plr_ZOff_l,d1

            int x = rotateX(dx, dz, sin, cos);
            rotX[at] = x;
            rotZ[at] = rotateZ(dx, dz, sin, cos, full);
            int z = rotZ[at];

            // Et l'abscisse remise a l'echelle du decor, le balancement du pas compris.
            worldX[at] = (x << 7) + xWobble;           // ext.l d2 ; asl.l #7,d2 ; add.l d3,d2
            at++;
        }
    }
}
