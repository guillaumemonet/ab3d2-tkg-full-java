package ab3d2.full.sim;

import ab3d2.full.M68k;
import ab3d2.full.level.Level;

import java.util.List;

/**
 * Navigation de l'IA : points de contrôle du niveau et table de routage.
 *
 * <p>Le jeu ne fait pas de pathfinding au runtime : chaque niveau embarque un maillage de points
 * de contrôle (fichier .bin) et DEUX tables précalculées « prochain saut » — une à pied
 * (fichier .map), une en vol (.fly). {@code GetNextCPt} (Objectmove.java:917) lit simplement
 * {@code links[courant * 100 + cible]}.
 *
 * <p>Contient aussi {@code GetRand}, le générateur du jeu : les aliens s'en servent pour choisir
 * une destination, donc son état fait partie de la simulation.
 */
public final class Nav {

    /** Aucun chemin ($7f). */
    public static final int NO_WAY = 0x7f;

    private final int[] cpX, cpZ, cpY;
    private final int[] walk, fly;
    /** Lvl_NumControlPoints_w. */
    public final int count;
    /** ONLYSEE : le lien n'est utilisable qu'à vue (bit 7 du lien). */
    public boolean onlySee;

    /** Rand1 (ObjectmoveData.java:104) : graine d'origine. */
    private int rand1 = 234;

    public Nav(Level lvl) {
        List<Level.ControlPoint> pts = lvl.controlPoints();
        count = pts.size();
        cpX = new int[pts.size()];
        cpZ = new int[pts.size()];
        cpY = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            cpX[i] = pts.get(i).x();
            cpZ[i] = pts.get(i).z();
            cpY[i] = pts.get(i).y();
        }
        walk = lvl.walkRoutes();
        fly = lvl.flyRoutes();
    }

    public int x(int cp) {
        return cp >= 0 && cp < cpX.length ? cpX[cp] : 0;
    }

    public int z(int cp) {
        return cp >= 0 && cp < cpZ.length ? cpZ[cp] : 0;
    }

    public int y(int cp) {
        return cp >= 0 && cp < cpY.length ? cpY[cp] : 0;
    }

    /**
     * GetNextCPt : point suivant sur le chemin de {@code from} vers {@code to}, ou {@link #NO_WAY}.
     * Le pas de la table est de 100 quel que soit le nombre de points (muls #100 dans l'original).
     */
    public int nextCPt(int from, int to, boolean flying) {
        onlySee = false;                               // clr.b ONLYSEE
        if ((short) from == (short) to) {
            return from;                               // noneedforhassle
        }
        int idx = M68k.muls(from, 100) + (short) to;
        int[] links = flying ? fly : walk;
        if (idx < 0 || idx >= links.length) {
            return NO_WAY;                             // hors table : pas de chemin
        }
        int d0 = links[idx] & 0xFF;                    // move.b (a0,d0.w),d0
        onlySee = (d0 & 0x80) != 0;                    // and.b #$80 ; sne ONLYSEE
        d0 = d0 & 0x7f;                                // and.b #$7f,d0
        return (byte) d0;                              // ext.w d0
    }

    /**
     * Harnais : repose la graine du generateur.
     *
     * <p>Le jeu ne le fait qu'une fois, au demarrage — mais un oracle qui rejoue une scene
     * isolee doit pouvoir retrouver le meme hasard que celui qui l'a relevee.
     */
    void seed(int graine) {
        rand1 = graine & 0xFFFF;
    }

    /**
     * L'etat courant du generateur, pour les seules verifications : un oracle qui releve
     * {@code Rand1} apres coup prouve ainsi que le portage a tire le MEME NOMBRE de des, dans
     * le meme ordre. Rien dans le jeu ne lit cet etat.
     */
    int etat() {
        return rand1 & 0xFFFF;
    }

    /** GetRand (Objectmove.java:1214) : {@code rol.w #3 ; add.w #$2343}. */
    public int rand() {
        int d0 = rand1 & 0xFFFF;
        d0 = ((d0 << 3) | (d0 >>> 13)) & 0xFFFF;
        d0 = (d0 + 0x2343) & 0xFFFF;
        rand1 = d0;
        return d0;
    }
}
