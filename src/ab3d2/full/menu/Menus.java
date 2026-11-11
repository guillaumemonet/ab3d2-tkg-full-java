package ab3d2.full.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LES MENUS DU JEU, tels que l'assembleur les declare.
 *
 * <p>Chacun est une suite de lignes et quatre nombres. Les deux premieres lignes de la plupart
 * sont VIDES : c'est ce qui descend le premier item a l'ordonnee ou le curseur l'attend.
 *
 * <p>Les menus a deux joueurs ({@code mnu_MYMASTERMENU}, {@code mnu_MYSLAVEMENU}) ne sont pas
 * ici : le mode a deux n'est pas porte, et un menu qui mene a rien vaut moins qu'un menu
 * absent.
 */
public final class Menus {

    /** L'ecart entre deux items, en pixels. */
    private static final int SPREAD = 20;

    private Menus() {
    }

    // ------------------------------------------------- le menu principal

    /** Le rang de la ligne qui porte le nom du niveau courant. */
    public static final int MAIN_LEVEL_LINE = 4;

    /** Les neuf items du menu principal, dans l'ordre de {@code game_ReadMainMenu}. */
    public static final int MAIN_PLAY = 0;
    public static final int MAIN_TWO_PLAYER = 1;
    public static final int MAIN_LEVEL = 2;
    public static final int MAIN_CONTROLS = 3;
    public static final int MAIN_CREDITS = 4;
    public static final int MAIN_LOAD = 5;
    public static final int MAIN_SAVE = 6;
    public static final int MAIN_OPTIONS = 7;
    public static final int MAIN_EXIT = 8;

    /** {@code mnu_MYMAINMENU}. */
    public static MenuDef main() {
        return new MenuDef(0, 0, 0, 40, SPREAD, 9, lignes(
                "", "",
                "     PLAY  GAME",
                "      1 PLAYER",
                "",                                    // le nom du niveau courant
                "  CONTROL  OPTIONS",
                "    GAME CREDITS",
                "   LOAD  POSITION",
                "   SAVE  POSITION",
                "   CUSTOM OPTIONS",
                "        EXIT"));
    }

    // ------------------------------------------------- le choix du niveau

    /** L'item « page suivante » / « menu principal » des deux pages de niveaux. */
    public static final int LEVEL_NEXT = 8;

    /** {@code mnu_MYLEVELMENU} : les niveaux A a H. */
    public static MenuDef levelsOne() {
        return levels("ABCDEFGH", "     NEXT  PAGE");
    }

    /** {@code mnu_MYLEVELMENU2} : les niveaux I a P. */
    public static MenuDef levelsTwo() {
        return levels("IJKLMNOP", "     MAIN  MENU");
    }

    private static MenuDef levels(String lettres, String dernier) {
        List<String> l = new ArrayList<>();
        l.add("");
        l.add("");
        for (char c : lettres.toCharArray()) {
            l.add("      LEVEL  " + c);
        }
        l.add(dernier);
        return new MenuDef(0, 0, 0, 40, SPREAD, 9, l);
    }

    // ------------------------------------------------- les reglages

    /** Le rang de la premiere ligne de reglage dans {@link #options()}. */
    public static final int OPTION_FIRST_LINE = 2;

    /** La colonne, en caracteres, ou le {@code Y} ou le {@code N} s'ecrit. */
    public static final int OPTION_COLUMN = 17;

    /** Les huit reglages, dans l'ordre du menu. */
    public static final String[] OPTION_NAMES = {
        "  ORIGINAL MOUSE", "  ALWAYS RUN", "  SHOW MESSAGES", "  NO AUTO AIM",
        "  SHOW FPS", "  HIDE WEAPON", "  OPTION 7", "  OPTION 8",
    };

    /** L'item qui ramene au menu principal. */
    public static final int OPTION_BACK = 8;

    /** {@code mnu_MYCUSTOMOPTSMENU}. */
    public static MenuDef options() {
        List<String> l = new ArrayList<>();
        l.add("");
        l.add("");
        l.addAll(Arrays.asList(OPTION_NAMES));
        l.add("     MAIN  MENU");
        return new MenuDef(0, 0, 0, 40, SPREAD, 9, l);
    }

    // ------------------------------------------------- les touches

    /** Les onze touches de la premiere page, puis {@code MORE}. */
    public static final String[] KEYS_ONE = {
        "  TURN LEFT", "  TURN RIGHT", "  FORWARDS", "  BACKWARDS", "  FIRE",
        "  OPERATE", "  RUN", "  FORCE S/S", "  S/S LEFT", "  S/S RIGHT", "  CROUCH",
    };

    /** Les six de la seconde, puis {@code MAIN MENU}. */
    public static final String[] KEYS_TWO = {
        "  LOOK BEHIND", "  JUMP", "  LOOK UP", "  LOOK DOWN", "  CENTRE VIEW",
        "  NEXT WEAPON",
    };

    /** {@code mnu_MYCONTROLSONE} : douze items, le dernier menant a la page deux. */
    public static MenuDef keysOne() {
        List<String> l = new ArrayList<>(Arrays.asList(KEYS_ONE));
        l.add("        MORE");
        return new MenuDef(0, 0, 0, 0, SPREAD, 12, l);
    }

    /** {@code mnu_MYCONTROLSTWO} : sept items, le dernier ramenant au menu principal. */
    public static MenuDef keysTwo() {
        List<String> l = new ArrayList<>(Arrays.asList(KEYS_TWO));
        l.add("     MAIN  MENU");
        return new MenuDef(0, 40, 0, 40, SPREAD, 7, l);
    }

    // ------------------------------------------------- charger et sauver

    /** Le nombre d'emplacements de sauvegarde. */
    public static final int SLOTS = 5;

    /**
     * {@code mnu_MYLOADMENU} : une nouvelle partie, cinq emplacements, et renoncer.
     *
     * <p>Sept items pour six lignes utiles : le titre ne compte pas, mais il occupe la
     * premiere ordonnee — c'est pourquoi le curseur part vingt pixels plus bas que le texte.
     */
    public static MenuDef load(List<String> emplacements) {
        List<String> l = new ArrayList<>();
        l.add("   LOAD  POSITION");
        l.add("      NEW GAME");
        l.addAll(emplacements);
        l.add("       CANCEL");
        return new MenuDef(0, 40, 0, 60, SPREAD, 7, l);
    }

    /** {@code mnu_MYSAVEMENU} : cinq emplacements, et renoncer. */
    public static MenuDef save(List<String> emplacements) {
        List<String> l = new ArrayList<>();
        l.add("   SAVE  POSITION");
        l.addAll(emplacements);
        l.add("       CANCEL");
        return new MenuDef(0, 40, 0, 60, SPREAD, 6, l);
    }

    private static List<String> lignes(String... l) {
        return new ArrayList<>(Arrays.asList(l));
    }
}
