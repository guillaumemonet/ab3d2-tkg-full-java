package ab3d2.full.render;

import java.util.List;

/**
 * Le tri des choses : lesquelles dessiner dans ce secteur, et dans quel ordre.
 *
 * <p>Le rendu n'a pas de tampon de profondeur. Les choses se peignent donc de la plus loin a la
 * plus proche, chacune recouvrant la precedente — et c'est tout ce que fait cette classe :
 * elle range, elle ne dessine rien.
 *
 * <h2>Une table de quatre-vingts cases, et pas une de plus</h2>
 *
 * <p>Le rangement se fait par insertion dans une table FIXE. Chaque chose retenue y trouve sa
 * place, tout ce qui suit glisse d'un cran, et ce qui depasse tombe. Comme la table est rangee
 * de la plus loin a la plus proche, ce qui tombe est la case du bout, c'est-a-dire la chose la
 * plus PROCHE — celle qu'on aurait le plus de raisons de voir. Au-dela de quatre-vingts choses
 * dans un secteur, c'est donc le premier plan qui disparait, pas l'arriere-plan.
 *
 * <p>Les cases vides portent une profondeur de moins trente-deux mille sept cent soixante-sept,
 * et le dessin s'arrete a la premiere profondeur NEGATIVE. Une chose derriere l'oeil, dont la
 * profondeur est negative, se range donc avant ces cases vides et coupe la liste : elle n'est
 * pas dessinee, et rien de ce qui la suit ne l'est non plus.
 *
 * <h2>Le depart qui ne range rien</h2>
 *
 * <p>Quand la fenetre de decoupage est vide ou large d'un seul pixel, la routine sort AVANT
 * d'avoir vide la table. Celle-ci garde alors ce qu'un secteur precedent y avait mis — mais
 * rien ne la relit, puisque le dessin ne s'ouvre pas. On reproduit les deux : aucune chose a
 * dessiner, et la table laissee telle quelle.
 *
 * <h2>Les egalites</h2>
 *
 * <p>L'insertion s'arrete a la premiere case dont la profondeur est INFERIEURE OU EGALE. A
 * profondeur egale, la chose qui arrive se place donc AVANT celle qui etait la : elle se
 * dessine en premier, et l'autre lui passe dessus. C'est l'ordre de la table des entites qui
 * tranche, et il le fait a l'envers de ce qu'on attendrait.
 */
public final class Sprites {

    /** Combien de choses la table peut en porter. */
    public static final int SLOTS = 80;

    /** La profondeur d'une case vide : {@code $8001}. */
    private static final int EMPTY = -32767;

    private final int[] depth = new int[SLOTS];
    private final int[] which = new int[SLOTS];
    private int count;
    private int dropped;
    private int overrun;

    /** Combien de choses sont a dessiner, dans l'ordre. */
    public int count() {
        return count;
    }

    /** Le rang, dans la table des entites, de la {@code i}-eme chose a dessiner. */
    public int at(int i) {
        return which[i];
    }

    /** La profondeur de la {@code i}-eme chose a dessiner. */
    public int depth(int i) {
        return depth[i];
    }

    /** La table entiere, cases vides comprises : {profondeur, rang}. */
    public int slotDepth(int i) {
        return depth[i];
    }

    /** Le rang range dans la case {@code i}, qu'elle serve ou non. */
    public int slotWhich(int i) {
        return which[i];
    }

    /** Combien de choses ont ete chassees du bout de la table depuis la remise a zero. */
    public int dropped() {
        return dropped;
    }

    /**
     * Combien de fois la recherche de place est sortie de la table.
     *
     * <p>Il faut pour cela que la profondeur soit inferieure aux quatre-vingts cases — ce qui
     * n'arrive qu'une fois la table pleine de profondeurs NEGATIVES, les cases vides en valant
     * moins trente-deux mille sept cent soixante-sept. L'original ne s'en garde pas : il
     * continue sa recherche dans ce qui suit la table, puis y ecrit. Et ce qui suit la table
     * est la borne haute de la piece — de sorte qu'une chose de trop ne se contente pas de ne
     * pas s'afficher : elle fausse le decoupage vertical de toutes celles qui suivent.
     *
     * <p>Les seize niveaux publies n'y vont pas : dix-sept choses dans un meme secteur, au
     * plus, contre quatre-vingts places. On compte l'evenement plutot que de le reproduire,
     * parce qu'il depend de l'ordre des variables en memoire et non du calcul.
     */
    public int overrun() {
        return overrun;
    }

    /** Remet a zero les deux comptes. */
    public void clearCounts() {
        dropped = 0;
        overrun = 0;
    }

    /**
     * Range les choses du secteur, de la plus loin a la plus proche.
     *
     * @param things    les choses, dans l'ordre du niveau
     * @param view      la rotation, qui donne la profondeur de chaque point
     * @param zone      le secteur en cours de dessin
     * @param upper     l'etage en cours, tel OCTET : zero ou deux cent cinquante-cinq
     * @param leftClip  le bord gauche de la fenetre
     * @param rightClip le bord droit
     */
    public void sort(List<ObjectView.Thing> things, ObjectView view, int zone, int upper,
            int leftClip, int rightClip) {
        count = 0;
        if (rightClip - leftClip - 1 <= 0) {
            return;                                    // .done_all_in_front, table intacte
        }

        for (int i = 0; i < SLOTS; i++) {              // .empty_tab
            depth[i] = EMPTY;
            which[i] = 0;
        }

        int rank = 0;
        for (ObjectView.Thing t : things) {
            // Le secteur de DESSIN, pas le vrai ; et l'etage compare octet a octet, ce qui
            // ecarte toute valeur qui ne serait ni zero ni deux cent cinquante-cinq.
            boolean here = t.drawZone() == zone && ((t.upper() ^ upper) & 0xFF) == 0;
            if (!here) {
                rank++;                                // .not_in_this_zone
                continue;
            }

            int z = view.rotZ(t.point());              // move.w 2(a2,d1.w*8),d1
            int at = 0;                                // .still_in_front
            while (at < SLOTS && z < depth[at]) {
                at++;
            }
            if (depth[SLOTS - 1] != EMPTY) {
                dropped++;                             // la plus proche tombe du bout
            }

            // Le glissement a lieu AVANT qu'on sache si la place est dans la table, et il a
            // toujours lieu au moins une fois : c'est une boucle a test de queue. Quand la
            // recherche est sortie, ce glissement unique reste — la case du bout prend celle
            // d'avant, et l'ecriture qui suit tombe dehors.
            for (int j = SLOTS - 2; ; j--) {           // .finished_shift
                depth[j + 1] = depth[j];
                which[j + 1] = which[j];
                if (j <= at) {
                    break;
                }
            }
            if (at >= SLOTS) {
                overrun++;
                rank++;
                continue;
            }
            depth[at] = z;
            which[at] = rank;
            rank++;
        }

        // .go_back_and_do_another : le dessin s'arrete a la premiere profondeur negative.
        while (count < SLOTS && depth[count] >= 0) {
            count++;
        }
    }
}
