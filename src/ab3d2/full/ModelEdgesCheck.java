package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.level.VectorModel;
import ab3d2.full.render.BrightRings;
import ab3d2.full.render.Lights;
import ab3d2.full.render.ModelEdges;
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
 * Verifie les BORDS d'une face : ce que le trace laisse dans les deux tables de colonnes.
 *
 * <p>Pour chaque face que le garde-fou et le dos tourne ont laissee passer, les deux bornes
 * de l'objet, la plage retenue par le decoupage, et le contenu des deux tables sur cette
 * plage — ordonnee, deux coordonnees de texture, clarte — colonne par colonne.
 *
 * <h2>Ce que l'oracle releve</h2>
 *
 * <p>Seulement les faces arrivees jusqu'au trace. Pour savoir lesquelles, la verification
 * rejoue le tri des faces, qui est du code DEJA eprouve par son propre oracle : s'il se
 * trompait, le relevé se desalignerait aussitot et la confrontation le dirait. Ce n'est pas
 * la meme chose que d'ecarter un cas sur la foi du code qu'on eprouve.
 *
 * <p>Une face que le decoupage d'ecran ecarte n'a pas de plage : l'oracle la marque et la
 * verification exige que le portage l'ecarte aussi.
 */
public final class ModelEdgesCheck {

    private ModelEdgesCheck() {
    }

    private static int ecarts;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.modeledges", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=modeledges");
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
        long peintes = 0;
        long colonnes = 0;
        long degrade = 0;
        char courant = 0;
        int avant = 0;

        ObjectView view = new ObjectView(Data.sinCos());
        ModelPoints mp = new ModelPoints(Data.sinCos());
        ModelParts parts = new ModelParts();
        ModelFace face = new ModelFace();
        ModelEdges edges = new ModelEdges();
        Lights lights = null;
        BrightRings rings = null;
        boolean horsTables = false;
        Map<Integer, Map<Integer, List<VectorModel.Face>>> parModele = new HashMap<>();

        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            char lettre = c.get("level").getAsString().charAt(0);
            Level lvl = Data.level(lettre);
            int zone = c.get("zone").getAsInt();
            int leftClip = c.get("leftClip").getAsInt();
            int rightClip = c.get("rightClip").getAsInt();
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
                        if (face.sort(f, mp) != ModelFace.SHOWN) {
                            continue;        // l'oracle ne releve que les faces tracees
                        }
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
                        if (f.gouraud()) {
                            degrade++;
                        }
                        edges.start();
                        edges.lines(f, mp, leftClip, rightClip, f.gouraud());
                        boolean peinte = edges.clip(leftClip, rightClip);
                        if ((edges.drawn() ? 1 : 0) != q.get(1).getAsInt()
                                || edges.left() != q.get(2).getAsInt()
                                || edges.right() != q.get(3).getAsInt()) {
                            ecarts += note("%s, face a %d : tracee=%b bornes [%d,%d],"
                                    + " l'oracle dit tracee=%d bornes [%d,%d]",
                                    quoi, f.at(), edges.drawn(), edges.left(), edges.right(),
                                    q.get(1).getAsInt(), q.get(2).getAsInt(),
                                    q.get(3).getAsInt());
                            casse = true;
                            break;
                        }
                        int first = q.get(4).getAsInt();
                        if (peinte != (first >= 0)) {
                            ecarts += note("%s, face a %d : %s, l'oracle dit %s", quoi, f.at(),
                                    peinte ? "peinte" : "ecartee",
                                    first >= 0 ? "peinte" : "ecartee");
                            casse = true;
                            break;
                        }
                        if (!peinte) {
                            continue;
                        }
                        peintes++;
                        if (edges.first() != first || edges.span() != q.get(5).getAsInt()) {
                            ecarts += note("%s, face a %d : plage %d+%d, l'oracle dit %d+%d",
                                    quoi, f.at(), edges.first(), edges.span(), first,
                                    q.get(5).getAsInt());
                            casse = true;
                            break;
                        }
                        int at = 6;
                        for (int x = first; x <= first + edges.span(); x++) {
                            colonnes++;
                            int[] mien = {
                                edges.topY(x), edges.topU(x), edges.topV(x), edges.topBright(x),
                                edges.botY(x), edges.botU(x), edges.botV(x), edges.botBright(x),
                            };
                            for (int j = 0; j < 8; j++) {
                                int dit2 = q.get(at + j).getAsInt();
                                if (mien[j] != dit2) {
                                    ecarts += note("%s, face a %d, colonne %d : %s = %d,"
                                            + " l'oracle dit %d", quoi, f.at(), x,
                                            CHAMPS[j], mien[j], dit2);
                                    casse = true;
                                    break;
                                }
                            }
                            if (casse) {
                                break;
                            }
                            at += 8;
                        }
                    }
                }
                if (!casse && k != dit.size()) {
                    ecarts += note("%s : %d faces tracees, l'oracle en dit %d", quoi, k,
                            dit.size());
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecarts - avant);
        }

        System.out.printf("%n%d vues, %d modeles, %d faces tracees (%d en degrade), %d peintes,"
                + " %d colonnes, %d debordement(s), %d ecart(s) -> %s%n",
                cas.size(), modeles, faces, degrade, peintes, colonnes, debordements, ecarts,
                ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static final String[] CHAMPS = {
        "ordonnee du haut", "u du haut", "v du haut", "clarte du haut",
        "ordonnee du bas", "u du bas", "v du bas", "clarte du bas",
    };

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
