package ab3d2.full.render;

import ab3d2.full.level.VectorModel;

import static ab3d2.full.M68k.muls;

/**
 * Dans quel ordre les morceaux d'un modele se dessinent.
 *
 * <p>Un modele n'est pas une liste de faces mais une liste de PARTIES, et deux choses se
 * decident ici : lesquelles existent dans cette pose, et dans quel ordre les peindre.
 *
 * <h2>Lesquelles</h2>
 *
 * <p>La pose porte un mot double de drapeaux, un bit par partie, consomme par le bas. Une
 * partie eteinte est sautee — et son bit est consomme quand meme, de sorte que le rang d'une
 * partie dans la liste est bien le rang de son bit. C'est ainsi qu'un canon rentre dans sa
 * tourelle sans que la geometrie change.
 *
 * <h2>Dans quel ordre</h2>
 *
 * <p>Le premier mot du fichier dit si le modele veut etre TRIE. Ceux qui ne le veulent pas se
 * dessinent dans l'ordre de leur liste, et tant pis pour ce qui se recouvre : c'est le choix
 * du modeleur, pas un oubli.
 *
 * <p>Ceux qui le veulent se trient par distance DECROISSANTE — le plus loin d'abord, pour que
 * le plus proche le recouvre. La distance est celle d'un point representatif que la partie
 * designe, prise au carre et sans racine : l'abscisse et la hauteur ramenees de sept bits, la
 * profondeur telle quelle. Les trois echelles ne sont donc pas les memes, et cette mesure ne
 * vaut rien en soi ; elle ne sert qu'a comparer.
 *
 * <p>Le tri est une insertion : on cherche la premiere place dont la distance n'est pas plus
 * grande, on decale tout ce qui suit d'un cran vers la fin, et l'on ecrit. Le decalage part
 * TOUJOURS du bout du tampon, qu'il y ait quelque chose a decaler ou non.
 *
 * <h2>Le point qui n'appartient pas au modele</h2>
 *
 * <p>Rien ne garantit que le point representatif soit l'un des points du modele. La GUEPE
 * designe, sur quatre de ses dix-sept parties et dans CHACUNE de ses treize poses, des rangs
 * au-dela de ses quatre-vingt-neuf points. Elle lit alors ce que l'objet precedent a laisse
 * dans la table de travail, et l'ordre de ses parties depend donc de ce qui a ete dessine
 * avant elle. C'est pourquoi {@link ModelPoints} n'efface jamais ses tables : les effacer
 * donnerait une autre image que le jeu. Soixante-douze lectures de ce genre dans le corpus.
 *
 * <h2>Le tampon de trente-deux</h2>
 *
 * <p>Il n'y en a pas davantage, et rien ne le verifie. Une partie de trop ecraserait le
 * voisinage, et une recherche qui passe le bout du tampon lirait ce qui est range apres. On
 * compte ces cas-la plutot que de les inventer — mesure : **la plus longue liste de parties
 * des vingt modeles en compte DIX-NEUF**, toutes allumees, et le tampon n'est donc jamais
 * qu'a moitie plein. C'est la seule mutation de ce chantier qu'on ne peut pas montrer.
 */
public final class ModelParts {

    /** Combien de parties le tampon tient. */
    public static final int SLOTS = 32;

    /** Ce qu'une place vide porte : un mot double NEGATIF, qui ferme la liste. */
    private static final int EMPTY = 0x80000001;

    private final int[] distance = new int[SLOTS];
    private final int[] where = new int[SLOTS];
    private int count;
    private int overflow;
    private int beyondModel;

    /** Combien de parties sont a dessiner, dans l'ordre. */
    public int count() {
        return count;
    }

    /** La distance au carre de la partie de rang {@code i}, nulle si le modele ne trie pas. */
    public int distance(int i) {
        return distance[i];
    }

    /** Ou sont les faces de la partie de rang {@code i}. */
    public int where(int i) {
        return where[i];
    }

