package ab3d2.full;

import ab3d2.full.render.EdgePvs;
import ab3d2.full.level.Level;
import ab3d2.full.render.Lights;
import ab3d2.full.render.Scene;
import ab3d2.full.render.View;
import ab3d2.full.render.VisibleEdges;
import ab3d2.full.render.ZoneOrder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifie la MISE EN SCENE : ce que la boucle maitresse decide de dessiner, dans l'ordre.
 *
 * <p>C'est la premiere verification qui ne regarde aucun pixel. Toutes les autres posaient
 * elles-memes un secteur, une fenetre et une luminosite, puis eprouvaient le dessin ; elles
 * tenaient donc pour acquis ce qui le commande. Celle-ci fait l'inverse : elle ne dessine rien
 * et confronte la suite complete des decisions.
 *
 * <p>Une commande est comparee sur treize valeurs. Les douze premieres sont le contexte — le
 * secteur, son etage, sa luminosite, la fenetre horizontale, le haut et le bas de la piece, les
 * quatre bornes du plan d'eau, et le fait d'etre dans le secteur du joueur. La treizieme est le
 * code de la commande.
 *
 * <p>Une quatorzieme, l'offset du record dans le fichier d'habillage, n'est comparee que pour
 * les pans et les surfaces horizontales : ce sont les seules commandes qui en portent un dans
 * le niveau tel que ce portage le lit. Elle vaut mieux que son rang, car elle designe l'objet
 * lui-meme : deux murs peuvent se suivre, un seul est a cette adresse.
 */
public final class ZoneGraphCheck {

