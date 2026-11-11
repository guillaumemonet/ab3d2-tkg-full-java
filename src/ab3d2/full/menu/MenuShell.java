package ab3d2.full.menu;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.sim.Campaign;
import ab3d2.full.sim.Inventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * LA COQUILLE : ce que le menu fait quand on choisit quelque chose.
 *
 * <p>{@code game_ReadMainMenu} (controlloop.s:353) est une suite de comparaisons sur l'indice
 * choisi, et chacune mene a une page ou a une action. On la porte telle quelle — un aiguillage,
 * pas une table — parce que c'est ainsi qu'on voit tout de suite ce qui manque : l'item QUATRE,
 * {@code GAME CREDITS}, appelle une routine dont le {@code jsr} est COMMENTE dans l'original.
 * Il ne fait rien, et ce rien est fidele.
 *
 * <h2>Les choix du menu ne lancent pas le jeu</h2>
 *
 * <p>Choisir un niveau dans la page des niveaux ne demarre rien : cela ne fait qu'ecrire
 * {@code Game_LevelCounter_w} et revenir au menu principal, ou le nom du niveau choisi s'affiche
 * desormais. Il faut ensuite choisir {@code PLAY GAME}. Deux gestes, et le jeu en a toujours eu
 * deux.
 *
 * <p>La page des niveaux appelle aussi {@code DEFGAME}, qui chargeait une partie par defaut
 * propre a chaque niveau depuis le disque ({@code ab3:levels/level_x/deflev.dat}). Ce fichier
 * n'est pas dans les donnees publiees ; ne pas le lire laisse l'inventaire tel quel, ce qui est
 * le comportement du jeu quand le fichier manque.
 */
public final class MenuShell {

    /** Ce que la coquille demande au programme qui la conduit. */
    public enum Outcome {
        /** Rien encore : on est toujours dans le menu. */
        NONE,
        /** {@code PLAY GAME} : lancer la partie au niveau courant. */
        PLAY,
        /** {@code EXIT}. */
        QUIT
    }

    /** Quelle page est ouverte. */
    private enum Page { MAIN, LEVELS_ONE, LEVELS_TWO, OPTIONS, KEYS_ONE, KEYS_TWO, LOAD, SAVE }

    private final MenuFire fire;
    private final MenuPalette palette;
    private final Menu menu;
    private final Campaign campaign;
    private final Options options;
    private final SaveSlots slots;
    private final Database glf;

    private Page page = Page.MAIN;
    private Outcome outcome = Outcome.NONE;

    /** Le tampon d'ecran : un octet par pixel, chaque octet un rang de la palette du menu. */
    private final byte[] pixels = new byte[MenuFire.WIDTH * MenuFire.HEIGHT];

    /** Le fondu courant, de zero a 256. */
    private int fade;

    /** De combien le fondu avance par image : {@code mnu_fadespeed}. */
    private static final int FADE_SPEED = 16;

    /** En attente d'une touche pour l'affecter a une action, ou -1. */
    private int awaitingKey = -1;

    public MenuShell(Campaign campaign) {
        this.campaign = campaign;
        this.glf = Data.database();
        this.fire = new MenuFire(Data.menu("back2.raw"), Data.menu("font16x16.raw2"));
        this.palette = new MenuPalette(Data.menu("back.pal"), Data.menu("firepal.pal2"),
                Data.menu("font16x16.pal2"));
        this.menu = new Menu(fire);
        Path racine = Data.saveDir();
        this.options = new Options(racine.resolve("prefs.cfg"));
        this.slots = new SaveSlots(racine.resolve("boot.dat"));
        openMain();
    }

