package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.level.VectorModel;
import ab3d2.full.render.BrightRings;
import ab3d2.full.render.Lights;
import ab3d2.full.render.ModelFace;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifie les FACES d'un modele : laquelle se peint, et de quelle couleur.
 *
 * <p>Pour chaque modele releve, les faces sont confrontees DANS L'ORDRE ou le jeu les visite :
 * partie par partie selon le tri en profondeur, face par face dans chaque partie. Comparer
 * l'ordre autant que le contenu eprouve donc aussi, une seconde fois et par un autre bout, le
 * tri des parties.
 *
 * <h2>Ce que l'oracle dit, et ce qu'il ne dit pas</h2>
 *
 * <p>Il dit le sort de chaque face. Il ne dit la texture et la palette que de celles qui sont
 * allees jusqu'au remplissage, parce que c'est seulement la que le jeu les calcule : une face
 * ecartee par le decoupage d'ecran — un decoupage qui n'est pas encore porte — n'a pas de
 * teinte a montrer. La verification ne choisit donc jamais elle-meme lesquelles teinter ; elle
 * teinte celles que l'oracle nomme, et compte les autres.
 */
public final class ModelFacesCheck {

    private ModelFacesCheck() {
    }

    private static int ecarts;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.modelfaces", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=modelfaces");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonObject champs = root.getAsJsonObject("objects");
        JsonArray cas = root.getAsJsonArray("cases");

        int modeles = 0;
        int debordements = 0;
        long faces = 0;
        long teintes = 0;
        char courant = 0;
        int avant = 0;

        ObjectView view = new ObjectView(Data.sinCos());
        ModelPoints mp = new ModelPoints(Data.sinCos());
        ModelParts parts = new ModelParts();
        ModelFace face = new ModelFace();
        Lights lights = null;
        BrightRings rings = null;
        boolean horsTables = false;
        Map<Integer, Map<Integer, List<VectorModel.Face>>> parModele = new HashMap<>();

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
                JsonArray dit = o.getAsJsonArray("faces");
                String quoi = String.format("%c vue %d, entite %d (modele %d, pose %d)",
                        lettre, i, e, modele, pose);

                int depth = view.rotZ(point);
                int centreY = c.get("centreY").getAsInt();
                if (depth == 0 && e == lvl.weaponEntity()) {
                    depth = 1;
                    centreY = Screen.FULL_HEIGHT / 2;
                }
                if (depth <= 0 || horsTables) {
                    if (depth <= 0 && dit.size() > 0) {
                        ecarts += note("%s : ecarte (profondeur %d), l'oracle en dit %d faces",
                                quoi, depth, dit.size());
                    }
                    continue;
                }

                VectorModel vm = Data.model(modele);
                if (pose >= vm.frames().size()) {
                    ecarts += note("%s : pose hors du modele", quoi);
                    continue;
                }
                rings.clearOffTable();
                rings.calc(zone, haut, objX, objZ);
                mp.buildGrid(rings);
                boolean tient = mp.project(vm, pose, objAngle, c.get("angle").getAsInt(),
                        view.worldX(point), depth, objY, c.get("eyeY").getAsInt(),
                        c.get("centreX").getAsInt(), centreY);
                if (!tient) {
                    if (dit.size() > 0) {
                        ecarts += note("%s : abandonne, l'oracle en dit %d faces", quoi,
                                dit.size());
                    }
                    continue;
                }
                parts.clearOverflow();
                boolean tenu = parts.sort(vm, pose, mp);
                if (!tenu) {
                    debordements++;
                    continue;
                }
                modeles++;

                Map<Integer, List<VectorModel.Face>> ou = parModele.computeIfAbsent(modele,
                        k -> {
                            Map<Integer, List<VectorModel.Face>> t = new HashMap<>();
                            for (VectorModel.Part p : Data.model(k).parts()) {
                                t.put(p.at(), p.faces());
                            }
                            return t;
                        });

                int[] angles = vm.frames().get(pose).angles();
                int k = 0;
                boolean casse = false;
                for (int pi = 0; pi < parts.count() && !casse; pi++) {
                    List<VectorModel.Face> liste = ou.get(parts.where(pi));
                    if (liste == null) {
                        ecarts += note("%s, partie %d : aucune face a %d", quoi, pi,
                                parts.where(pi));
                        casse = true;
                        break;
                    }
                    for (VectorModel.Face f : liste) {
                        if (k >= dit.size()) {
                            ecarts += note("%s : face a %d en trop, l'oracle en dit %d",
                                    quoi, f.at(), dit.size());
                            casse = true;
                            break;
                        }
                        JsonArray q = dit.get(k).getAsJsonArray();
                        k++;
                        faces++;
                        if (f.at() != q.get(0).getAsInt()) {
                            ecarts += note("%s, face %d : dossier a %d, l'oracle dit %d",
                                    quoi, k - 1, f.at(), q.get(0).getAsInt());
                            casse = true;
                            break;
                        }
                        int mien = face.sort(f, mp);
                        if (mien != q.get(1).getAsInt()) {
                            ecarts += note("%s, face a %d : %s, l'oracle dit %s",
                                    quoi, f.at(), nom(mien), nom(q.get(1).getAsInt()));
                            casse = true;
                            break;
                        }
                        if (q.get(2).getAsInt() < 0) {
                            continue;
                        }
                        teintes++;
                        int tex = face.texture(f);
                        if (tex != q.get(2).getAsInt()) {
                            ecarts += note("%s, face a %d : texture a %d, l'oracle dit %d",
                                    quoi, f.at(), tex, q.get(2).getAsInt());
                            casse = true;
                            break;
                        }
                        int pal = face.palette(f, angles, objAngle, mp, rings);
                        if (pal != q.get(3).getAsInt()) {
                            ecarts += note("%s, face a %d : palette a %d, l'oracle dit %d",
                                    quoi, f.at(), pal, q.get(3).getAsInt());
                            casse = true;
                            break;
                        }
                    }
                }
                if (!casse && k != dit.size()) {
                    ecarts += note("%s : %d faces, l'oracle en dit %d", quoi, k, dit.size());
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecarts - avant);
        }

        System.out.printf("%n%d vues, %d modeles, %d faces (%d jetees par le garde-fou,"
                + " %d de dos, %d teintees : %d ordinaires, %d en degrade, %d qui miroitent,"
                + " %d a trous), %d debordement(s), %d ecart(s) -> %s%n",
                cas.size(), modeles, faces, face.guarded(), face.turned(), teintes,
                face.plain(), face.smooth(), face.shiny(), face.holed(), debordements, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static String nom(int sort) {
        return switch (sort) {
            case ModelFace.GUARDED -> "jetee par le garde-fou";
            case ModelFace.TURNED -> "de dos";
            default -> "gardee";
        };
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
