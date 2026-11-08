package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.level.Level;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Palette;
import ab3d2.full.render.Appearance;
import ab3d2.full.render.Bitmap;
import ab3d2.full.render.ObjectView;
import ab3d2.full.render.EdgePvs;
import ab3d2.full.render.Lights;
import ab3d2.full.render.Scene;
import ab3d2.full.render.ScenePainter;
import ab3d2.full.render.Screen;
import ab3d2.full.render.Sky;
import ab3d2.full.render.View;
import ab3d2.full.render.VisibleEdges;
import ab3d2.full.render.Water;
import ab3d2.full.render.ZoneOrder;
import ab3d2.full.sim.PlayerSim;
import ab3d2.full.sim.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Une IMAGE, prise depuis un point du niveau.
 *
 * <p>C'est la premiere fois que ce portage produit une vue subjective. Jusqu'ici chaque piece
 * du rendu etait eprouvee seule, contre un releve du portage fidele ; rien ne les faisait
 * tourner ensemble, faute de la boucle qui ordonne. Elle existe maintenant, et voici ce qu'elle
 * donne.
 *
 * <p>L'ecran du jeu est un tableau d'octets : un pixel n'est pas une couleur mais un rang. Il
 * ne devient une image qu'ici, par {@link Palette}.
 *
 * <pre>
 * gradle -p full shot                          # niveau a, premier secteur
 * gradle -p full shot -Plevel=c -Pzone=40 -Pangle=2048 -Pout=vue.png
 * </pre>
 */
public final class Shot {

    private Shot() {
    }

