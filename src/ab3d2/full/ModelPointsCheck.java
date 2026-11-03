package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.level.VectorModel;
import ab3d2.full.render.BrightRings;
import ab3d2.full.render.Lights;
import ab3d2.full.render.ModelParts;
import ab3d2.full.render.ModelPoints;
import ab3d2.full.render.ObjectView;
import ab3d2.full.render.Screen;

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
 * Verifie les POINTS D'UN MODELE : la grille d'eclairage, la rotation, la projection, et la
 * luminosite de chaque point.
 *
 * <p>Quatre choses confrontees d'un bloc, parce qu'une seule routine les produit. Pour chaque
 * modele relevé : les deux cent cinquante-six octets de la grille, puis six valeurs par point —
 * l'abscisse et la hauteur en mots doubles, la profondeur, la colonne et la ligne a l'ecran, et
 * la luminosite.
 *
 * <p>Un modele que l'original n'a PAS dessine ne porte pas de points, et son absence est
 * eprouvee comme le reste : il faut que nous le declinions aussi, et pour la meme raison.
 *
 * <h2>Ce qui est ecarte, et par qui</h2>
 *
 * <p>Comme pour les anneaux, la decision d'ecarter un cas est prise ICI, en refaisant le
 * parcours des points de bord, et jamais d'apres ce que la routine eprouvee a compte.
 */
public final class ModelPointsCheck {

    /** Les deux cent cinquante-six octets de la grille. */
    private static final int GRILLE = 256;

    private ModelPointsCheck() {
    }

