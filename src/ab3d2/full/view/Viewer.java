package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;
import ab3d2.full.render.Appearance;
import ab3d2.full.render.Bitmap;
import ab3d2.full.render.BrightRings;
import ab3d2.full.render.Lights;
import ab3d2.full.render.LitPalette;
import ab3d2.full.render.ObjectView;
import ab3d2.full.render.Scene;
import ab3d2.full.render.ScenePainter;
import ab3d2.full.render.Screen;
import ab3d2.full.render.Sky;
import ab3d2.full.render.View;
import ab3d2.full.render.VisibleEdges;
import ab3d2.full.render.Water;
import ab3d2.full.render.ZoneOrder;
import ab3d2.full.sim.Aliens;
import ab3d2.full.sim.ObjectRuntime;
import ab3d2.full.sim.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * UNE IMAGE DU JEU, du point de vue jusqu'aux octets d'ecran.
 *
 * <p>Toutes les pieces du rendu existaient ; il manquait ce qui les tient ensemble a chaque
 * image. Le voici, et il sert aussi bien a prendre une seule image ({@link Shot}) qu'a en
 * prendre cinquante par seconde ({@link Game}) — c'est le meme travail.
 *
 * <h2>Ce qui change d'une image a l'autre</h2>
 *
 * <p>La vue, la visibilite par ouverture, l'ordre des secteurs, la rotation des choses : tout
 * cela depend du point de vue et se refait. L'eclairage, lui, ne se refait PAS ici — il se
 * refait dans la simulation, avant l'IA, car les torches et les tirs ecrivent par-dessus (voir
 * {@link ab3d2.full.sim.ZoneLight}). Le peintre ne fait que le lire.
 *
 * <h2>Les choses, par leur RANG</h2>
 *
 * <p>Les deux listes que le peintre recoit suivent le rang des entites du niveau, car c'est ce
 * rang que le tri manipule. Une chose que rien n'anime garde donc sa place, avec une apparence
 * vide — la pose l'ecarte d'elle-meme. Trois sources les remplissent : les MONSTRES vivants,
 * leur graphique ACCROCHE (qui occupe l'entite precedente), et le DECOR anime.
 */
public final class Viewer {

    private final Level lvl;
    private final Screen screen = new Screen();
    private final View view;
    private final VisibleEdges edges;
    private final ZoneOrder order = new ZoneOrder();
    private final Lights lights;
    private final Water water = new Water();
    private final Sky ciel;
    private final ScenePainter peintre;
    private final Scene scene = new Scene();
    private final ObjectView objectView;
    private final char lettre;

    private final List<ObjectView.Thing> choses = new ArrayList<>();
    private final List<Bitmap.Thing> apparences = new ArrayList<>();
    private final int[] orientations;
    private final Map<Level.Entity, ObjectRuntime.Thing> parEntite = new IdentityHashMap<>();
    private final Map<Integer, Aliens.Alien> monstres = new HashMap<>();

    /** Ce que la derniere image a compte. */
    public int animees;
    public int vivants;
    public int accroches;
    public int pensants;

    public Viewer(char lettre, Level lvl) {
        this.lettre = lettre;
        this.lvl = lvl;
        this.view = new View(Data.sinCos());
        this.view.screen(true);
        this.edges = new VisibleEdges(Data.sinCos());
        this.lights = new Lights(lvl, Data.sinCos());
        this.orientations = new int[lvl.entities().size()];

        Sky quelCiel = null;
        try {
            quelCiel = new Sky(Data.sky());
            for (Level.Zone z : lvl.zones()) {
                quelCiel.disable(z.id(), !z.drawBackdrop());
            }
        } catch (RuntimeException e) {
            quelCiel = null;
        }
        this.ciel = quelCiel;

        this.peintre = new ScenePainter(screen, view, lights, Data.walls(lettre),
                Data.floors(lettre), Data.shades(), Data.water(), water, Data.sinCos(),
                ciel, Data.iterations(), Data.database());
        this.objectView = new ObjectView(Data.sinCos());
        this.peintre.things(objectView, choses, apparences, Data::sheet);
        this.peintre.models(Data::model, Data.textureMaps(),
                new BrightRings(lvl, lights, Data.sinCos()), orientations, lvl.weaponEntity());
        this.peintre.lit(new LitPalette(Data.guff()));
        // L'ordre des secteurs ne se refait QUE si le joueur a quitte son carre de seize
        // unites : on ne le remet a zero qu'une fois, au depart, comme le jeu au chargement.
        this.order.reset();
    }

    /** Les luminosites du moment : la boucle les branche sur la simulation. */
    public Lights lights() {
        return lights;
    }

    /** Le tampon d'ecran : un octet par pixel, et chaque octet est un RANG de palette. */
    public Screen screen() {
        return screen;
    }

    /** La vue, pour sa hauteur utile. */
    public View view() {
        return view;
    }

    /** Vrai si le niveau a un ciel. */
    public boolean hasSky() {
        return ciel != null;
    }

    /** Combien de secteurs l'ordre courant compte. */
    public int zones() {
        return order.count();
    }

    /** Le compte rendu du peintre, cumule depuis le debut. */
    public String tally() {
        return peintre.tally();
    }

    /**
     * Dessine une image.
     *
     * @param world le monde, pour les choses vivantes
     * @param x     l'abscisse de l'oeil
     * @param z     son ordonnee
     * @param zone  le secteur ou il se trouve
     * @param angle son orientation
     * @param eye   sa hauteur
     */
    public void draw(World world, int x, int z, int zone, int angle, int eye) {
        Level.Zone from = lvl.zones().get(zone);
        view.update(lvl, x, z, angle, 0);
        edges.update(lvl, Data.edgePvs(lettre), zone, x, z, angle, 0);
        edges.span(view);
        order.update(lvl, from, x, z);

        screen.clear();
        peintre.beginFrame();
        peintre.eye(x, z, eye, angle, Data.sinCos());
        things(world);
        objectView.update(choses, lvl.objectPoints(), x, z, angle, 0, true);

        // tst.b (a0)+ ; beq nobackgraphics : c'est l'octet du secteur du joueur qui decide.
        if (from.drawBackdrop()) {
            peintre.backdrop(zone);
        }
        scene.draw(lvl, from, order, edges, lights, view, eye, peintre);

        // L'eau prend son image AVANT d'avancer : le battement suivant est pour l'image
        // suivante (voir Water).
        water.tick();
    }

    /** Remplit les deux listes que le peintre lit, une entree par entite du niveau. */
    private void things(World world) {
        parEntite.clear();
        for (ObjectRuntime.Thing t : world.objects.collectables()) {
            parEntite.put(t.entity, t);
        }
        for (ObjectRuntime.Thing t : world.objects.activatables()) {
            parEntite.put(t.entity, t);
        }
        for (ObjectRuntime.Thing t : world.objects.decorations()) {
            parEntite.put(t.entity, t);
        }
        // Un monstre garde le rang de l'entite dont il est ne, et son graphique ACCROCHE occupe
        // l'entite qui le PRECEDE : c'est la que son animation l'ecrit (ENT_PREV), et c'est
        // pour cela que l'accroche emprunte le point de son porteur.
        monstres.clear();
        pensants = 0;
        for (Aliens.Alien m : world.aliens.aliens()) {
            if (!m.alive || m.zone < 0 || m.index >= lvl.entities().size()) {
                continue;
            }
            if (m.ownBright != 0) {
                pensants++;
            }
            if (m.drawn) {
                monstres.put(m.index, m);
            }
        }

        Database glf = Data.database();
        choses.clear();
        apparences.clear();
        animees = 0;
        vivants = 0;
        accroches = 0;
        for (Level.Entity ent : lvl.entities()) {
            int rang = choses.size();
            // EntT_CurrentAngle_w est le MEME champ que l'angle du fichier : la simulation ne
            // fait tourner que le joueur et les monstres, pas le decor.
            orientations[rang] = ent.angle();

            Aliens.Alien monstre = monstres.get(rang);
            if (monstre != null) {
                Database.AlienDef quel = glf.alien(monstre.type);
                Appearance vu = Appearance.ofAlien(monstre.sheet, monstre.frame, monstre.flip,
                        monstre.drawWord2, quel.gfxType() & 0xFF);
                orientations[rang] = monstre.angle;
                choses.add(new ObjectView.Thing(monstre.x, monstre.z, monstre.zone,
                        monstre.zone, monstre.upperZone ? 0xFF : 0, false, ent.point()));
                apparences.add(new Bitmap.Thing(ent.point(), monstre.ownBright, monstre.height,
                        vu.halfWidth(), vu.halfDepth(), vu.sheet(), vu.flags(), vu.frame(),
                        0, 0));
                vivants++;
                continue;
            }

            // L'accroche du monstre qui SUIT : liberee a chaque image, remplie seulement si
            // l'image en cours la reclame. Liberee, son secteur vaut -1 et le tri ne la voit pas.
            Aliens.Alien porteur =
                    ent.kind() == Level.Kind.AUX ? monstres.get(rang + 1) : null;
            if (porteur != null) {
                Database.ObjectDef quel = porteur.auxFrame < 0 ? null
                        : glf.object(glf.alien(porteur.type).auxiliary());
                if (quel == null || porteur.auxFrame >= quel.idleAnim().size()) {
                    choses.add(new ObjectView.Thing(ent.x(), ent.z(), -1, -1,
                            ent.upperZone(), true, ent.point()));
                    apparences.add(new Bitmap.Thing(ent.point(), 0, 0, 0, 0, 0, 0, 0, 0, 0));
                    continue;
                }
                Appearance vu = Appearance.of(quel.idleAnim().get(porteur.auxFrame),
                        quel.gfxType());
                choses.add(new ObjectView.Thing(porteur.x, porteur.z, porteur.zone,
                        porteur.zone, porteur.upperZone ? 0xFF : 0, true, ent.point()));
                apparences.add(new Bitmap.Thing(ent.point(), 0, porteur.height,
                        vu.halfWidth(), vu.halfDepth(), vu.sheet(), vu.flags(), vu.frame(),
                        porteur.auxX, porteur.auxY));
                accroches++;
                continue;
            }

            ObjectRuntime.Thing t = parEntite.get(ent);
            int ou = t != null ? t.zone : ent.zone();
            choses.add(new ObjectView.Thing(ent.x(), ent.z(), ou, ou,
                    ent.upperZone(), ent.kind() == Level.Kind.AUX, ent.point()));
            Appearance vu = null;
            int h = 0;
            if (t != null) {
                Database.ObjectDef def = glf.object(ent.def());
                List<Database.AnimStep> scenario =
                        t.activated ? def.activeAnim() : def.idleAnim();
                if (!scenario.isEmpty()) {
                    Database.AnimStep pas =
                            scenario.get(Math.floorMod(t.animStep, scenario.size()));
                    vu = Appearance.of(pas, def.gfxType());
                    h = ObjectRuntime.objectHeight(world.lvl, t.zone,
                            ent.upperZone() != 0, t.animDelta, def);
                    animees++;
                }
            }
            apparences.add(vu == null
                    ? new Bitmap.Thing(ent.point(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    : new Bitmap.Thing(ent.point(), 0, h, vu.halfWidth(), vu.halfDepth(),
                            vu.sheet(), vu.flags(), vu.frame(), 0, 0));
        }
    }
}
