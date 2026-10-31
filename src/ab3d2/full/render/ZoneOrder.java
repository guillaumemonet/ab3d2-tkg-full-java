package ab3d2.full.render;

import ab3d2.full.level.Level;

import java.util.Arrays;
import java.util.List;

import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;

/**
 * Dans quel ordre dessiner les secteurs visibles.
 *
 * <p>Le jeu n'a pas de tampon de profondeur : il peint du plus loin au plus pres et laisse le
 * dernier trait recouvrir le precedent. Tout repose donc sur cet ordre, et une seule paire mal
 * classee se voit aussitot — une piece lointaine passant par-dessus un mur proche.
 *
 * <p>Le tri ne compare pas des distances, il compare des COTES. Deux secteurs voisins partagent
 * une arete ; savoir lequel est devant revient a savoir de quel cote de cette arete se tient le
 * joueur, ce qu'un produit en croix donne. On part de l'ordre ou le niveau donne les secteurs,
 * et l'on deplace dans une liste doublement chainee ceux qui sont mal places, en remontant la
 * liste du plus pres vers le plus loin. Au plus cent passes : le code d'origine ne s'autorise
 * pas davantage, et c'est une limite, pas une garantie de convergence.
 *
 * <p>L'ordre ne depend QUE de la position du joueur, jamais de son angle : tourner la tete ne
 * change pas ce qui est devant quoi. Le code d'origine en profite pour ne retrier que si le
 * joueur a quitte le carre de seize unites ou il se tenait. On garde cette economie, qui fait
 * partie de son comportement.
 */
public final class ZoneOrder {

    /**
     * De combien le joueur doit bouger pour qu'on retrie.
     *
     * <p>Les quatre bits de poids faible de chaque coordonnee sont mis a zero : tant qu'on reste
     * dans le meme carre de seize unites, l'ordre precedent tient.
     */
    private static final int MOVEMENT_MASK = 0xFFF0FFF0;

    /** Le nombre de passes qu'on s'autorise avant d'arreter de reordonner. */
    private static final int MAX_PASSES = 100;

    // La liste chainee. Une case par ENTREE ; la case 0 est une sentinelle qui ferme la liste.
    private int[] prev = new int[0];
    private int[] zoneOf = new int[0];
    private int[] next = new int[0];

    /** Le masque de travail, une case par SECTEUR, parti de celui de son entree PVS. */
    private int[] mask = new int[0];

    private int[] order = new int[0];
    private int count;
    private int lastPosition = -1;

    /**
     * Les secteurs visibles, du plus PRES au plus LOIN — le secteur du joueur en tete.
     *
     * <p>C'est l'ordre dans lequel le jeu range sa table. Pour peindre, il la parcourt a
     * l'envers : voir {@link #drawOrder()}.
     */
    public int[] order() {
        return Arrays.copyOf(order, count);
    }

