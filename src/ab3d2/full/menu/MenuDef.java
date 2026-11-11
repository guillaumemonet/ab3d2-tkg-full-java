package ab3d2.full.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * UN MENU : son texte, et ou son curseur se promene.
 *
 * <p>Le jeu decrit un menu par un petit enregistrement de seize octets suivi de paires
 * (type, donnee) — abscisse et ordonnee du texte, pointeur sur ce texte, abscisse et ordonnee
 * du curseur, ecart entre deux lignes, nombre d'items. Ici ce sont des champs nommes.
 *
 * <h2>Le texte n'est pas les items</h2>
 *
 * <p>Deux choses se confondent facilement et ne sont pas la meme. Le TEXTE part de
 * {@code (x, y)} et descend de vingt pixels par ligne. Le CURSEUR part de
 * {@code (curX, curY)} et descend de {@code spread}. Le menu principal commence par DEUX
 * LIGNES VIDES, et c'est ce qui place son premier item — « PLAY GAME » — a l'ordonnee quarante,
 * exactement la ou le curseur l'attend.
 *
 * <p>Ces lignes vides ne sont donc pas une maladresse : elles sont le calage.
 *
 * <h2>Les paires que personne ne lit</h2>
 *
 * <p>L'enregistrement d'origine porte des paires (type, donnee) qui decrivent des reglages a
 * glissiere et des listes a faire tourner. Aucun menu du jeu n'en a : tous leurs types valent
 * zero, et le moteur reel se contente de l'INDICE choisi. Ces paires ne sont pas portees ici —
 * le glisseur d'origine est d'ailleurs bogue, il se dessine au mauvais endroit, ce qui se voit
 * d'autant moins qu'il n'est jamais appele.
 */
public final class MenuDef {

    /** L'abscisse du texte, en OCTETS : un octet vaut huit pixels. */
    public final int x;

    /** L'ordonnee de la premiere ligne de texte, en pixels. */
    public final int y;

    /** L'abscisse du curseur, en octets. */
    public final int curX;

    /** L'ordonnee du premier item, en pixels. */
    public final int curY;

    /** De combien de pixels on descend d'un item au suivant. */
    public final int spread;

    /** Combien d'items le curseur peut atteindre. */
    public final int items;

    /** Les lignes de texte ; certaines sont reecrites en cours de route. */
    private final List<String> lignes;

    public MenuDef(int x, int y, int curX, int curY, int spread, int items,
            List<String> lignes) {
        this.x = x;
        this.y = y;
        this.curX = curX;
        this.curY = curY;
        this.spread = spread;
        this.items = items;
        this.lignes = new ArrayList<>(lignes);
    }

    /** Les lignes, dans l'ordre. */
    public List<String> lines() {
        return lignes;
    }

    /** Reecrit une ligne : c'est ainsi que le nom du niveau courant arrive dans le menu. */
    public void line(int i, String texte) {
        if (i >= 0 && i < lignes.size()) {
            lignes.set(i, texte);
        }
    }

    /** La ligne {@code i}, ou la chaine vide. */
    public String line(int i) {
        return i >= 0 && i < lignes.size() ? lignes.get(i) : "";
    }

    /**
     * Le texte tel que {@code mnu_printxy} le recoit : les lignes separees par un caractere
     * en dessous de l'espace, qui vaut retour a la ligne.
     */
    public String text() {
        return String.join("\u0001", lignes);
    }
}
