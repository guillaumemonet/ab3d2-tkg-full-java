package ab3d2.full.render;

import ab3d2.full.SinCos;
import ab3d2.full.defs.Database;
import ab3d2.full.level.FloorTexture;
import ab3d2.full.level.Level;
import ab3d2.full.level.ShadeTable;
import ab3d2.full.level.SpriteSheet;
import ab3d2.full.level.TextureMaps;
import ab3d2.full.level.VectorModel;
import ab3d2.full.level.WaterFrames;
import ab3d2.full.level.WallTexture;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Le peintre : il execute ce que {@link Scene} decide.
 *
 * <p>{@link Scene} marche les secteurs, resserre les fenetres et ordonne les commandes, mais
 * ne touche pas un pixel. Celui-ci ne decide rien et les dessine tous. C'est la premiere fois
 * que le rendu de ce portage tourne en entier plutot que morceau par morceau.
 *
 * <h2>Les trois facons d'etre une chose</h2>
 *
 * <p>{@link Appearance} dit laquelle, et le peintre en sert deux. Un SPRITE passe par
 * {@link Bitmap} puis {@link BitmapPaint}, dans sa boucle simple ou dans son melange additif.
 * Un MIROITEMENT passe par {@link Glare} puis {@link GlarePaint} — routine a part, table a
 * part. Un MODELE en trois dimensions n'a pas de voie ici : il est compte et passe.
 *
 * <h2>Ce qu'il ne dessine pas encore</h2>
 *
 * <p>Les modeles vectoriels, donc — et c'est la moitie de ce que le jeu montre. L'image
 * ECLAIREE non plus, dont la palette se construit a chaque image d'apres l'eclairage
 * directionnel.
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
    private final Sprites sprites = new Sprites();
    private final Bitmap bitmap;
    private final BitmapPaint bitmapPaint;
    private final Glare glare;
    private final GlarePaint glarePaint;

    private final ModelPoints modelPoints;
    private final ModelParts modelParts = new ModelParts();
    private final ModelFace modelFace = new ModelFace();
    private final ModelEdges modelEdges = new ModelEdges();
    private final ModelPaint modelPaint = new ModelPaint();
    private final Map<VectorModel, Map<Integer, List<VectorModel.Face>>> parModele =
            new IdentityHashMap<>();

    private LitPalette litPalette;
    private IntFunction<VectorModel> modeles;
    private TextureMaps maps;
    private BrightRings rings;
    private int[] objectAngles;
    private int weapon = -1;

    private ObjectView objectView;
    private List<ObjectView.Thing> choses;
    private List<Bitmap.Thing> apparences;
    private IntFunction<SpriteSheet> feuilles;

    private int playerY;
    private int centreY;
    private int angle;

    private int wallsDrawn;
    private int flatsDrawn;
    private int watersDrawn;
    private int skiesDrawn;
    private int thingsDrawn;
    private int glaresDrawn;
    private int thingsSkipped;
    private int litDrawn;
    private int modelsDrawn;
    private int modelFacesDrawn;
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
     * @param iterations la table des pas du placage des murs
     * @param glf    la base de liaison, pour les tailles d'images
     */
    public ScenePainter(Screen screen, View view, Lights lights, List<WallTexture> walls,
            FloorTexture floors, ShadeTable shades, WaterFrames ripples, Water water,
            SinCos sinCos, Sky sky, int[] iterations, Database glf) {
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
        ScaleTable echelles = new ScaleTable();
        this.bitmap = new Bitmap(glf, echelles);
        this.bitmapPaint = new BitmapPaint(screen);
        this.glare = new Glare(glf, echelles);
        this.glarePaint = new GlarePaint(screen);
        this.modelPoints = new ModelPoints(sinCos);
    }

    /**
     * Donne au peintre de quoi dessiner les CHOSES.
     *
     * <p>Les deux listes sont indexees par le rang de l'entite dans le niveau, et ont donc la
     * meme longueur que lui : c'est ce rang que le tri manipule, et une chose absente doit
     * garder sa place. Une chose dont l'apparence porte une demi-largeur NULLE n'est pas
     * dessinee — c'est la sortie que prend le jeu lui-meme, et c'est ce qui arrive a tout ce
     * que la simulation n'anime pas encore.
     *
     * @param objectView les points des choses, deja tournes
     * @param choses     ou chaque chose se trouve
     * @param apparences ce que chaque chose montre
     * @param feuilles   ou trouver une feuille de sprites par son rang
     */
    public void things(ObjectView objectView, List<ObjectView.Thing> choses,
            List<Bitmap.Thing> apparences, IntFunction<SpriteSheet> feuilles) {
        this.objectView = objectView;
        this.choses = choses;
        this.apparences = apparences;
        this.feuilles = feuilles;
    }

    /**
     * Donne au peintre de quoi dessiner les MODELES EN RELIEF.
     *
     * <p>Tant que ceci n'est pas fourni, une chose qui porte la marque du relief est
     * simplement comptee et laissee de cote.
     *
     * @param modeles ou trouver un modele par son rang
     * @param maps    le bloc de textures des modeles, commun a tout le jeu
     * @param rings   les anneaux d'eclairage, deja attaches au niveau et aux lumieres
     * @param angles  l'orientation de chaque chose, indexee par le rang de l'entite
     * @param weapon  le rang de l'entite qui porte l'arme du joueur, la seule qu'on dessine
     *                a profondeur nulle
     */
    public void models(IntFunction<VectorModel> modeles, TextureMaps maps, BrightRings rings,
            int[] angles, int weapon) {
        this.modeles = modeles;
        this.maps = maps;
        this.rings = rings;
        this.objectAngles = angles;
        this.weapon = weapon;
    }

    /**
     * Donne au peintre de quoi dessiner les images ECLAIREES.
     *
     * <p>Les anneaux d'eclairage viennent de {@link #models}, car ce sont les memes. Tant que
     * les deux ne sont pas fournis, une chose eclairee est comptee et laissee de cote.
     *
     * @param pals le constructeur de palette, qui refait la sienne a chaque sprite
     */
    public void lit(LitPalette pals) {
        this.litPalette = pals;
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
                + " d'eau, " + skiesDrawn + " ciels, " + thingsDrawn + " choses, "
                + glaresDrawn + " miroitements, " + modelsDrawn + " modeles ("
                + modelFacesDrawn + " faces), " + litDrawn + " eclairees ("
                + thingsSkipped + " passees), " + offTable
                + " hors table";
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
        if (choses == null) {
            thingsSkipped++;
            return;
        }
        // draw_TopY_3D_l / draw_BottomY_3D_l : le moment de la commande decide de quelle
        // tranche du secteur les choses ont le droit d'occuper.
        bitmap.view(view.fullScreen(), view.centreX(), centreY, playerY,
                pass.objectsTop(o.moment()), pass.objectsBottom(o.moment()),
                0, view.height(), pass.leftClip(), pass.rightClip());
        glare.view(view.fullScreen(), view.centreX(), centreY, playerY,
                pass.objectsTop(o.moment()), pass.objectsBottom(o.moment()),
                0, view.height(), pass.leftClip(), pass.rightClip());
        sprites.sort(choses, objectView, pass.zone(), pass.upper() ? 1 : 0,
                pass.leftClip(), pass.rightClip());
        for (int i = 0; i < sprites.count(); i++) {
            int rang = sprites.at(i);
            Bitmap.Thing quoi = apparences.get(rang);
            if (quoi.halfWidth() == Appearance.VECTOR_MARK) {
                // cmp.b #$ff,6(a0) : la marque du relief.
                model(pass, rang, quoi);
                continue;
            }
            if (quoi.halfWidth() == 0) {
                thingsSkipped++;                       // rien a montrer
                continue;
            }
            // tst.l ObjT_YPos_l(a0) ; blt draw_bitmap_glare : le signe du rang de feuille.
            if (quoi.glare()) {
                glare.clearOffTable();
                Glare.Placed lueur = glare.place(quoi, objectView);
                if (lueur == null) {
                    continue;
                }
                SpriteSheet ou = feuilles.apply(Glare.sheetOf(quoi.sheet()));
                if (ou == null) {
                    thingsSkipped++;
                    continue;
                }
                glarePaint.paint(lueur, ou, shades);
                glaresDrawn++;
                continue;
            }
            bitmap.clearOffTable();
            Bitmap.Placed pose = bitmap.place(quoi, objectView);
            if (pose == null) {
                continue;
            }
            SpriteSheet feuille = feuilles.apply(quoi.sheet() & 0x7FFF);
            if (feuille == null) {
                thingsSkipped++;
                continue;
            }
            if (pose.light()) {
                // tst.b draw_LightIt_b ; bne draw_bitmap_lighted
                if (litPalette == null || rings == null) {
                    thingsSkipped++;                   // l'eclairage n'a pas ete fourni
                    continue;
                }
                ObjectView.Thing ou = choses.get(rang);
                // draw_ResetAngleBrights, et non draw_CalcBrightRings : voir BrightRings.
                rings.clearOffTable();
                rings.reset(pass.zone(), ou.upper() != 0, ou.x(), ou.z());
                litPalette.build(rings.rings(), angle, pose.flip(), pose.bright(),
                        pose.lightPal(), feuille);
                bitmapPaint.paintLit(pose, feuille, litPalette);
                litDrawn++;
                continue;
            }
            if (pose.additive()) {
                bitmapPaint.paintAdditive(pose, feuille);
            } else {
                bitmapPaint.paint(pose, feuille);
            }
            thingsDrawn++;
        }
    }

    /**
     * Dessine un modele en relief (objdrawhires.s:1684, {@code draw_PolygonModel}).
     *
     * <p>Toute la chaine en une page : les anneaux d'eclairage du secteur, la grille seize par
     * seize qui en decoule, la rotation et la projection des points, le tri des parties en
     * profondeur, puis face par face le garde-fou, le dos tourne, la teinte, les bords et les
     * pixels.
     *
     * <p>Une chose dont la profondeur est NULLE est derriere l'oeil et ne se dessine pas —
     * sauf une seule, l'arme du joueur, que le jeu reconnait a sa place dans la table et
     * dessine a la profondeur un, au milieu de l'ecran plutot qu'au milieu de la vue.
     */
    private void model(Scene.Pass pass, int rang, Bitmap.Thing quoi) {
        if (modeles == null) {
            thingsSkipped++;                           // le relief n'a pas ete fourni
            return;
        }
        ObjectView.Thing ou = choses.get(rang);
        int depth = objectView.rotZ(ou.point());
        int milieu = centreY;
        if (depth == 0 && rang == weapon) {            // cmp.l #DRAW_VECTOR_NEAR_PLANE,a3
            depth = 1;                                 // move.w #1,d1
            milieu = Screen.FULL_HEIGHT / 2;           // move.w #FS_HEIGHT/2,...
        }
        if (depth <= 0) {                              // blt polybehind
            thingsSkipped++;
            return;
        }
        VectorModel modele = modeles.apply(quoi.sheet());
        if (modele == null || quoi.frame() >= modele.frames().size()) {
            thingsSkipped++;
            return;
        }
        rings.clearOffTable();
        rings.calc(pass.zone(), ou.upper() != 0, ou.x(), ou.z());  // draw_CalcBrightRings
        modelPoints.buildGrid(rings);                  // MYacross
        if (!modelPoints.project(modele, quoi.frame(), objectAngles[rang], angle,
                objectView.worldX(ou.point()), depth, quoi.height(), playerY,
                view.centreX(), milieu)) {
            thingsSkipped++;                           // no_more_parts : un point trop loin
            return;
        }
        modelParts.clearOverflow();
        if (!modelParts.sort(modele, quoi.frame(), modelPoints)) {
            thingsSkipped++;
            return;
        }
        Map<Integer, List<VectorModel.Face>> parDeplacement = parModele.computeIfAbsent(modele,
                m -> {
                    Map<Integer, List<VectorModel.Face>> t = new HashMap<>();
                    for (VectorModel.Part p : m.parts()) {
                        t.put(p.at(), p.faces());
                    }
                    return t;
                });
        int[] faceAngles = modele.frames().get(quoi.frame()).angles();
        int topClip = 0;
        int botClip = view.height();                   // draw_TopClip_w / draw_BottomClip_w
        for (int pi = 0; pi < modelParts.count(); pi++) {
            List<VectorModel.Face> faces = parDeplacement.get(modelParts.where(pi));
            if (faces == null) {
                continue;                              // une partie qui sort du modele
            }
            for (VectorModel.Face f : faces) {
                if (modelFace.sort(f, modelPoints) != ModelFace.SHOWN) {
                    continue;
                }
                modelEdges.start();
                modelEdges.lines(f, modelPoints, pass.leftClip(), pass.rightClip(),
                        f.gouraud());
                if (!modelEdges.clip(pass.leftClip(), pass.rightClip())) {
                    continue;
                }
                modelPaint.fill(modelEdges, ModelFace.kind(f), modelFace.texture(f),
                        modelFace.palette(f, faceAngles, objectAngles[rang], modelPoints,
                                rings),
                        maps, shades, screen, topClip, botClip);
                modelFacesDrawn++;
            }
        }
        modelsDrawn++;
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
