package ab3d2.full.view;

import ab3d2.full.SinCos;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;
import ab3d2.full.sim.Aliens;
import ab3d2.full.sim.ObjectRuntime;
import ab3d2.full.sim.Shots;
import ab3d2.full.sim.World;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * De quoi JOUER la simulation, vue de dessus.
 *
 * <p>Ce n'est pas le rendu du jeu — celui-la reste a porter — mais c'est ce qui manquait pour
 * essayer la simulation autrement qu'en lisant des chiffres : on conduit le joueur au clavier,
 * les portes s'ouvrent, les monstres viennent, les balles volent, et tout se voit.
 *
 * <p>La boucle bat a <b>cinquante fois par seconde</b> parce que tous les compteurs du jeu sont
 * en images de balayage : une porte met tant d'images a s'ouvrir, un monstre tant d'images a
 * reagir. Accelerer la boucle accelererait le jeu.
 *
 * <p>Le plan est celui du jeu, sans conversion : X vers la droite, Z vers le haut. Les hauteurs
 * ne se voient pas ici, de sorte qu'un secteur a deux etages se montre a plat — c'est la limite
 * de cette vue, pas de la simulation.
 */
public final class Play {

    private Play() {
    }

    public static void main(String[] args) {
        char letter = System.getProperty("ab3d2.level", args.length > 0 ? args[0] : "a")
                .toLowerCase().charAt(0);
        World world = World.of(letter);

        JFrame frame = new JFrame("Alien Breed 3D II — niveau " + Character.toUpperCase(letter));
        Map map = new Map(world);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(map);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        map.requestFocusInWindow();

        // Un top toutes les vingt millisecondes : le rythme du balayage PAL.
        new Timer(20, e -> map.step()).start();
    }

    /** La vue, et le clavier qui va avec. */
    private static final class Map extends JPanel {

        private static final int TOUR = 8192;

        private final World world;
        private final boolean[] keys = new boolean[256];
        private final Deque<String> journal = new ArrayDeque<>();
        private final World.Input input = new World.Input();

        private double echelle = 0.22;
        private boolean suitLeJoueur = true;
        private int images;
        private long dernierTop = System.nanoTime();
        private double parSeconde;

