package ab3d2.full.sim;

import ab3d2.full.defs.Database;

import java.util.List;

/**
 * Animation des objets : port de {@code animObj} / {@code DEFANIMOBJ}
 * (Newaliencontrol.java:683-742).
 *
 * <p>Chaque type d'objet a un SCRIPT de 20 pas de 6 octets. Un pas donne le graphique à afficher
 * ({@code gfx} = feuille de sprite ou modèle vectoriel, {@code frame}) et l'index du pas SUIVANT :
 * la durée n'est pas un compteur, elle est encodée par répétition, et un pas qui pointe sur
 * lui-même fige l'objet. Le pas courant est {@code EntT_Timer1_w} de l'entité.
 *
 * <p>Le jeu n'avance l'animation que pour les objets « inquiétés » (visibles, cf.
 * {@code markVisibleAndWorry}) : ici on les anime tous, faute de calcul de visibilité — seule la
 * PHASE des animations peut donc différer, pas leur contenu.
 */
public final class ObjectAnim {

    private List<Database.AnimStep> script;
    private int current;

    private ObjectAnim(List<Database.AnimStep> script, int start) {
        this.script = script;
        this.current = start;
    }

    /**
     * Bascule sur un autre script (DEFANIMOBJ <-> ACTANIMOBJ) en repartant du pas 0, comme le
     * jeu qui remet {@code EntT_Timer1_w} a zero au changement d'etat.
     */
    public void retarget(List<Database.AnimStep> other) {
        if (other == null || other.isEmpty() || other == script) {
            return;
        }
        script = other;
        current = 0;
    }

    /**
     * Crée l'animation d'un objet, ou null s'il n'en a pas (script absent, ou pas 0 qui boucle
     * sur lui-même = objet figé).
     */
    public static ObjectAnim of(List<Database.AnimStep> script) {
        if (script == null || script.isEmpty()) {
            return null;
        }
        Database.AnimStep first = script.get(0);
        if (first.next() == 0) {
            return null;                               // pas unique : rien à animer
        }
        return new ObjectAnim(script, 0);
    }

    /**
     * Crée l'animation même si le script est figé : nécessaire pour les objets ACTIVABLES, dont
     * le script actif peut s'animer alors que celui au repos est statique.
     */
    public static ObjectAnim always(List<Database.AnimStep> script) {
        return script == null || script.isEmpty() ? null : new ObjectAnim(script, 0);
    }

    /** Force le pas courant (EntT_Timer1_w : le jeu y ecrit 1 quand l'arme tire). */
    public void setStep(int i) {
        if (i >= 0 && i < script.size()) {
            current = i;
        }
    }

    /** Pas courant. */
    public Database.AnimStep step() {
        return script.get(current);
    }

    /** Avance d'un pas (une frame de jeu). Renvoie true si le graphique affiché change. */
    public boolean advance() {
        Database.AnimStep before = step();
        int next = before.next();
        if (next < 0 || next >= script.size()) {
            next = 0;
        }
        current = next;
        Database.AnimStep after = step();
        return after.gfx() != before.gfx() || after.frame() != before.frame();
    }
}
