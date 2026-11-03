package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.level.FloorTexture;
import ab3d2.full.level.Level;
import ab3d2.full.level.ShadeTable;
import ab3d2.full.level.WaterFrames;
import ab3d2.full.level.WallTexture;

import java.util.List;

/**
 * Le peintre : il execute ce que {@link Scene} decide.
 *
 * <p>{@link Scene} marche les secteurs, resserre les fenetres et ordonne les commandes, mais
 * ne touche pas un pixel. Celui-ci ne decide rien et les dessine tous. C'est la premiere fois
 * que le rendu de ce portage tourne en entier plutot que morceau par morceau.
 *
 * <h2>Ce qu'il ne dessine pas encore</h2>
 *
 * <p>Les CHOSES manquent, et pas par negligence : leurs champs de dessin — la feuille, l'image,
 * les drapeaux, les demi-tailles — ne vivent pas dans le fichier de niveau. Ils y sont tous a
 * zero, et c'est l'animation des objets qui les remplit a chaque image. Tant qu'elle n'est pas
 * portee, un sprite n'a rien a montrer : {@link #objects} compte les commandes et passe. Les
 * verifications des sprites contournent ce trou en SEMANT ces champs ; une image, elle, ne peut
 * pas se le permettre sans mentir.
 *
 * <p>Les murs se peignent tous par la voie SIMPLE. Le degrade de Gouraud attend les lumieres
 * dynamiques, et le jeu lui-meme retombe sur la voie simple quand les quatre coins s'accordent.
 */
public final class ScenePainter implements Scene.Painter {

    private final Screen screen;
    private final View view;
    private final Lights lights;
    private final List<WallTexture> walls;
    private final FloorTexture floors;
    private final ShadeTable shades;

    private final Wall wall;
    private final WallPaint wallPaint;
    private final Flat flat;
    private final FlatPaint flatPaint;
    private final Sky sky;

    private int playerY;
    private int centreY;
    private int angle;

    private int wallsDrawn;
    private int flatsDrawn;
    private int watersDrawn;
    private int skiesDrawn;
    private int thingsSkipped;
    private int offTable;

    /**
     * @param screen le tampon ou tout se depose
     * @param view   la vue, deja tournee
     * @param lights les luminosites du moment
     * @param walls  les textures de murs du niveau
     * @param floors les tuiles de sol du niveau
     * @param shades la table des teintes
     * @param ripples les images de l'eau
     * @param water  l'animation de l'eau
     * @param sinCos la table de sinus
     * @param sky    le ciel, ou {@code null} si le niveau n'en a pas
     */
    public ScenePainter(Screen screen, View view, Lights lights, List<WallTexture> walls,
            FloorTexture floors, ShadeTable shades, WaterFrames ripples, Water water,
            SinCos sinCos, Sky sky, int[] iterations) {
        this.screen = screen;
        this.view = view;
        this.lights = lights;
        this.walls = walls;
        this.floors = floors;
        this.shades = shades;
        this.wall = new Wall(iterations);
        this.wallPaint = new WallPaint(screen);
        this.flat = new Flat();
        this.flatPaint = new FlatPaint(screen);
        this.flatPaint.water(ripples, water, sinCos);
        this.sky = sky;
    }

    /**
     * Pose ce qui ne change pas d'une commande a l'autre.
     *
     * @param playerX l'oeil
     * @param playerZ l'oeil
     * @param playerY la hauteur de l'oeil, en virgule fixe comme le jeu la tient
     * @param angle   l'orientation du regard
     * @param sinCos  la table de sinus
     */
    public void eye(int playerX, int playerZ, int playerY, int angle, SinCos sinCos) {
        this.playerY = playerY;
        this.angle = angle;
        this.centreY = view.height() / 2;
        wallPaint.eye(0, view.height(), 0, view.fullScreen());
        flat.eye(view, playerX, playerZ, playerY, 0, 0, sinCos.sin(angle), sinCos.cos(angle),
                centreY, 0);
        flat.water(true);
        flat.clearWaterTint();
        flat.gouraud(false);
    }

    /**
     * Le FOND de ciel, pose avant tout le reste.
     *
     * <p>Il ne vient pas de la boucle des secteurs : la commande de ciel du flux d'habillage
     * existe, mais sur les quarante et un mille commandes relevees aux seize niveaux, elle
     * n'est JAMAIS emise. Le jeu dessine son ciel ailleurs — a l'endroit ou il installe le
     * secteur du joueur, d'apres l'octet que ce secteur porte. C'est donc le secteur ou l'on
     * SE TIENT qui decide s'il y a un ciel, pas celui qu'on regarde.
     *
     * @param zone le secteur du joueur
     */
    public void backdrop(int zone) {
        if (sky == null) {
            return;
        }
        sky.draw(screen, zone, angle, centreY, view.fullScreen());
        skiesDrawn++;
    }

    /** Combien de pans, de surfaces, de plans d'eau et de ciels ont ete peints. */
    public String tally() {
        return wallsDrawn + " pans, " + flatsDrawn + " surfaces, " + watersDrawn + " plans"
                + " d'eau, " + skiesDrawn + " ciels, " + thingsSkipped + " commandes d'objets"
                + " passees, " + offTable + " hors table";
    }

    @Override
    public void wall(Scene.Pass pass, Level.Wall w) {
        WallTexture peau = w.texture() < walls.size() ? walls.get(w.texture()) : null;
        if (peau == null) {
            return;
        }
        int gauche = Scene.wallBright(lights, pass, w, false, false);
        int droite = Scene.wallBright(lights, pass, w, false, true);
        wall.draw(view, w, playerY, centreY, pass.leftClip(), pass.rightClip(), gauche, droite,
                true);
        if (wall.outOfTable() > 0) {
            offTable++;
            return;                                    // voir Wall.beyond : pan non dessinable
        }
        wallPaint.wall(w, peau, 0);
        for (Wall.Strip s : wall.strips()) {
            wallPaint.paint(s);
        }
        wallsDrawn++;
    }

    @Override
    public void flat(Scene.Pass pass, Level.Flat f) {
        flat.room(pass.topOfRoom(), pass.bottomOfRoom(), pass.bright(), pass.leftClip(),
                pass.rightClip(), pass.rootZone());
        flat.brights(lights.pointBrights(), Scene.flatBrightsAt(pass, f));
        boolean eau = f.kind() == Level.Surface.WATER;
        if (eau) {
            flatPaint.flat(f, null, shades);
            flat.draw(f);
        } else {
            flat.draw(f);
            flatPaint.flat(f, floors, shades);
        }
        if (flat.offTable() > 0) {
            offTable++;
            return;                                    // contour hors des tables
        }
        for (Flat.Span span : flat.spans()) {
            if (eau) {
                flatPaint.paintWater(span, flat.above());
            } else {
                flatPaint.paint(span);
            }
        }
        if (eau) {
            watersDrawn++;
        } else {
            flatsDrawn++;
        }
    }

    @Override
    public void objects(Scene.Pass pass, Level.Objects o) {
        thingsSkipped++;                               // voir l'avertissement en tete de classe
    }

    @Override
    public void sky(Scene.Pass pass, Level.Sky s) {
        if (sky == null) {
            return;
        }
        sky.draw(screen, pass.zone(), angle, centreY, view.fullScreen());
        skiesDrawn++;
    }
}