        Map(World world) {
            this.world = world;
            setPreferredSize(new Dimension(1100, 760));
            setBackground(new Color(12, 14, 18));
            setFocusable(true);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    touche(e.getKeyCode(), true);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    touche(e.getKeyCode(), false);
                }
            });
            dire("niveau " + Character.toUpperCase(world.level.letter())
                    + " : " + world.level.zones().size() + " secteurs, "
                    + world.aliens.aliens().size() + " emplacements de monstres, "
                    + world.level.doors().size() + " portes");
            dire("fleches ou ZQSD pour aller, A/E pour tourner, ESPACE pour tirer,"
                    + " F pour actionner");
        }

        private void touche(int code, boolean enfoncee) {
            if (code >= 0 && code < keys.length) {
                keys[code] = enfoncee;
            }
            if (!enfoncee) {
                return;
            }
            if (code == KeyEvent.VK_ADD || code == KeyEvent.VK_PLUS || code == KeyEvent.VK_EQUALS) {
                echelle = Math.min(1.6, echelle * 1.25);
            } else if (code == KeyEvent.VK_SUBTRACT || code == KeyEvent.VK_MINUS) {
                echelle = Math.max(0.03, echelle / 1.25);
            } else if (code == KeyEvent.VK_TAB) {
                suitLeJoueur = !suitLeJoueur;
            } else if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
                world.gun = code - KeyEvent.VK_1;
                Database.Gun g = world.glf.gun(world.gun);
                dire("arme " + (world.gun + 1) + " : " + (g == null ? "?" : g.name()));
            }
        }

        private boolean on(int... codes) {
            for (int c : codes) {
                if (c >= 0 && c < keys.length && keys[c]) {
                    return true;
                }
            }
            return false;
        }

        /** Une image de jeu, puis un rafraichissement. */
        void step() {
            input.forward = on(KeyEvent.VK_UP, KeyEvent.VK_Z, KeyEvent.VK_W);
            input.backward = on(KeyEvent.VK_DOWN, KeyEvent.VK_S);
            input.stepLeft = on(KeyEvent.VK_Q, KeyEvent.VK_A) && on(KeyEvent.VK_SHIFT);
            input.stepRight = on(KeyEvent.VK_D, KeyEvent.VK_E) && on(KeyEvent.VK_SHIFT);
            input.run = on(KeyEvent.VK_CONTROL);
            input.jump = on(KeyEvent.VK_SPACE) && on(KeyEvent.VK_ALT);
            input.duck = on(KeyEvent.VK_C);
            input.fire = on(KeyEvent.VK_SPACE) && !on(KeyEvent.VK_ALT);
            input.action = on(KeyEvent.VK_F);
            input.turn = 0;
            if (!on(KeyEvent.VK_SHIFT)) {
                if (on(KeyEvent.VK_LEFT, KeyEvent.VK_Q, KeyEvent.VK_A)) {
                    input.turn -= 60;
                }
                if (on(KeyEvent.VK_RIGHT, KeyEvent.VK_D, KeyEvent.VK_E)) {
                    input.turn += 60;
                }
            }

            int santeAvant = world.health();
            int zoneAvant = world.player.zone;
            world.frame(input);
            for (String m : world.messages.drain()) {
                dire(m);
            }
            if (world.player.zone != zoneAvant) {
                dire("secteur " + zoneAvant + " -> " + world.player.zone);
            }
            if (world.health() < santeAvant) {
                dire("touche : -" + (santeAvant - world.health()) + " (sante "
                        + world.health() + ")");
            }

            images++;
            long maintenant = System.nanoTime();
            if (maintenant - dernierTop >= 1_000_000_000L) {
                parSeconde = images * 1e9 / (maintenant - dernierTop);
                images = 0;
                dernierTop = maintenant;
            }
            repaint();
        }

        private void dire(String texte) {
            journal.addFirst(texte);
            while (journal.size() > 12) {
                journal.removeLast();
            }
        }

        // ---- l'affichage ----

        private double camX;
        private double camZ;

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            double px = world.player.xOff >> 16;
            double pz = world.player.zOff >> 16;
            if (suitLeJoueur) {
                camX = px;
                camZ = pz;
            }

            decor(g);
            portes(g);
            objets(g);
            monstres(g);
            balles(g);
            joueur(g, px, pz);
            tableauDeBord(g);
        }

        private int sx(double x) {
            return (int) Math.round(getWidth() / 2.0 + (x - camX) * echelle);
        }

        private int sy(double z) {
            return (int) Math.round(getHeight() / 2.0 - (z - camZ) * echelle);
        }

        /** Les aretes : pleines pour un mur, pales pour un passage. */
        private void decor(Graphics2D g) {
            List<Level.Point> pts = world.level.points();
            g.setStroke(new BasicStroke(1.4f));
            for (Level.Edge e : world.level.edges()) {
                int x1 = sx(e.x());
                int y1 = sy(e.z());
                int x2 = sx(e.x() + e.dx());
                int y2 = sy(e.z() + e.dz());
                g.setColor(e.solid() ? new Color(120, 150, 185) : new Color(42, 52, 66));
                g.drawLine(x1, y1, x2, y2);
            }
            if (pts.isEmpty()) {
                return;
            }
        }

        /** Les portes et les ascenseurs, teintes par leur avancement. */
        private void portes(Graphics2D g) {
            g.setStroke(new BasicStroke(3f));
            dessineLiftables(g, world.level.doors(), true, new Color(220, 170, 60));
            dessineLiftables(g, world.level.lifts(), false, new Color(90, 200, 220));
        }

        private void dessineLiftables(Graphics2D g, List<Level.Liftable> list, boolean porte,
                Color couleur) {
            List<Level.Edge> edges = world.level.edges();
            for (int i = 0; i < list.size(); i++) {
                Level.Liftable l = list.get(i);
                int pos = porte ? world.anims.doorPosition(i) : world.anims.liftPosition(i);
                int course = l.top() - l.bottom();
                double avancement = course == 0 ? 0
                        : Math.max(0, Math.min(1, (double) (pos - l.bottom()) / course));
                g.setColor(melange(couleur, new Color(70, 80, 95), 1 - avancement));
                for (Level.Liftable.Wall w : l.walls()) {
                    if (w.edge() < 0 || w.edge() >= edges.size()) {
                        continue;
                    }
                    Level.Edge e = edges.get(w.edge());
                    g.drawLine(sx(e.x()), sy(e.z()), sx(e.x() + e.dx()), sy(e.z() + e.dz()));
                }
            }
        }

        private static Color melange(Color a, Color b, double t) {
            double u = 1 - t;
            return new Color(
                    (int) (a.getRed() * u + b.getRed() * t),
                    (int) (a.getGreen() * u + b.getGreen() * t),
                    (int) (a.getBlue() * u + b.getBlue() * t));
        }

        /** Les objets encore au sol : ramassables en vert, interrupteurs en violet. */
        private void objets(Graphics2D g) {
            for (ObjectRuntime.Thing t : world.objects.collectables()) {
                if (t.zone < 0) {
                    continue;
                }
                point(g, t.entity.x(), t.entity.z(), 3,
                        t.entity.holdsKey() ? new Color(255, 215, 80) : new Color(110, 210, 120));
            }
            for (ObjectRuntime.Thing t : world.objects.activatables()) {
                if (t.zone < 0) {
                    continue;
                }
                point(g, t.entity.x(), t.entity.z(), 4,
                        t.activated ? new Color(240, 240, 255) : new Color(170, 110, 230));
            }
        }

        /** Les monstres vivants, avec leur orientation et un oeil s'ils voient le joueur. */
        private void monstres(Graphics2D g) {
            SinCos sc = ab3d2.full.Data.sinCos();
            for (Aliens.Alien a : world.aliens.aliens()) {
                if (a.zone < 0 || (a.hitPoints & 0xFF) == 0) {
                    continue;
                }
                Color c = a.seePlayer ? new Color(255, 90, 80) : new Color(200, 110, 90);
                point(g, a.x, a.z, 4, c);
                int dx = sc.sin(a.angle);
                int dz = sc.cos(a.angle);
                g.setColor(c);
                g.setStroke(new BasicStroke(1.2f));
                g.drawLine(sx(a.x), sy(a.z),
                        sx(a.x + dx * 90.0 / 32768), sy(a.z + dz * 90.0 / 32768));
            }
        }

        /** Les balles en vol. */
        private void balles(Graphics2D g) {
            for (Shots.Shot s : world.shots.shots()) {
                if (!s.active) {
                    continue;
                }
                point(g, s.x >> 16, s.z >> 16, s.popping ? 5 : 2,
                        s.popping ? new Color(255, 180, 60) : new Color(255, 245, 180));
            }
        }

        private void joueur(Graphics2D g, double px, double pz) {
            int dx = ab3d2.full.Data.sinCos().sin(world.player.angPos);
            int dz = ab3d2.full.Data.sinCos().cos(world.player.angPos);
            g.setColor(new Color(120, 220, 255));
            g.setStroke(new BasicStroke(2f));
            g.drawLine(sx(px), sy(pz),
                    sx(px + dx * 140.0 / 32768), sy(pz + dz * 140.0 / 32768));
            point(g, px, pz, 5, new Color(120, 220, 255));
        }

        private void point(Graphics2D g, double x, double z, int r, Color c) {
            g.setColor(c);
            g.fillOval(sx(x) - r, sy(z) - r, r * 2, r * 2);
        }

        /** Ce qu'il faut savoir sans quitter l'ecran des yeux. */
        private void tableauDeBord(Graphics2D g) {
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(16, 18, 24, 215));
            g.fillRect(0, 0, 360, 92);
            g.setColor(new Color(215, 225, 240));

            Database.Gun arme = world.glf.gun(world.gun);
            int munitions = arme == null ? 0
                    : world.objects.inventory.ammo(arme.shoot().bulletType());
            int vivants = 0;
            for (Aliens.Alien a : world.aliens.aliens()) {
                if (a.zone >= 0 && (a.hitPoints & 0xFF) != 0) {
                    vivants++;
                }
            }
            g.drawString(String.format("sante %4d    secteur %3d%s    image %6d   %.0f/s",
                    world.health(), world.player.zone,
                    world.player.stoodInTop ? " (haut)" : "",
                    world.frame, parSeconde), 10, 20);
            g.drawString(String.format("arme   %-12s munitions %4d   monstres %3d",
                    arme == null ? "?" : arme.name(), munitions, vivants), 10, 38);
            g.drawString(String.format("x %6d  z %6d  angle %5d   verrous 0x%04X",
                    world.player.xOff >> 16, world.player.zOff >> 16,
                    world.player.angPos & (TOUR - 1), world.anims.doorLocks), 10, 56);
            g.drawString(world.dead() ? "MORT — relancer pour recommencer"
                    : "TAB camera   +/- zoom   1-9 arme", 10, 74);

            int y = getHeight() - 14;
            List<String> lignes = new ArrayList<>(journal);
            for (int i = 0; i < lignes.size(); i++) {
                int alpha = Math.max(40, 235 - i * 18);
                g.setColor(new Color(190, 205, 225, alpha));
                g.drawString(lignes.get(i), 10, y);
                y -= 15;
            }
        }
    }
}
