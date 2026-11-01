package ab3d2.full.render;

import ab3d2.full.level.Level;

import static ab3d2.full.M68k.s16;

/**
 * La fenetre d'ecran dans laquelle un secteur a le droit de se dessiner.
 *
 * <p>Une fois l'ordre des secteurs connu, il reste a dire OU chacun apparait. Le jeu ne decoupe
 * pas des polygones : il garde, pour le secteur courant, deux abscisses — une a gauche, une a
 * droite — et tout ce qu'il dessine est borne par elles. Traverser une ouverture ne fait que
 * resserrer ces deux bornes, jamais les elargir ; quand elles se croisent, le secteur est
 * entierement cache et le rendu passe au suivant sans rien tracer.
 *
 * <p>Le resserrement se lit dans le niveau. Chaque ouverture porte un groupe de sommets — les
 * uns bornent a gauche, les autres a droite — et chacun est un candidat. Un candidat n'est
 * retenu qu'a deux conditions, et ce sont elles qui font tout le travail :
 *
 * <ul>
 *   <li>le sommet doit etre DEVANT la camera. Derriere elle, sa projection n'a pas de sens : le
 *       rabattement sur un bord, qui rend la perspective calculable, mettrait une borne a
 *       l'exact oppose de sa place ;</li>
 *   <li>la paroi doit CONTINUER du bon cote. Un sommet qui borne a gauche ne vaut que si son
 *       voisin est encore plus a gauche a l'ecran. Autrement on regarde par le bord ouvert d'un
 *       mur, et resserrer la aveuglerait le rendu sur ce qu'il devrait voir.</li>
 * </ul>
 *
 * <p>Et le sens du resserrement n'est pas symetrique : la borne droite retenue est decalee d'un
 * pixel vers la droite, la gauche non. Cette asymetrie d'un pixel vient du code d'origine, ou
 * la borne gauche est incluse et la droite exclue.
 *
 * <p>La fenetre de depart est un PARAMETRE, et non zero-a-la-largeur : le jeu la resserre
 * d'abord par les aretes reellement dans le champ de vision, un calcul qui lui est propre. Tant
 * qu'il n'est pas porte, on part de la vue entiere, ce qui donne une fenetre plus large — jamais
 * plus etroite — que celle du jeu.
 */
public final class ZoneClip {

    private int left;
    private int right;

    /** La borne gauche retenue : la premiere abscisse ou le secteur a le droit de peindre. */
    public int left() {
        return left;
    }

    /** La borne droite retenue, EXCLUE : la premiere abscisse ou il n'a plus le droit. */
    public int right() {
        return right;
    }

    /**
     * Vrai s'il reste quelque chose a dessiner.
     *
     * <p>Le jeu ecarte le secteur des que la borne droite est negative, ou qu'elle n'est pas
     * strictement au-dela de la gauche.
     */
    public boolean visible() {
        return right >= 0 && left < right;
    }

    /**
     * Resserre la fenetre d'un secteur en passant par une ouverture.
     *
     * @param clip       le groupe de sommets de l'ouverture, ou {@code null} si la vue est
     *                   degagee : la fenetre reste alors celle de depart
     * @param startLeft  la borne gauche de depart
     * @param startRight la borne droite de depart
     */
    public void narrow(Level level, View view, Level.Clip clip, int startLeft, int startRight) {
        left = startLeft;
        right = startRight;
        if (clip == null) {
            return;
        }
        for (int point : clip.left()) {
            setLeft(level, view, point);
        }
        for (int point : clip.right()) {
            setRight(level, view, point);
        }
    }

    /**
     * Un candidat a la borne GAUCHE.
     *
     * <p>Sa profondeur est jugee sur SEIZE bits : le code d'origine ne teste que le mot bas du
     * resultat de la transformation. Un sommet assez lointain — de l'ordre de quarante-six mille
     * unites en vue encadree — deborderait de ce mot et passerait pour etre derriere la camera.
     * Aucun des seize niveaux n'est assez grand pour qu'on l'observe ; on garde la troncature
     * parce qu'elle est dans le code, pas parce qu'elle se voit.
     */
    private void setLeft(Level level, View view, int point) {
        if (s16(view.rotZ(point)) <= 0) {               // tst.w 6(a2,d0*8) ; ble
            return;
        }
        int x = view.screenX(point);                    // move.w (a1,d0*2),d1
        int neighbour = level.links().get(point).left();
        if (view.screenX(neighbour) > x) {              // cmp.w d1,d2 ; bgt
            return;                                     // la paroi ne continue pas a gauche
        }
        if (x > left) {                                 // cmp.w Draw_LeftClip_w,d1 ; ble
            left = x;
        }
    }

    /** Un candidat a la borne DROITE. La borne retenue est celle du sommet, decalee d'un. */
    private void setRight(Level level, View view, int point) {
        if (s16(view.rotZ(point)) <= 0) {               // tst.w 6(a2,d0*8) ; ble
            return;
        }
        int x = view.screenX(point);                    // move.w (a1,d0*2),d1
        int neighbour = level.links().get(point).right();
        if (view.screenX(neighbour) < x) {              // cmp.w d1,d2 ; blt
            return;                                     // la paroi ne continue pas a droite
        }
        if (x < right) {                                // cmp.w Draw_RightClip_w,d1 ; bge
            right = x + 1;                              // addq #1,d1
        }
    }
}