    /** Les reglages du joueur, que la partie consulte. */
    public Options options() {
        return options;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** La palette du menu : elle n'est pas celle du jeu. */
    public MenuPalette palette() {
        return palette;
    }

    /** Le fondu courant, a appliquer a la palette. */
    public int fade() {
        return fade;
    }

    /** L'ecran du menu, compose. */
    public byte[] pixels() {
        fire.composite(pixels);
        return pixels;
    }

    /**
     * Une image de menu.
     *
     * <p>Les commandes sont des FRONTS : le jeu lit l'octet de la touche puis le remet a zero,
     * donc une touche tenue n'agit qu'une fois.
     *
     * @param rawKey le code d'une touche fraichement enfoncee, ou zero — pour l'affectation
     *               des commandes, ou n'importe quelle touche convient
     */
    public void frame(boolean haut, boolean bas, boolean valider, boolean annuler, int rawKey) {
        if (fade < 256) {
            fade = Math.min(256, fade + FADE_SPEED);   // mnu_fadein
        }
        if (awaitingKey >= 0) {
            fire.frame();
            if (rawKey != 0) {
                options.key(awaitingKey, rawKey);
                awaitingKey = -1;
                redrawKeys();
            }
            return;
        }
        menu.frame(haut, bas, valider, annuler);
        if (menu.state() == Menu.State.CHOSEN) {
            choisir(menu.chosen());
        } else if (menu.state() == Menu.State.CANCELLED) {
            openMain();
        }
    }

    // ------------------------------------------------- l'aiguillage

    private void choisir(int item) {
        switch (page) {
            case MAIN -> mainMenu(item);
            case LEVELS_ONE -> levels(item, 0);
            case LEVELS_TWO -> levels(item, 8);
            case OPTIONS -> optionsMenu(item);
            case KEYS_ONE -> keys(item, Menus.KEYS_ONE.length, Page.KEYS_TWO, 0);
            case KEYS_TWO -> keys(item, Menus.KEYS_TWO.length, Page.MAIN,
                    Menus.KEYS_ONE.length);
            case LOAD -> load(item);
            case SAVE -> save(item);
            default -> openMain();
        }
    }

    /** {@code game_ReadMainMenu}. */
    private void mainMenu(int item) {
        switch (item) {
            case Menus.MAIN_PLAY -> outcome = Outcome.PLAY;
            case Menus.MAIN_LEVEL -> open(Page.LEVELS_ONE, Menus.levelsOne());
            case Menus.MAIN_CONTROLS -> open(Page.KEYS_ONE, Menus.keysOne());
            // GAME CREDITS : « ;jsr mnu_viewcredz » est COMMENTE dans l'original. Le jeu
            // rouvre simplement le menu principal, et c'est tout ce qu'il fait.
            case Menus.MAIN_CREDITS -> openMain();
            case Menus.MAIN_LOAD -> open(Page.LOAD, Menus.load(labels(1, Menus.SLOTS)));
            case Menus.MAIN_SAVE -> open(Page.SAVE, Menus.save(labels(1, Menus.SLOTS)));
            case Menus.MAIN_OPTIONS -> openOptions();
            case Menus.MAIN_EXIT -> outcome = Outcome.QUIT;
            // 1 PLAYER mene au menu a deux joueurs, qui n'est pas porte : on reste ici.
            default -> openMain();
        }
    }

    /** {@code levelMenu} / {@code levelMenu2}. */
    private void levels(int item, int decalage) {
        if (item == Menus.LEVEL_NEXT) {
            if (decalage == 0) {
                open(Page.LEVELS_TWO, Menus.levelsTwo());
            } else {
                openMain();
            }
            return;
        }
        campaign.goTo(decalage + item);                // move d0,Game_LevelCounter_w
        openMain();
    }

    /** {@code customOptions} : chaque item retourne sa bascule, le dernier ramene. */
    private void optionsMenu(int item) {
        if (item == Menus.OPTION_BACK) {
            openMain();
            return;
        }
        options.toggle(item);
        redrawOptions();
    }

    /** {@code CHANGECONTROLS} : choisir une action, puis appuyer sur la touche voulue. */
    private void keys(int item, int combien, Page suivante, int premier) {
        if (item >= combien) {
            if (suivante == Page.MAIN) {
                openMain();
            } else {
                open(suivante, Menus.keysTwo());
            }
            return;
        }
        awaitingKey = premier + item;
        // Le jeu EFFACE l'affichage de la touche pendant qu'il attend : c'est ainsi qu'on sait
        // qu'il attend.
        redrawKeys();
    }

    private void load(int item) {
        if (item == 0) {                               // NEW GAME
            campaign.newGame();
            openMain();
            return;
        }
        if (item > Menus.SLOTS) {                      // CANCEL
            openMain();
            return;
        }
        Inventory inv = campaign.party();
        if (slots.used(item)) {
            slots.read(item, inv);
            campaign.goTo(slots.level(item));
        }
        openMain();
    }

    private void save(int item) {
        if (item >= Menus.SLOTS) {                     // CANCEL
            openMain();
            return;
        }
        slots.write(item + 1, campaign.level(), campaign.party());
        openMain();
    }

    // ------------------------------------------------- l'ouverture des pages

    private void openMain() {
        MenuDef d = Menus.main();
        d.line(Menus.MAIN_LEVEL_LINE, levelName());
        open(Page.MAIN, d);
    }

    private void openOptions() {
        open(Page.OPTIONS, Menus.options());
        redrawOptions();
    }

    private void open(Page p, MenuDef d) {
        page = p;
        menu.open(d);
    }

    /**
     * {@code game_SetMenuLevelName} : le nom du niveau courant, centre dans la ligne.
     *
     * <p>Le nom vient de la base de liaison, sur quarante octets ; l'original le recopie dans
     * la ligne du menu sans la redimensionner.
     */
    private String levelName() {
        Database.LevelInfo i = glf.levels().get(campaign.level());
        String nom = i.name().trim();
        int marge = Math.max(0, (20 - nom.length()) / 2);
        return " ".repeat(marge) + nom;
    }

    /** Reecrit les huit lignes de reglage avec leur {@code Y} ou leur {@code N}. */
    private void redrawOptions() {
        MenuDef d = menu.def();
        for (int i = 0; i < Options.COUNT; i++) {
            String base = Menus.OPTION_NAMES[i];
            StringBuilder b = new StringBuilder(base);
            while (b.length() < Menus.OPTION_COLUMN) {
                b.append(' ');
            }
            b.setLength(Menus.OPTION_COLUMN);
            b.append(options.on(i) ? 'Y' : 'N');
            d.line(Menus.OPTION_FIRST_LINE + i, b.toString());
        }
        fire.clearText();
        menu.redraw();
    }

    /** Reecrit les lignes de touches, avec le glyphe de chacune. */
    private void redrawKeys() {
        MenuDef d = menu.def();
        String[] noms = page == Page.KEYS_ONE ? Menus.KEYS_ONE : Menus.KEYS_TWO;
        int premier = page == Page.KEYS_ONE ? 0 : Menus.KEYS_ONE.length;
        for (int i = 0; i < noms.length; i++) {
            StringBuilder b = new StringBuilder(noms[i]);
            while (b.length() < Menus.OPTION_COLUMN) {
                b.append(' ');
            }
            b.setLength(Menus.OPTION_COLUMN);
            int code = options.key(premier + i);
            // La touche qu'on attend ne s'affiche pas : c'est le signe qu'on attend.
            b.append(awaitingKey == premier + i || code == 0 ? ' ' : (char) code);
            d.line(i, b.toString());
        }
        fire.clearText();
        menu.redraw();
    }

    private List<String> labels(int premier, int combien) {
        List<String> l = new ArrayList<>(combien);
        for (int i = 0; i < combien; i++) {
            l.add(slots.label(premier + i));
        }
        return l;
    }
}
