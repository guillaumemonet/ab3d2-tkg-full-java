package ab3d2.full.menu;

/**
 * LE MOTEUR DU MENU : un menu ouvert, un curseur qui descend, et une image par appel.
 *
 * <p>L'original est une boucle BLOQUANTE — {@code mnu_waitmenu} tourne sur {@code WaitTOF}
 * jusqu'a ce qu'on choisisse quelque chose. Ici c'est une machine a etats qu'on avance d'une
 * image a chaque appel. Le comportement visible est le meme ; la difference est qu'une fenetre,
 * une surface Android ou un harnais peuvent la conduire sans lui donner un fil d'execution.
 *
 * <h2>Le curseur n'est pas un indice</h2>
 *
 * <p>{@code mnu_row} n'est pas le rang de l'item choisi : c'est un compteur de SEIZE BITS qui
 * court librement, qu'on incremente en descendant et qu'on decremente en montant, et l'item est
 * son RESTE par le nombre d'items. Le jeu l'initialise a {@code items * 3000} — un multiple,
 * donc l'item zero — uniquement pour qu'il soit assez loin de zero pour que monter ne le fasse
 * pas passer sous la barre.
 *
 * <p>C'est ce qui donne au menu son enroulement dans les deux sens, sans un seul test.
 *
 * <h2>Le curseur est une lettre</h2>
 *
 * <p>Ce n'est pas un sprite : c'est un caractere de la police, qu'on imprime a la bonne ligne
 * et qu'on EFFACE en imprimant un espace a l'ancienne. Son animation est une suite d'octets
 * qu'on parcourt une image sur deux.
 */
public final class Menu {

    /** Ou en est le menu. */
    public enum State {
        /** Le texte n'est pas encore pose : trente-cinq images d'attente. */
        OPENING,
        /** On attend un choix. */
        WAITING,
        /** Un item vient d'etre choisi. */
        CHOSEN,
        /** On a recule ({@code Esc}). */
        CANCELLED
    }

    /** {@code move.l #35,timer} : ce que {@code mnu_openmenu} attend avant d'ecrire. */
    private static final int OPEN_DELAY = 35;

    /** Le multiple dont part {@code mnu_row}, pour qu'il ne passe jamais sous zero. */
    private static final int ROW_BASE = 3000;

    /**
     * {@code mnu_cursanim} : les caracteres du curseur, parcourus une image sur deux.
     *
     * <p>Huit dessins d'une fleche qui bat, et la table se termine par un {@code 8} qui n'est
     * pas un neuvieme dessin : c'est de combien reculer. {@code mnu_animcursor} lit l'octet
     * SUIVANT le dessin courant ; au-dela de quarante c'est un dessin de plus, en dessous c'est
     * un recul. Reculer de huit puis avancer de un ramene exactement au debut.
     *
     * <p>La table est donc sa propre boucle, sans compteur ni test de fin.
     */
    private static final int[] CURSOR_ANIM = {130, 129, 128, 127, 126, 125, 124, 123};

    private final MenuFire fire;

    private MenuDef def;
    private State state = State.WAITING;
    private int row;
    private int oldRow;
    private int timer;
    private int cursorStep;
    private int chosen = -1;

    public Menu(MenuFire fire) {
        this.fire = fire;
    }

    /** {@code mnu_openmenu} : efface, attend, puis ecrit le texte et pose le curseur. */
    public void open(MenuDef d) {
        this.def = d;
        fire.clearText();                              // bsr mnu_cls
        timer = OPEN_DELAY;                            // move.l #35,timer
        row = (d.items * ROW_BASE) & 0xFFFF;           // mulu #3000,d0
        oldRow = row;
        state = State.OPENING;
        chosen = -1;
    }

    /** Reecrit le texte sans rien changer d'autre ({@code mnu_redraw}). */
    public void redraw() {
        if (def != null) {
            fire.print(def.text(), def.x, def.y);
        }
    }

    /**
     * Une image.
     *
     * <p>Les trois commandes sont des FRONTS, pas des etats : le jeu lit l'octet de la touche
     * puis le remet a zero ({@code move.b (a0,d0.w),d1 ; clr.b (a0,d0.w)}), de sorte qu'une
     * touche tenue ne fait descendre le curseur qu'une fois.
     */
    public void frame(boolean haut, boolean bas, boolean valider, boolean annuler) {
        fire.frame();                                  // mnu_vblint : le fond et le feu
        animateCursor();

        if (state == State.OPENING) {
            if (--timer > 0) {
                return;
            }
            fire.print(def.text(), def.x, def.y);      // bsr mnu_printxy
            state = State.WAITING;
            drawCursor();
            return;
        }
        if (state != State.WAITING) {
            return;
        }

        if (annuler) {                                 // RAWKEY_ESC
            state = State.CANCELLED;
            return;
        }
        if (valider) {                                 // Entree, Espace, ou la touche de tir
            chosen = selection();
            state = State.CHOSEN;
            return;
        }
        if (haut) {
            row = (row - 1) & 0xFFFF;
        } else if (bas) {
            row = (row + 1) & 0xFFFF;
        }
        if (row != oldRow) {
            // On efface l'ancien curseur avant de poser le nouveau : un espace, a l'ancienne
            // ligne (mnu_cleararrow).
            fire.print(" ", def.curX, ligne(oldRow));
            oldRow = row;
        }
        drawCursor();
    }

    /**
     * {@code mnu_docursor} : le caractere anime, sur la ligne courante.
     *
     * <p>{@code mnu_arrow} porte le CODE du caractere, pas son rang dans la police : c'est
     * {@code mnu_printxy} qui retire trente-deux.
     */
    private void drawCursor() {
        fire.print(String.valueOf((char) CURSOR_ANIM[cursorStep]), def.curX, ligne(row));
    }

    /** {@code mnu_animcursor} : l'animation avance une image sur deux. */
    private void animateCursor() {
        if ((fire.counter() & 1) == 0) {
            return;
        }
        cursorStep = (cursorStep + 1) % CURSOR_ANIM.length;
    }

    /** L'ordonnee de la ligne qu'un compteur designe. */
    private int ligne(int compteur) {
        int r = def.items == 0 ? 0 : Integer.remainderUnsigned(compteur, def.items);
        return r * def.spread + def.curY;
    }

    /** {@code .cpcont} : l'item que le compteur designe. */
    public int selection() {
        return def == null || def.items == 0 ? 0 : Integer.remainderUnsigned(row, def.items);
    }

    public State state() {
        return state;
    }

    /** L'item choisi, ou -1 si rien ne l'a ete. */
    public int chosen() {
        return chosen;
    }

    /** Le menu ouvert. */
    public MenuDef def() {
        return def;
    }
}