    public static void main(String[] args) throws IOException {
        char lettre = System.getProperty("ab3d2.level", "a").toLowerCase().charAt(0);
        Level lvl = Data.level(lettre);
        int secteur = Integer.getInteger("ab3d2.zone", -1);
        int echelle = Integer.getInteger("ab3d2.scale", 3);
        int tours = Integer.getInteger("ab3d2.frames", 0);
        Path sortie = Path.of(System.getProperty("ab3d2.out", "shot.png"));

        // Le point de vue vient de la SIMULATION, et non d'une valeur inventee : c'est elle
        // qui sait ou le joueur commence et a quelle hauteur sont ses yeux — le sol du
        // secteur moins sa taille. Pose a zero, l'oeil serait pile au ras du sol et l'image
        // s'arreterait net a l'horizon.
        World world = World.of(lettre);
        // Le point de vue se pose AVANT de faire tourner le monde. C'est la position du JOUEUR
        // qui reveille les monstres — leur secteur doit figurer dans sa liste de visibilite —
        // et un point de vue pose apres coup les laisserait tous endormis, donc invisibles :
        // leurs octets de dessin sont a zero tant qu'ils n'ont pas pense.
        if (secteur >= 0) {
            Level.Zone depart = lvl.zones().get(secteur);
            int ou = depart.controlPoint();
            world.player.xOff = lvl.controlPoints().get(ou).x() << 16;
            world.player.zOff = lvl.controlPoints().get(ou).z() << 16;
            world.player.zone = secteur;
            world.player.yOff = depart.floor() - PlayerSim.PLR_STAND_HEIGHT;
        }
        for (int t = 0; t < tours; t++) {
            world.frame(new World.Input());
        }
        int x = world.player.xOff >> 16;
        int z = world.player.zOff >> 16;
        int zone = world.player.zone;
        int angle = Integer.getInteger("ab3d2.angle", world.player.angPos);
        int hauteur = Integer.getInteger("ab3d2.eye", world.player.yOff);
        if (secteur >= 0) {
            zone = secteur;
            Level.Zone zz = lvl.zones().get(zone);
            int cp = zz.controlPoint();
            x = lvl.controlPoints().get(cp).x();
            z = lvl.controlPoints().get(cp).z();
            hauteur = Integer.getInteger("ab3d2.eye", zz.floor() - PlayerSim.PLR_STAND_HEIGHT);
        }
        Level.Zone from = lvl.zones().get(zone);

        View view = new View(Data.sinCos());
        view.screen(true);
        view.update(lvl, x, z, angle, 0);

        VisibleEdges edges = new VisibleEdges(Data.sinCos());
        edges.update(lvl, Data.edgePvs(lettre), zone, x, z, angle, 0);
        edges.span(view);

        ZoneOrder order = new ZoneOrder();
        order.reset();
        order.update(lvl, from, x, z);

        Lights lights = new Lights(lvl, Data.sinCos());
        Screen screen = new Screen();
        screen.clear();

        Sky ciel = null;
        try {
            ciel = new Sky(Data.sky());
            for (Level.Zone zz : lvl.zones()) {
                ciel.disable(zz.id(), !zz.drawBackdrop());
            }
        } catch (RuntimeException e) {
            System.out.println("pas de ciel : " + e.getMessage());
        }

        ScenePainter peintre = new ScenePainter(screen, view, lights, Data.walls(lettre),
                Data.floors(lettre), Data.shades(), Data.water(), new Water(), Data.sinCos(),
                ciel, Data.iterations(), Data.database());
        peintre.eye(x, z, hauteur, angle, Data.sinCos());

        // Les CHOSES. Les deux listes suivent le rang des entites du niveau, car c'est ce rang
        // que le tri manipule : une chose que la simulation n'anime pas doit garder sa place,
        // et son apparence reste alors vide — la pose l'ecarte d'elle-meme.
        java.util.Map<Level.Entity, ab3d2.full.sim.ObjectRuntime.Thing> parEntite =
                new java.util.IdentityHashMap<>();
        for (ab3d2.full.sim.ObjectRuntime.Thing t : world.objects.collectables()) {
            parEntite.put(t.entity, t);
        }
        for (ab3d2.full.sim.ObjectRuntime.Thing t : world.objects.activatables()) {
            parEntite.put(t.entity, t);
        }
        for (ab3d2.full.sim.ObjectRuntime.Thing t : world.objects.decorations()) {
            parEntite.put(t.entity, t);
        }
        // Les MONSTRES viennent de la SIMULATION et non du fichier : leurs champs de dessin y
        // sont tous a zero. Chacun garde le rang de l'entite dont il est ne, et son graphique
        // AUXILIAIRE occupe l'entite qui le PRECEDE — c'est la que l'animation l'ecrit
        // (ENT_PREV), et c'est pour cela que l'auxiliaire emprunte le point de son porteur.
        java.util.Map<Integer, ab3d2.full.sim.Aliens.Alien> monstres = new java.util.HashMap<>();
        int eveilles = 0;
        for (ab3d2.full.sim.Aliens.Alien m : world.aliens.aliens()) {
            if (!m.alive || m.zone < 0 || m.index >= lvl.entities().size()) {
                continue;
            }
            if (m.ownBright != 0) {
                eveilles++;
            }
            if (m.drawn) {
                monstres.put(m.index, m);
            }
        }
        System.out.printf("  monstres : %d en place, %d qui pensent, %d avec un dessin%n",
                world.aliens.aliens().size(), eveilles, monstres.size());

        List<ObjectView.Thing> choses = new ArrayList<>(lvl.entities().size());
        List<Bitmap.Thing> apparences = new ArrayList<>(lvl.entities().size());
        int[] orientations = new int[lvl.entities().size()];
        int animees = 0;
        int vivants = 0;
        int accroches = 0;
        for (Level.Entity ent : lvl.entities()) {
            int rang = choses.size();
            // EntT_CurrentAngle_w est le MEME champ que l'angle du fichier : la simulation ne
            // fait tourner que le joueur et les aliens, pas le decor.
            orientations[rang] = ent.angle();

            ab3d2.full.sim.Aliens.Alien monstre = monstres.get(rang);
            if (monstre != null) {
                Database.AlienDef quel = Data.database().alien(monstre.type);
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

            // L'auxiliaire du monstre qui SUIT. Il est libere a chaque image et rempli
            // seulement si l'image en cours le reclame ; libere, son secteur vaut -1 et le tri
            // ne le voit pas.
            ab3d2.full.sim.Aliens.Alien porteur =
                    ent.kind() == Level.Kind.AUX ? monstres.get(rang + 1) : null;
            if (porteur != null) {
                Database.ObjectDef quel = porteur.auxFrame < 0 ? null
                        : Data.database().object(Data.database().alien(porteur.type).auxiliary());
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

            ab3d2.full.sim.ObjectRuntime.Thing t = parEntite.get(ent);
            int ou = t != null ? t.zone : ent.zone();
            choses.add(new ObjectView.Thing(ent.x(), ent.z(), ou, ou,
                    ent.upperZone(), ent.kind() == Level.Kind.AUX, ent.point()));
            Appearance vu = null;
            int h = 0;
            if (t != null) {
                Database.ObjectDef def = Data.database().object(ent.def());
                List<Database.AnimStep> scenario =
                        t.activated ? def.activeAnim() : def.idleAnim();
                if (!scenario.isEmpty()) {
                    Database.AnimStep pas =
                            scenario.get(Math.floorMod(t.animStep, scenario.size()));
                    vu = Appearance.of(pas, def.gfxType());
                    h = ab3d2.full.sim.ObjectRuntime.objectHeight(world.lvl, t.zone,
                            ent.upperZone() != 0, t.animDelta, def);
                    animees++;
                }
            }
            apparences.add(vu == null
                    ? new Bitmap.Thing(ent.point(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    : new Bitmap.Thing(ent.point(), 0, h, vu.halfWidth(), vu.halfDepth(),
                            vu.sheet(), vu.flags(), vu.frame(), 0, 0));
        }
        ObjectView objectView = new ObjectView(Data.sinCos());
        objectView.update(choses, lvl.objectPoints(), x, z, angle, 0, true);
        peintre.things(objectView, choses, apparences, Data::sheet);
        peintre.models(Data::model, Data.textureMaps(),
                new ab3d2.full.render.BrightRings(lvl, lights, Data.sinCos()),
                orientations, lvl.weaponEntity());
        peintre.lit(new ab3d2.full.render.LitPalette(Data.guff()));

        // tst.b (a0)+ ; beq nobackgraphics : c'est l'octet du secteur du joueur qui decide.
        if (from.drawBackdrop()) {
            peintre.backdrop(zone);
        }
        new Scene().draw(lvl, from, order, edges, lights, view, hauteur, peintre);

        System.out.printf("niveau %c, secteur %d (%d, %d), angle %d, oeil %d, %d choses"
                + " animees, %d monstres vivants (%d graphiques accroches)%n",
                lettre, zone, x, z, angle, hauteur, animees, vivants, accroches);
        System.out.println("  " + peintre.tally());

        Palette palette = Data.palette();
        int largeur = View.FULL_WIDTH;
        int haut = view.height();
        BufferedImage image = new BufferedImage(largeur * echelle, haut * echelle,
                BufferedImage.TYPE_INT_RGB);
        byte[] pixels = screen.pixels();
        for (int y = 0; y < haut; y++) {
            for (int k = 0; k < largeur; k++) {
                int couleur = palette.at(pixels[screen.line(y) + k] & 0xFF);
                for (int dy = 0; dy < echelle; dy++) {
                    for (int dx = 0; dx < echelle; dx++) {
                        image.setRGB(k * echelle + dx, y * echelle + dy, couleur);
                    }
                }
            }
        }
        Path parent = sortie.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", sortie.toFile());
        System.out.println("  -> " + sortie.toAbsolutePath());
    }
}
