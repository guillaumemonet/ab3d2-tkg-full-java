package ab3d2.full.view;

import ab3d2.full.Data;
import ab3d2.full.level.Level;
import ab3d2.full.level.Palette;
import ab3d2.full.render.Screen;
import ab3d2.full.render.View;
import ab3d2.full.sim.PlayerSim;
import ab3d2.full.sim.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Une IMAGE, prise depuis un point du niveau.
 *
 * <p>Tout le travail est dans {@link Viewer} ; il ne reste ici que le choix du point de vue et
 * la conversion en PNG. L'ecran du jeu est un tableau d'octets : un pixel n'est pas une couleur
 * mais un RANG, et il ne devient une image qu'ici, par {@link Palette}.
 *
 * <p>Le point de vue se pose <b>avant</b> de faire tourner le monde. C'est la position du
 * joueur qui reveille les monstres — leur secteur doit figurer dans sa liste de visibilite — et
 * un point de vue pose apres coup les laisserait tous endormis, donc invisibles : leurs octets
 * de dessin sont a zero tant qu'ils n'ont pas pense.
 *
 * <pre>
 * gradle -p full shot                                    # niveau a, la ou le joueur commence
 * gradle -p full shot -Plevel=a -Pzone=55 -Pframes=10 -Pangle=7168
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
        int secteurDemande = Integer.getInteger("ab3d2.zone", -1);
        if (secteurDemande >= 0) {
            // Une image au moins : c'est la simulation qui pose l'oeil a la bonne hauteur, et
            // le sol d'un secteur a deux etages ne se devine pas depuis le fichier.
            tours = Math.max(1, tours);
        }
        Path sortie = Path.of(System.getProperty("ab3d2.out", "shot.png"));

        // Le point de vue vient de la SIMULATION, et non d'une valeur inventee : c'est elle qui
        // sait ou le joueur commence et a quelle hauteur sont ses yeux — le sol du secteur
        // moins sa taille. Pose a zero, l'oeil serait au ras du sol et l'image s'arreterait net.
        World world = World.of(lettre);
        Viewer viewer = new Viewer(lettre, lvl);
        // Les deux reglages d'eclairage du joueur : 'eclairage simple' eteint le degrade
        // des murs, et le degrade des sols est allume au demarrage.
        viewer.simpleWalls(Boolean.getBoolean("ab3d2.simple"));
        viewer.gouraudFlats(!Boolean.getBoolean("ab3d2.flatshade"));
        viewer.showWeapon = !Boolean.getBoolean("ab3d2.nogun");
        viewer.playMusic = !Boolean.getBoolean("ab3d2.nomusic");
        world.light = viewer.lights();
        world.aliens.dynLight = viewer.lights();
        world.shots.dynLight = viewer.lights();
        // Les lumieres dynamiques se posent PENDANT l'image de simulation, avant le dessin :
        // leurs hauteurs de secteur doivent donc venir de la simulation, pas du fichier.
        viewer.lights().heights(Viewer.hauteurs(world));
        if (secteur >= 0) {
            // Le point de controle d'un secteur n'est PAS garanti dedans : c'est un jalon de
            // navigation, et plusieurs tombent de l'autre cote d'une ouverture (le 100 du
            // niveau f est dans le 99). Pose dans le mauvais secteur, le rendu ecarte tout et
            // l'image sort NOIRE. On suit donc les ouvertures jusqu'a celui qui le contient.
            int vrai = contenant(world, secteur,
                    lvl.controlPoints().get(lvl.zones().get(secteur).controlPoint()).x(),
                    lvl.controlPoints().get(lvl.zones().get(secteur).controlPoint()).z());
            if (vrai != secteur) {
                System.out.printf("  le point de controle du secteur %d est dans le secteur"
                        + " %d : c'est de la qu'on regarde%n", secteur, vrai);
                secteur = vrai;
            }
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
            hauteur = Integer.getInteger("ab3d2.eye", world.player.yOff);
        }

        // Aucune image jouee : l'eclairage n'a jamais ete calcule, il faut le faire une fois.
        if (tours == 0) {
            viewer.lights().frame(zone);
        }
        viewer.draw(world, x, z, zone, angle, hauteur);
        viewer.sound(world, x, z, angle);
        // L'habillage : le reticule, les messages, puis le bandeau du tableau de bord.
        viewer.overlay(world, 0);

        System.out.printf("niveau %c, secteur %d (%d, %d), angle %d, oeil %d%n",
                lettre, zone, x, z, angle, hauteur);
        System.out.printf("  %d choses animees, %d monstres vivants (%d qui pensent,"
                + " %d graphiques accroches), arme %s%n",
                viewer.animees, viewer.vivants, viewer.pensants, viewer.accroches,
                viewer.armeVue ? "en main (objet " + world.weapon.object + ", modele "
                        + (world.weapon.shown == null ? -1 : world.weapon.shown.gfx())
                        + ")" : "absente");
        System.out.println("  " + viewer.tally());

        // -Pshimmer=N : regarder l'image a travers la table de dematerialisation, comme la
        // presentation le fait quand le joueur se teleporte ou atteint la sortie.
        int pas = Integer.getInteger("ab3d2.shimmer", -1);
        if (pas >= 0) {
            byte[] delite = new ab3d2.full.render.Shimmer(ab3d2.full.Data.shimmer())
                    .apply(viewer.screen().pixels(), pas);
            System.arraycopy(delite, 0, viewer.screen().pixels(), 0, delite.length);
            System.out.println("  dematerialisation au pas " + pas);
        }

        ecrire(viewer.screen(), Screen.HEIGHT, echelle, sortie);
        System.out.println("  -> " + sortie.toAbsolutePath());
    }

    /**
     * Quel secteur contient vraiment ce point ?
     *
     * <p>Meme test que {@code MoveObject} : un point est du bon cote d'une arete quand
     * {@code (x-ex)*edz - (z-ez)*edx >= 0}. S'il est du mauvais cote d'une arete qui JOINT un
     * voisin, c'est que le point est chez le voisin ; on l'y suit.
     */
    private static int contenant(World world, int zone, int x, int z) {
        for (int saut = 0; saut < 8; saut++) {
            int passe = -1;
            for (int ei : world.lvl.zones[zone].edgeList) {
                if (ei < 0) {
                    break;                             // fin du contour
                }
                int d = ab3d2.full.M68k.muls(x - world.lvl.edgeX[ei], world.lvl.edgeDz[ei])
                        - ab3d2.full.M68k.muls(z - world.lvl.edgeZ[ei], world.lvl.edgeDx[ei]);
                if (d < 0 && world.lvl.edgeJoin[ei] >= 0) {
                    passe = world.lvl.edgeJoin[ei];
                    break;
                }
            }
            if (passe < 0) {
                return zone;
            }
            zone = passe;
        }
        return zone;
    }

    /**
     * Du tampon d'octets a un PNG, chaque octet passant par la palette du jeu.
     *
     * <p>L'image fait les DEUX CENT CINQUANTE-SIX lignes de l'ecran, et non les deux cent
     * quarante de la vue : les dernieres portent le tableau de bord.
     */
    static void ecrire(Screen screen, int haut, int echelle, Path sortie) throws IOException {
        Palette palette = Data.palette();
        int largeur = View.FULL_WIDTH;
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
    }
}
