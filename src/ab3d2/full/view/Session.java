package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.render.Screen;
import ab3d2.full.render.Shimmer;
import ab3d2.full.sim.Campaign;
import ab3d2.full.sim.World;

/**
 * LA PARTIE EN TRAIN DE SE JOUER : la simulation, le rendu et le son d'un niveau, et ce qui
 * se passe quand ce niveau s'acheve.
 *
 * <p>Cette classe ne connait ni fenetre, ni clavier, ni haut-parleur. Elle prend une intention
 * par image et rend un ecran — trois cent vingt octets par ligne, deux cent cinquante-six
 * lignes, chaque octet un rang de palette. Ce qui la conduit peut etre une fenetre Swing, une
 * surface Android ou un harnais qui ne dessine rien : c'est la raison d'etre de ce decoupage.
 *
 * <h2>Les deux temps d'une partie</h2>
 *
 * <p>{@code game_main_loop} tourne jusqu'a ce que le joueur atteigne la sortie ou perde la vie.
 * Alors {@code endlevel} prend la main, et le jeu fait une chose qu'on oublie en le lisant :
 * <b>il ne dessine plus rien</b>. Sa boucle est « {@code CALLGRAF WaitTOF ; jsr mt_music ;
 * tst.b reachedend ; beq} » — l'ecran reste fige sur la derniere image pendant que la musique
 * va jusqu'a son terme. C'est pour cela qu'on voit si longtemps le couloir ou l'on vient de
 * mourir.
 *
 * <p>Le niveau suivant n'est monte qu'ensuite.
 */
public final class Session {

    /** Ou en est la partie. */
    public enum Phase {
        /** On joue : la simulation avance et l'image se refait. */
        PLAYING,
        /** Le niveau est fini : l'image est figee, la musique finit de se jouer. */
        ENDING
    }

    private final Campaign campaign;
    private final Shimmer shimmer = new Shimmer(Data.shimmer());

    private Viewer viewer;
    private World world;
    private Phase phase = Phase.PLAYING;
    private Campaign.Outcome outcome;

    /** Les reglages que la fenetre pose, et qu'il faut reposer a chaque changement de niveau. */
    private boolean simpleWalls;
    private boolean gouraudFlats = true;
    private boolean showWeapon = true;
    private boolean playMusic = true;
    private boolean sonOuvert;

    /** Le pas de la dematerialisation pour l'image en cours, ou -1 si l'image est nette. */
    private int shimmerFrame = -1;

    public Session() {
        this(0);
    }

    /** @param depart le rang du niveau ou commencer, de zero a quinze */
    public Session(int depart) {
        this.campaign = Campaign.start();
        this.campaign.goTo(depart);
        monter();
    }

    /** La partie : le compteur de niveau et l'inventaire qu'on emporte. */
    public Campaign campaign() {
        return campaign;
    }

    /** Le niveau en cours. */
    public World world() {
        return world;
    }

    /** De quoi lire la sante, les armes, le nombre de monstres. */
    public Viewer viewer() {
        return viewer;
    }

    public Phase phase() {
        return phase;
    }

    /** Ce que le dernier niveau a donne, ou {@code null} tant qu'aucun ne s'est acheve. */
    public Campaign.Outcome outcome() {
        return outcome;
    }

    /** Ouvre le son. A n'appeler qu'une fois : les niveaux suivants le rouvrent seuls. */
    public boolean openSound() {
        sonOuvert = viewer.openSound();
        return sonOuvert;
    }

    public void simpleWalls(boolean on) {
        simpleWalls = on;
        viewer.simpleWalls(on);
    }

    public void gouraudFlats(boolean on) {
        gouraudFlats = on;
        viewer.gouraudFlats(on);
    }

    public void showWeapon(boolean on) {
        showWeapon = on;
        viewer.showWeapon = on;
    }

    public void playMusic(boolean on) {
        playMusic = on;
        viewer.playMusic = on;
    }

    /**
     * Une image.
     *
     * @param in ce que le joueur demande ; ignore pendant la musique de fin
     */
    public void frame(World.Input in) {
        if (phase == Phase.ENDING) {
            finir();
            return;
        }
        jouer(in);
    }

    /** {@code game_main_loop} : une image de jeu. */
    private void jouer(World.Input in) {
        world.frame(in);

        int zone = world.player.zone;
        if (zone >= 0) {
            viewer.draw(world, world.player.xOff >> 16, world.player.zOff >> 16, zone,
                    world.player.angPos, world.player.yOff);
            viewer.sound(world, world.player.xOff >> 16, world.player.zOff >> 16,
                    world.player.angPos);
            viewer.overlay(world, System.currentTimeMillis());
        }
        // Vid_Present : la dematerialisation se consomme a la PRESENTATION, une unite par
        // image, qu'on soit ou non dans la zone de sortie.
        shimmerFrame = world.shimmerStep();

        if (world.finished) {                          // jmp endlevel
            outcome = campaign.endLevel();
            viewer.board().push(outcome == Campaign.Outcome.WON
                    ? "NIVEAU TERMINE" : "VOUS ETES MORT", MessageBoard.OTHER);
            viewer.endMusic(outcome == Campaign.Outcome.WON
                    ? Data.wellDone() : Data.gameOver());
            phase = Phase.ENDING;
        }
    }

    /** {@code playwelldone} / {@code playgameover} : l'image est figee, la musique court. */
    private void finir() {
        if (!viewer.endMusicFrame()) {
            return;
        }
        // La suite de Game_Start : l'inventaire n'est emporte que si le niveau a ete fini, puis
        // le seizieme niveau ramene au premier.
        campaign.carryInventory();
        campaign.wrapIfFinished();
        viewer.endMusic(null);
        viewer.closeSound();
        monter();
        phase = Phase.PLAYING;
    }

    /** {@code Game_Begin} : le niveau suivant, monte de zero. */
    private void monter() {
        world = campaign.begin();
        viewer = new Viewer(campaign.letter(), world.level);
        viewer.simpleWalls(simpleWalls);
        viewer.gouraudFlats(gouraudFlats);
        viewer.showWeapon = showWeapon;
        viewer.playMusic = playMusic;
        if (sonOuvert) {
            sonOuvert = viewer.openSound();
        }
        // L'eclairage est le MEME objet des deux cotes : la simulation y ecrit, le peintre y lit.
        world.light = viewer.lights();
        world.aliens.dynLight = viewer.lights();
        world.shots.dynLight = viewer.lights();
        viewer.lights().heights(Viewer.hauteurs(world));
        shimmerFrame = -1;
        viewer.board().push(campaign.name(), MessageBoard.OTHER);
    }

    /**
     * L'ecran a montrer : celui que le peintre vient d'ecrire, ou sa version delitee quand la
     * dematerialisation est en cours.
     */
    public byte[] pixels() {
        byte[] brut = viewer.screen().pixels();
        return shimmerFrame < 0 ? brut : shimmer.apply(brut, shimmerFrame);
    }

    /** La hauteur de l'image, bandeau compris. */
    public int height() {
        return Screen.HEIGHT;
    }

    /** La largeur de l'image. */
    public int width() {
        return Screen.WIDTH;
    }
}
