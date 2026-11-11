package ab3d2.full.view;

import ab3d2.full.menu.MenuShell;
import ab3d2.full.menu.Options;
import ab3d2.full.render.Screen;
import ab3d2.full.sim.Campaign;
import ab3d2.full.sim.World;

/**
 * LE JEU ENTIER, sans fenetre : le menu, la partie, et l'aller-retour entre les deux.
 *
 * <p>C'est {@code Game_Start} (controlloop.s:120) : une boucle qui ouvre le menu, attend qu'on
 * choisisse {@code PLAY GAME}, joue un niveau, puis <b>revient au menu</b>. Le jeu ne passe
 * jamais directement d'un niveau au suivant — il repasse par le menu, ou le nom du niveau
 * d'apres s'affiche deja. Deux gestes pour continuer, et c'est ainsi depuis 1996.
 *
 * <p>Deux recopies encadrent chaque niveau, et toute la progression tient dans leur
 * dissymetrie : l'inventaire de la PARTIE est recopie dans celui du joueur AVANT, et le chemin
 * inverse n'est pris QUE si le niveau a ete fini. Voir {@link Campaign}.
 *
 * <h2>Deux palettes, pas une</h2>
 *
 * <p>Le menu et le jeu n'ont pas les memes couleurs. Le jeu lit la palette du niveau ; le menu
 * en CALCULE une, ou la teinte d'une flamme depend du fond qu'elle recouvre. Un octet d'ecran
 * ne veut donc pas dire la meme chose selon le mode, et c'est pourquoi {@link #argb(int)}
 * existe : l'appelant ne doit pas avoir a le savoir.
 */
public final class Shell {

    /** Ou l'on est. */
    public enum Mode {
        /** Dans le menu. */
        MENU,
        /** En train de jouer. */
        GAME,
        /** Le joueur a demande a quitter. */
        QUIT
    }

    private final Campaign campaign;
    private final MenuShell menu;

    private Session session;
    private Mode mode = Mode.MENU;
    private boolean son;

    /** Les reglages de rendu, poses par la ligne de commande et reposes a chaque niveau. */
    private boolean simpleWalls;
    private boolean gouraudFlats = true;
    private boolean music = true;

    public Shell() {
        this.campaign = Campaign.start();
        this.menu = new MenuShell(campaign);
    }

    /** Ouvre le son. A n'appeler qu'une fois. */
    public boolean openSound() {
        son = true;
        return true;
    }

    public void simpleWalls(boolean on) {
        simpleWalls = on;
    }

    public void gouraudFlats(boolean on) {
        gouraudFlats = on;
    }

    public void music(boolean on) {
        music = on;
    }

    public Mode mode() {
        return mode;
    }

    public Campaign campaign() {
        return campaign;
    }

    /** Le niveau en cours, ou {@code null} si l'on est dans le menu. */
    public Session session() {
        return session;
    }

    /** Les reglages du joueur. */
    public Options options() {
        return menu.options();
    }

    /** Saute le menu et lance tout de suite un niveau : c'est ce que fait {@code -Plevel}. */
    public void playNow(int niveau) {
        campaign.goTo(niveau);
        lancer();
    }

    /**
     * Une image.
     *
     * @param in     ce que le joueur demande quand on joue
     * @param haut   front : le curseur monte
     * @param bas    front : le curseur descend
     * @param ok     front : valider
     * @param back   front : reculer
     * @param rawKey le code d'une touche fraichement enfoncee, pour l'affectation des commandes
     */
    public void frame(World.Input in, boolean haut, boolean bas, boolean ok, boolean back,
            int rawKey) {
        if (mode == Mode.MENU) {
            menu.frame(haut, bas, ok, back, rawKey);
            switch (menu.outcome()) {
                case PLAY -> lancer();
                case QUIT -> mode = Mode.QUIT;
                default -> { }
            }
            return;
        }
        if (mode != Mode.GAME || session == null) {
            return;
        }
        session.frame(in);
        if (session.done()) {
            // La suite de Game_Start : l'inventaire n'est emporte que si le niveau a ete fini,
            // puis le seizieme ramene au premier, et l'on revient au menu.
            campaign.carryInventory();
            campaign.wrapIfFinished();
            session.closeSound();
            session = null;
            mode = Mode.MENU;
        }
    }

    /** {@code Game_Begin} : on quitte le menu et l'on monte le niveau courant. */
    private void lancer() {
        session = new Session(campaign);
        session.viewer().simpleWalls(simpleWalls);
        session.viewer().gouraudFlats(gouraudFlats);
        session.viewer().showWeapon = !options().on(Options.HIDE_WEAPON);
        session.viewer().playMusic = music;
        if (son) {
            session.openSound();
        }
        mode = Mode.GAME;
    }

    /** L'ecran a montrer, un octet par pixel. */
    public byte[] pixels() {
        return mode == Mode.GAME && session != null ? session.pixels() : menu.pixels();
    }

    /** La couleur d'un rang, dans la palette qui vaut pour le mode courant. */
    public int argb(int rang) {
        if (mode == Mode.GAME && session != null) {
            return 0xFF000000 | session.palette().at(rang);
        }
        return 0xFF000000 | menu.palette().at(rang, menu.fade());
    }

    public int width() {
        return Screen.WIDTH;
    }

    public int height() {
        return Screen.HEIGHT;
    }
}