    /**
     * Combien de fois le tampon a deborde.
     *
     * <p>Le jeu ecrirait alors par-dessus ce qui suit, ou lirait au-dela : ce n'est pas ce
     * qu'il y a ici, et le cas est abandonne.
     */
    public int overflow() {
        return overflow;
    }

    /**
     * Combien de parties ont designe un point qui n'appartient pas au modele.
     *
     * <p>Ce n'est pas une faute : la guepe le fait sur quatre de ses parties, dans chacune de
     * ses treize poses. Elle lit alors ce que l'objet precedent a laisse dans la table de
     * travail, et son ordre de parties depend donc de ce qui a ete dessine avant elle.
     */
    public int beyondModel() {
        return beyondModel;
    }

    public void clearOverflow() {
        overflow = 0;
    }

    /**
     * Remplit et ordonne le tampon.
     *
     * <p>Port de {@code clrpartbuff} / {@code putinunsorted} / {@code PutinParts}
     * (objdrawhires.s:2106).
     *
     * @param model le modele
     * @param frame la pose, dont on lit les drapeaux de parties
     * @param pts   les points deja tournes : c'est d'eux que vient la distance
     * @return faux si le tampon a deborde
     */
    public boolean sort(VectorModel model, int frame, ModelPoints pts) {
        for (int i = 0; i < SLOTS; i++) {              // clrpartbuff
            distance[i] = EMPTY;
            where[i] = 0;
        }
        count = 0;
        int d5 = model.frames().get(frame).onOff();    // move.l draw_ObjectOnOff_l,d5
        for (VectorModel.Part part : model.parts()) {
            boolean on = (d5 & 1) != 0;                // lsr.l #1,d5 ; bcs.s .yeson
            d5 = d5 >>> 1;
            if (!on) {
                continue;                              // addq #2,a1 ; bra
            }
            if (!model.sort()) {
                // putinunsorted : a la suite, sans rien mesurer.
                if (count >= SLOTS) {
                    overflow++;
                    return false;
                }
                distance[count] = 0;                   // move.l #0,(a0)+
                where[count] = part.at();              // move.w d7,(a0)
                count++;
                continue;
            }
            // PutinParts : la distance au carre du point representatif.
            //
            // ATTENTION : rien ne garantit que ce point appartienne au modele. La guepe
            // designe, sur quatre de ses parties, des rangs au-dela de ses quatre-vingt-neuf
            // points, et lit donc ce que l'objet PRECEDENT a laisse dans la table de travail.
            // C'est pourquoi cette table n'est jamais effacee : voir ModelPoints.
            int i = part.pointIndex();
            if (i >= pts.count()) { beyondModel++; }
            if (i < 0 || i >= pts.capacity()) {
                overflow++;
                return false;
            }
            int d0 = muls(pts.x3(i) >> 7, pts.x3(i) >> 7); // move.l (a2,d6.w),d0 ; asr.l #7 ; muls
            int d2 = muls(pts.y3(i) >> 7, pts.y3(i) >> 7); // move.l 4(a2,d6.w),d2 ; asr.l #7 ; muls
            d0 = d0 + d2;                              // add.l d2,d0
            d2 = muls(pts.z3(i), pts.z3(i));           // move.w 8(a2,d6.w),d2 ; muls d2,d2
            d0 = d0 + d2;                              // add.l d2,d0

            int at = 0;                                // stillfront
            while (at < SLOTS && d0 < distance[at]) {  // cmp.l (a0),d0 ; blt stillfront
                at++;
            }
            if (at >= SLOTS) {
                overflow++;
                return false;
            }
            for (int k = SLOTS - 1; k > at; k--) {     // domoreshift, depuis le BOUT
                distance[k] = distance[k - 1];
                where[k] = where[k - 1];
            }
            distance[at] = d0;                         // move.l d0,(a0)
            where[at] = part.at();                     // move.w d7,4(a0)
        }
        // doneallparts : la liste s'arrete a la premiere place restee vide.
        count = 0;
        while (count < SLOTS && distance[count] >= 0) {
            count++;
        }
        return true;
    }
}
