package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.level.Palette;
import ab3d2.full.render.Screen;
import ab3d2.full.render.Shimmer;
import ab3d2.full.sim.Campaign;
import ab3d2.full.sim.World;

/**
 * UN NIVEAU EN TRAIN D'ETRE JOUE : la simulation, le rendu et le son, et ce qui se passe quand
 * ce niveau s'acheve.
 *
 * <p>Cette classe ne connait ni fenetre, ni clavier, ni haut-parleur. Elle prend une intention
 * par image et rend un ecran — trois cent vingt octets par ligne, deux cent cinquante-six
 * lignes, chaque octet un rang de palette. Ce qui la conduit peut etre une fenetre Swing, une
 * surface Android ou un harnais qui ne dessine rien : c'est la raison d'etre de ce decoupage.
 *
 * <h2>Les deux temps d'un niveau</h2>
 *
 * <p>{@code game_main_loop} tourne jusqu'a ce que le joueur atteigne la sortie ou perde la vie.
 * Alors {@code endlevel} prend la main, et le jeu fait une chose qu'on oublie en le lisant :
 * <b>il ne dessine plus rien</b>. Sa boucle est « {@code CALLGRAF WaitTOF ; jsr mt_music ;
 * tst.b reachedend ; beq} » — l'ecran reste fige sur la derniere image pendant que la musique
 * va jusqu'a son terme. C'est pour cela qu'on voit si longtemps le couloir ou l'on vient de
 * mourir.
 *
 * <p>Ensuite le jeu ne charge PAS le niveau suivant : il retourne au MENU
 * ({@code game_BackToMenu}), ou le nom du niveau d'apres s'affiche deja. C'est a l'appelant de
 * le voir, par {@link #done()}.
 */
public final class Session {

    /** Ou en est le niveau. */
    public enum Phase {
        /** On joue : la simulation avance et l'image se refait. */
        PLAYING,
        /** Le niveau est fini : l'image est figee, la musique finit de se jouer. */
        ENDING,
        /** La musique s'est tue : il n'y a plus rien a faire ici. */
        OVER
    }

    private final Campaign campaign;
    private final Shimmer shimmer = new Shimmer(Data.shimmer());
    private final Viewer viewer;
    private final World world;

    private Phase phase = Phase.PLAYING;
    private Campaign.Outcome outcome;

    /** Le pas de la dematerialisation pour l'image en cours, ou -1 si l'image est nette. */
    private int shimmerFrame = -1;

    /** {@code Game_Begin} : le niveau courant de la partie, monte de zero. */
    public Session(Campaign campaign) {
        this(campaign, ab3d2.full.sound.Sink.SILENT);
    }

    /** @param sortie ou le son part */
    public Session(Campaign campaign, ab3d2.full.sound.Sink sortie) {
        this.campaign = campaign;
        this.world = campaign.begin();
        this.viewer = new Viewer(campaign.letter(), world.level, sortie);
        // L'eclairage est le MEME objet des deux cotes : la simulation y ecrit (torches, tirs),
        // le peintre y lit. C'est le seul point ou les deux moities du jeu se touchent.
        world.light = viewer.lights();
        world.aliens.dynLight = viewer.lights();
        world.shots.dynLight = viewer.lights();
        viewer.lights().heights(Viewer.hauteurs(world));
        viewer.board().push(campaign.name().trim(), MessageBoard.OTHER);
    }

    public Campaign campaign() {
        return campaign;
    }

    public World world() {
        return world;
    }

    public Viewer viewer() {
        return viewer;
    }

    public Phase phase() {
        return phase;
    }

    /** Vrai quand le niveau est derriere nous, musique comprise. */
    public boolean done() {
        return phase == Phase.OVER;
    }

    /** Ce que le niveau a donne, ou {@code null} tant qu'il n'est pas fini. */
    public Campaign.Outcome outcome() {
        return outcome;
    }

    /** La palette du jeu. */
    public Palette palette() {
        return Data.palette();
    }

    public boolean openSound() {
        return viewer.openSound();
    }

    public void closeSound() {
        viewer.endMusic(null);
        viewer.closeSound();
    }

    /**
     * Une image.
     *
     * @param in ce que le joueur demande ; ignore pendant la musique de fin
     */
    public void frame(World.Input in) {
        switch (phase) {
            case PLAYING -> jouer(in);
            case ENDING -> finir();
            default -> { }
        }
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
        if (viewer.endMusicFrame()) {
            phase = Phase.OVER;
        }
    }

    /**
     * L'ecran a montrer : celui que le peintre vient d'ecrire, ou sa version delitee quand la
     * dematerialisation est en cours.
     */
    public byte[] pixels() {
        byte[] brut = viewer.screen().pixels();
        return shimmerFrame < 0 ? brut : shimmer.apply(brut, shimmerFrame);
    }

    public int height() {
        return Screen.HEIGHT;
    }

    public int width() {
        return Screen.WIDTH;
    }
}