    /**
     * Les secteurs dans l'ordre ou il faut les DESSINER : du plus loin au plus pres.
     *
     * <p>Sans tampon de profondeur, c'est le dernier trait qui gagne ; on commence donc par le
     * fond.
     */
    public int[] drawOrder() {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = order[count - 1 - i];
        }
        return out;
    }

    /** Combien de secteurs l'ordre courant compte. */
    public int count() {
        return count;
    }

    /** Oublie l'ordre retenu, pour forcer un tri au prochain appel. */
    public void reset() {
        lastPosition = -1;
    }

    /**
     * Trie les secteurs visibles depuis celui ou se tient le joueur.
     *
     * <p>Ne fait rien si le joueur n'a pas quitte le carre de seize unites ou il etait au
     * dernier tri.
     *
     * @param from le secteur ou se tient le joueur : c'est SA liste de secteurs visibles qu'on
     *             trie
     */
    public void update(Level level, Level.Zone from, int playerX, int playerZ) {
        int position = ((s16(playerX) << 16) | (s16(playerZ) & 0xFFFF)) & MOVEMENT_MASK;
        if (position == lastPosition) {
            return;
        }
        lastPosition = position;

        List<Level.Visible> visible = from.visible();
        int n = visible.size();
        int zones = level.zones().size() + 2;
        if (mask.length < zones) {
            mask = new int[zones];
        }
        if (prev.length < n + 2) {
            prev = new int[n + 2];
            zoneOf = new int[n + 2];
            next = new int[n + 2];
            order = new int[n + 2];
        }

        // Chaque secteur visible apporte son masque de depart.
        for (Level.Visible v : visible) {
            if (v.zone() >= 0 && v.zone() < zones) {
                mask[v.zone()] = v.orderMask();
            }
        }

        // La liste de depart suit l'ordre ou le niveau donne les secteurs. La case 0 ne porte
        // aucun secteur : elle sert de tete.
        for (int i = 0; i < n; i++) {
            int slot = i + 1;
            zoneOf[slot] = visible.get(i).zone();
            prev[slot] = slot - 1;
            next[slot] = slot + 1;
        }
        if (n > 0) {
            next[n] = -1;
        }
        prev[0] = -1;
        zoneOf[0] = -1;
        next[0] = 1;

        // On remonte la liste depuis la DERNIERE entree, en reclassant chacune par rapport a
        // ses voisines. Les liens changeant sous nos pas, le parcours lui-meme est mouvant.
        int at = n;
        for (int pass = 0; pass <= MAX_PASSES; pass++) {
            int here = at;
            int before = prev[here];
            if (before < 0) {
                break;
            }
            at = before;
            int z = zoneOf[here];
            mask[z] = insert(level, here, z, mask[z], playerX, playerZ);
        }

        // On aplatit la chaine, du plus pres au plus loin.
        count = 0;
        for (int cursor = next[0]; cursor >= 0 && count < order.length; cursor = next[cursor]) {
            order[count++] = zoneOf[cursor];
        }
    }

    /**
     * Reclasse les voisines d'un secteur par rapport a lui.
     *
     * <p>On passe en revue ses aretes. Celles qui ferment le decor ne menent nulle part ; les
     * autres donnent une voisine. <b>Trois bits du masque</b> sont reserves a chaque arete : le
     * premier dit que l'arete compte, et les deux suivants gardent ce qui a deja ete tranche,
     * pour ne pas refaire le produit en croix a chaque passe.
     */
    private int insert(Level level, int here, int z, int bits, int playerX, int playerZ) {
        Level.Zone src = level.zones().get(z);
        int bit = 0;
        for (int edgeIndex : src.edgeList()) {
            if (edgeIndex < 0) {
                break;
            }
            Level.Edge e = level.edges().get(edgeIndex);
            int join = e.joinZone();
            if (join < 0 || (bits & (1 << (bit & 31))) == 0) {
                bit = s16(bit + 3);                    // l'arete ferme le decor, ou ne compte pas
                continue;
            }
            bit = s16(bit + 1);

            if ((bits & (1 << (bit & 31))) == 0) {
                // Rien n'a encore ete tranche pour cette arete : de quel cote est le joueur ?
                bits |= 1 << (bit & 31);
                int dx = s16(s16(playerX) - e.x());
                int dz = s16(s16(playerZ) - e.z());
                int cross = muls(dx, e.dz()) - muls(dz, e.dx());
                bit = s16(bit + 1);
                if (cross <= 0) {
                    bit = s16(bit + 1);                // la voisine est derriere : rien a faire
                    continue;
                }
                bits |= 1 << (bit & 31);               // on retient qu'elle est devant
            } else {
                bit = s16(bit + 1);
                if ((bits & (1 << (bit & 31))) == 0) {
                    bit = s16(bit + 1);                // deja tranche : elle reste derriere
                    continue;
                }
            }

            moveBefore(here, join);
            bit = s16(bit + 1);
        }
        return bits;
    }

    /**
     * Fait passer le secteur courant DEVANT une voisine mal placee.
     *
     * <p>On ne cherche la fautive que du cote LOINTAIN de la liste, celui qu'on a deja parcouru :
     * si elle n'y est pas, c'est qu'elle est deja assez pres et le tri n'a rien a faire. Sinon
     * on detache le secteur courant et on le raccroche juste avant elle.
     */
    private void moveBefore(int here, int target) {
        int scan = prev[here];
        int offender = -1;
        while (scan >= 0) {
            if (zoneOf[scan] == target) {
                offender = scan;
                break;
            }
            scan = prev[scan];
            if (scan < 0) {
                break;
            }
        }
        if (offender < 0) {
            return;
        }

        // On detache le courant. Le code d'origine le retrouve par le suivant de son
        // precedent plutot que par lui-meme ; on garde cette tournure.
        int behind = prev[here];
        int self = next[behind];
        int ahead = next[here];
        if (ahead >= 0) {
            prev[ahead] = behind;
        }
        next[behind] = ahead;

        // On le raccroche juste avant la fautive.
        int beforeOffender = prev[offender];
        int atOffender = next[beforeOffender];
        prev[self] = beforeOffender;
        next[self] = atOffender;
        prev[atOffender] = self;
        next[beforeOffender] = self;
    }
}