    private ZoneGraphCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.zonegraph", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=zonegraph");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        View view = new View(Data.sinCos());
        VisibleEdges edges = new VisibleEdges(Data.sinCos());
        ZoneOrder order = new ZoneOrder();
        Scene scene = new Scene();
        Journal journal = new Journal();

        int ecarts = 0;
        int poses = 0;
        long commandes = 0;
        char courant = 0;
        int ecartsNiveau = 0;
        EdgePvs pvs = null;
        Lights lights = null;

        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            char lettre = c.get("level").getAsString().charAt(0);
            if (lettre != courant) {
                if (courant != 0) {
                    ligne(courant, ecartsNiveau);
                    ecarts += ecartsNiveau;
                }
                courant = lettre;
                ecartsNiveau = 0;
                pvs = Data.edgePvs(lettre);
                lights = new Lights(Data.level(lettre), Data.sinCos());
            }
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();
            int x = c.get("x").getAsInt();
            int z = c.get("z").getAsInt();
            int y = c.get("y").getAsInt();
            int angle = c.get("angle").getAsInt();
            Level.Zone from = lvl.zones().get(zone);

            view.screen(true);
            view.update(lvl, x, z, angle, 0);
            // Les portes sont toutes fermees : le niveau vient d'etre charge, et l'oracle n'y
            // a pas touche non plus.
            edges.update(lvl, pvs, zone, x, z, angle, 0);
            edges.span(view);
            order.reset();
            order.update(lvl, from, x, z);

            poses++;

            // Avant le plan lui-meme, les trois entrees dont il depend. Les comparer ici
            // separement evite de lire un plan faux comme une faute de la marche : si les
            // secteurs retenus different, c'est en amont que cela se joue.
            if (edges.visibleCount() != c.get("visJoins").getAsInt()) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c depuis %d angle %d :"
                        + " %d ouvertures retenues, l'oracle en dit %d", lettre, zone, angle,
                        edges.visibleCount(), c.get("visJoins").getAsInt());
                continue;
            }
            JsonArray ordreOracle = c.getAsJsonArray("order");
            JsonArray marques = c.getAsJsonArray("marks");
            int[] mien = order.order();
            if (mien.length != ordreOracle.size()) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c depuis %d angle %d : %d secteurs"
                        + " dans l'ordre, l'oracle en dit %d", lettre, zone, angle, mien.length,
                        ordreOracle.size());
                continue;
            }
            boolean amont = false;
            for (int k = 0; k < mien.length && !amont; k++) {
                if (mien[k] != ordreOracle.get(k).getAsInt()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c depuis %d angle %d : ordre"
                            + " rang %d = %d, l'oracle dit %d", lettre, zone, angle, k, mien[k],
                            ordreOracle.get(k).getAsInt());
                    amont = true;
                } else if (edges.draws(mien[k]) != (marques.get(k).getAsInt() != 0)) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c depuis %d angle %d : secteur"
                            + " %d %s, l'oracle dit %s", lettre, zone, angle, mien[k],
                            edges.draws(mien[k]) ? "dessine" : "ecarte",
                            marques.get(k).getAsInt() != 0 ? "dessine" : "ecarte");
                    amont = true;
                }
            }
            if (amont) {
                continue;
            }

            journal.clear();
            scene.draw(lvl, from, order, edges, lights, view, y, journal);

            JsonArray attendu = c.getAsJsonArray("plan");
            commandes += attendu.size();
            if (journal.lignes.size() != attendu.size()) {
                ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d angle %d y %d :"
                        + " %d commandes, l'oracle en dit %d", lettre, zone, angle, y,
                        journal.lignes.size(), attendu.size());
                continue;
            }
            for (int k = 0; k < attendu.size() && ecartsNiveau < 10; k++) {
                int[] moi = journal.lignes.get(k);
                JsonArray lui = attendu.get(k).getAsJsonArray();
                for (int f = 0; f < CHAMPS.length; f++) {
                    if (f == OFFSET && !porteUnOffset(moi[OP])) {
                        continue;                      // pas d'offset pour les objets ni le ciel
                    }
                    if (moi[f] != lui.get(f).getAsInt()) {
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c depuis %d angle %d y %d,"
                                + " commande %d : secteur %d %s (%s) : %s = %d, l'oracle dit %d",
                                lettre, zone, angle, y, k, moi[0],
                                moi[1] != 0 ? "haut" : "bas", nom(moi[OP]), CHAMPS[f], moi[f],
                                lui.get(f).getAsInt());
                        break;
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d poses, %d commandes, %d ecart(s) -> %s%n", poses, commandes,
                ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /** Le nom de chaque valeur comparee, dans l'ordre du releve. */
    private static final String[] CHAMPS = {
        "secteur", "etage haut", "luminosite", "borne gauche", "borne droite", "haut de piece",
        "bas de piece", "haut avant l'eau", "bas avant l'eau", "haut apres l'eau",
        "bas apres l'eau", "secteur du joueur", "commande", "offset",
    };

    /** Le rang du code de commande dans le releve. */
    private static final int OP = 12;

    /** Le rang de l'offset. */
    private static final int OFFSET = 13;

    /** Seuls les pans et les surfaces horizontales ont un record, donc une adresse. */
    private static boolean porteUnOffset(int op) {
        return op == 0 || op == 1 || op == 2 || op == 7;
    }

    private static String nom(int op) {
        return switch (op) {
            case 0 -> "mur";
            case 1 -> "sol";
            case 2 -> "plafond";
            case 4 -> "objets";
            case 7 -> "eau";
            case 12 -> "ciel";
            default -> "code " + op;
        };
    }

    /** Un peintre qui ne peint rien : il note ce qu'on lui demande, et c'est ce qu'on compare. */
    private static final class Journal implements Scene.Painter {

        private final List<int[]> lignes = new ArrayList<>();

        void clear() {
            lignes.clear();
        }

        private void ajoute(Scene.Pass p, int op, int offset) {
            lignes.add(new int[] {
                p.zone(), p.upper() ? 1 : 0, p.bright(), p.leftClip(), p.rightClip(),
                p.topOfRoom(), p.bottomOfRoom(), p.beforeWaterTop(), p.beforeWaterBottom(),
                p.afterWaterTop(), p.afterWaterBottom(), p.rootZone() ? 1 : 0, op, offset,
            });
        }

        @Override
        public void wall(Scene.Pass pass, Level.Wall wall) {
            ajoute(pass, 0, wall.offset());
        }

        @Override
        public void flat(Scene.Pass pass, Level.Flat flat) {
            int op = switch (flat.kind()) {
                case FLOOR -> 1;
                case CEILING -> 2;
                case WATER -> 7;
            };
            ajoute(pass, op, flat.offset());
        }

        @Override
        public void objects(Scene.Pass pass, Level.Objects objects) {
            ajoute(pass, 4, 0);
        }

        @Override
        public void sky(Scene.Pass pass, Level.Sky sky) {
            ajoute(pass, 12, 0);
        }
    }

    private static int note(int dejaVus, String format, Object... args) {
        if (dejaVus < 10) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }

    private static void ligne(char lettre, int ecarts) {
        System.out.printf("  %c : %s%n", lettre, ecarts == 0 ? "conforme" : ecarts + " ECART(S)");
    }
}