    private static int ecarts;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.modelpoints", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=modelpoints");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonObject champs = root.getAsJsonObject("objects");
        JsonArray cas = root.getAsJsonArray("cases");

        int modeles = 0;
        int ecartes = 0;
        int abandons = 0;
        int armes = 0;
        int debordements = 0;
        long morceaux = 0;
        long points = 0;
        long octets = 0;
        int impairs = 0;
        char courant = 0;
        int avant = 0;

        ObjectView view = new ObjectView(Data.sinCos());
        ModelPoints mp = new ModelPoints(Data.sinCos());
        ModelParts parts = new ModelParts();
        Lights lights = null;
        BrightRings rings = null;
        boolean horsTables = false;

        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            char lettre = c.get("level").getAsString().charAt(0);
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();
            if (lettre != courant) {
                if (courant != 0) {
                    ligne(courant, ecarts - avant);
                }
                courant = lettre;
                avant = ecarts;
                lights = eclaire(lvl, zone, c);
                rings = new BrightRings(lvl, lights, Data.sinCos());
                horsTables = lights.offTable() > 0 || horsDesTables(lvl, zone);
            }

            JsonArray objets = champs.getAsJsonArray(String.valueOf(lettre));
            List<ObjectView.Thing> choses = new ArrayList<>(objets.size());
            for (int e = 0; e < objets.size(); e++) {
                Level.Entity ent = lvl.entities().get(e);
                choses.add(new ObjectView.Thing(ent.x(), ent.z(), ent.zone(), ent.zone(),
                        ent.upperZone(), ent.kind() == Level.Kind.AUX, ent.point()));
            }
            view.update(choses, lvl.objectPoints(), c.get("x").getAsInt(),
                    c.get("z").getAsInt(), c.get("angle").getAsInt(), 0, true);

            for (var m : c.getAsJsonArray("models")) {
                JsonObject o = m.getAsJsonObject();
                int e = o.get("entity").getAsInt();
                JsonArray champ = objets.get(e).getAsJsonArray();
                int point = champ.get(0).getAsInt();
                int objY = champ.get(1).getAsInt();
                int modele = champ.get(2).getAsInt();
                int pose = champ.get(3).getAsInt();
                int objAngle = champ.get(4).getAsInt();
                boolean haut = champ.get(5).getAsInt() != 0;
                int objX = champ.get(6).getAsInt();
                int objZ = champ.get(7).getAsInt();
                boolean releve = o.has("points");
                String quoi = String.format("%c vue %d, entite %d (modele %d, pose %d)",
                        lettre, i, e, modele, pose);

                int depth = view.rotZ(point);
                int centreY = c.get("centreY").getAsInt();
                if (depth == 0 && e == lvl.weaponEntity()) {
                    // L'arme en main : elle n'est pas derriere l'oeil, elle y est COLLEE.
                    depth = 1;                         // move.w #1,d1
                    centreY = Screen.FULL_HEIGHT / 2;  // move.w #FS_HEIGHT/2,draw_PolygonCentreY_w
                    armes++;
                }
                if (depth <= 0) {
                    if (releve) {
                        ecarts += note("%s : ecarte (profondeur %d), l'oracle le dessine",
                                quoi, depth);
                    }
                    ecartes++;
                    continue;
                }
                if (horsTables) {
                    ecartes++;
                    continue;
                }

                VectorModel vm = Data.model(modele);
                if (pose >= vm.frames().size()) {
                    ecarts += note("%s : pose hors du modele (%d poses)", quoi,
                            vm.frames().size());
                    continue;
                }
                rings.clearOffTable();
                rings.calc(zone, haut, objX, objZ);
                mp.buildGrid(rings);
                boolean tient = mp.project(vm, pose, objAngle, c.get("angle").getAsInt(),
                        view.worldX(point), depth, objY, c.get("eyeY").getAsInt(),
                        c.get("centreX").getAsInt(), centreY);
                if (!tient) {
                    if (releve) {
                        ecarts += note("%s : abandonne (trop loin), l'oracle le dessine", quoi);
                    }
                    abandons++;
                    continue;
                }
                if (!releve) {
                    ecarts += note("%s : dessine, l'oracle l'ecarte", quoi);
                    continue;
                }
                modeles++;

                if (vm.sort() != (o.get("sortIt").getAsInt() != 0)) {
                    ecarts += note("%s : tri = %b, l'oracle dit %d", quoi, vm.sort(),
                            o.get("sortIt").getAsInt());
                    continue;
                }
                if (vm.frames().get(pose).onOff() != o.get("onOff").getAsInt()) {
                    ecarts += note("%s : drapeaux de parties = 0x%08x, l'oracle dit 0x%08x",
                            quoi, vm.frames().get(pose).onOff(), o.get("onOff").getAsInt());
                    continue;
                }

                JsonArray grille = o.getAsJsonArray("grid");
                octets += GRILLE;
                boolean casse = false;
                for (int k = 0; k < GRILLE; k++) {
                    if (mp.grid()[k] != grille.get(k).getAsInt()) {
                        ecarts += note("%s : grille rang %d colonne %d = %d, l'oracle dit %d",
                                quoi, k / ModelPoints.RING, k % ModelPoints.RING,
                                mp.grid()[k], grille.get(k).getAsInt());
                        casse = true;
                        break;
                    }
                }
                if (casse) {
                    continue;
                }

                // ---- les parties, et leur ordre ----
                parts.clearOverflow();
                boolean tenu = parts.sort(vm, pose, mp);
                JsonArray parties = o.getAsJsonArray("parts");
                if (!tenu) {
                    debordements++;
                } else if (parts.count() != parties.size()) {
                    ecarts += note("%s : %d parties, l'oracle en dit %d", quoi, parts.count(),
                            parties.size());
                    continue;
                } else {
                    morceaux += parts.count();
                    for (int k = 0; k < parts.count() && !casse; k++) {
                        JsonArray q = k < parties.size() ? parties.get(k).getAsJsonArray() : null;
                        if (parts.distance(k) != q.get(0).getAsInt()) {
                            ecarts += note("%s, partie %d : distance = %d, l'oracle dit %d",
                                    quoi, k, parts.distance(k), q.get(0).getAsInt());
                            casse = true;
                        } else if (parts.where(k) != q.get(1).getAsInt()) {
                            ecarts += note("%s, partie %d : faces a %d, l'oracle dit %d",
                                    quoi, k, parts.where(k), q.get(1).getAsInt());
                            casse = true;
                        }
                    }
                    if (casse) {
                        continue;
                    }
                }

                JsonArray pts = o.getAsJsonArray("points");
                if (pts.size() != mp.count()) {
                    ecarts += note("%s : %d points, l'oracle en dit %d", quoi, mp.count(),
                            pts.size());
                    continue;
                }
                points += pts.size();
                String[] noms = {"abscisse", "hauteur", "profondeur", "colonne", "ligne",
                    "luminosite"};
                for (int k = 0; k < pts.size() && !casse; k++) {
                    JsonArray p = pts.get(k).getAsJsonArray();
                    int[] mien = {mp.x3(k), mp.y3(k), mp.z3(k), mp.screenX(k), mp.screenY(k),
                        mp.bright(k)};
                    for (int v = 0; v < mien.length; v++) {
                        if (mien[v] != p.get(v).getAsInt()) {
                            ecarts += note("%s, point %d : %s = %d, l'oracle dit %d", quoi, k,
                                    noms[v], mien[v], p.get(v).getAsInt());
                            casse = true;
                            break;
                        }
                    }
                }
            }
        }
        impairs = mp.oddAngles();
        if (courant != 0) {
            ligne(courant, ecarts - avant);
        }

        System.out.printf("%n%d vues, %d modeles, %d points, %d octets de grille, %d ecartes,"
                + " %d abandonnes, %d arme(s) en main, %d parties (%d hors modele),"
                + " %d debordement(s), %d angle(s) impair(s), %d ecart(s) -> %s%n", cas.size(),
                modeles, points, octets, ecartes, abandons, armes, morceaux,
                parts.beyondModel(), debordements, impairs, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /** Rejoue la semaille d'eclairage de l'oracle. */
    private static Lights eclaire(Level lvl, int zone, JsonObject c) {
        Lights lights = new Lights(lvl, Data.sinCos());
        for (int n = 0; n < c.get("frames").getAsInt(); n++) {
            lights.tick();
        }
        lights.frame(lvl.zones().get(zone));
        for (var e : c.getAsJsonArray("lights")) {
            JsonObject o = e.getAsJsonObject();
            int angle = o.get("angle").getAsInt();
            if (angle < 0) {
                lights.brighten(o.get("bright").getAsInt(), o.get("x").getAsInt(),
                        o.get("z").getAsInt(), o.get("zone").getAsInt(), o.get("y").getAsInt());
            } else {
                lights.brightenAngle(o.get("bright").getAsInt(), o.get("x").getAsInt(),
                        o.get("z").getAsInt(), o.get("zone").getAsInt(), angle,
                        o.get("y").getAsInt());
            }
        }
        return lights;
    }

    /**
     * Vrai si la boussole de ce secteur lirait hors des tables du jeu.
     *
     * <p>Copie deliberee de {@code BrightRingsCheck} : la decision d'ecarter ne doit rien
     * devoir au code qu'on eprouve.
     */
    private static boolean horsDesTables(Level lvl, int zone) {
        if (horsDuSecteur(lvl, zone)) {
            return true;
        }
        for (int e : lvl.zones().get(zone).outline()) {
            int off = (short) (e << 4);
            if ((off >> 4) != e || e >= lvl.edges().size()) {
                return true;
            }
            int join = lvl.edges().get(e).joinZone();
            if (join >= 0 && (join >= lvl.zones().size() || horsDuSecteur(lvl, join))) {
                return true;
            }
        }
        return false;
    }

    private static boolean horsDuSecteur(Level lvl, int zone) {
        int bo = zone * Lights.POINTS;
        int bp = zone * Lights.POINTS * Lights.SLOTS;
        int fin = lvl.zones().size() * Lights.POINTS;
        while (true) {
            if (bo >= fin) {
                return true;
            }
            var pts = lvl.zones().get(bo / Lights.POINTS).borderPoints();
            int k = bo % Lights.POINTS;
            bo++;
            int p = k < pts.size() ? pts.get(k) : 0;
            if (p < 0) {
                return false;
            }
            if (p >= lvl.points().size()
                    || bp + 1 >= lvl.zones().size() * Lights.POINTS * Lights.SLOTS) {
                return true;
            }
            bp += Lights.SLOTS;
        }
    }

    private static int note(String format, Object... args) {
        if (ecarts < 10) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }

    private static void ligne(char lettre, int combien) {
        System.out.printf("  %c : %s%n", lettre, combien == 0 ? "conforme" : combien + " ECART(S)");
    }
}
