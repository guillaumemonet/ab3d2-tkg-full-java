package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.level.VectorModel;
import ab3d2.full.render.BrightRings;
import ab3d2.full.render.Lights;
import ab3d2.full.render.ModelEdges;
import ab3d2.full.render.ModelFace;
import ab3d2.full.render.ModelPaint;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifie les PIXELS d'un modele en relief : les quatre remplisseurs de polygones.
 *
 * <p>Chaque colonne peinte est confrontee a celle de l'oracle : ou elle commence, combien de
 * lignes elle couvre, et les octets qu'elle laisse. Les colonnes se suivent dans l'ordre ou
 * elles sont peintes, ce qui re-eprouve l'ordre des faces et des parties par un autre bout.
 *
 * <p>L'ecran recoit le MEME motif de depart que l'oracle, avant chaque modele. Il n'est pas
 * uniforme : le miroitement lit le pixel deja pose, et un fond d'une seule couleur ne
 * l'eprouverait sur rien.
 */
public final class ModelPixelsCheck {

    private ModelPixelsCheck() {
    }

    private static int ecarts;

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.modelpixels", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=modelpixels");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonObject champs = root.getAsJsonObject("objects");
        JsonArray cas = root.getAsJsonArray("cases");
        Base64.Decoder decodeur = Base64.getDecoder();

        int modeles = 0;
        long colonnes = 0;
        long pixels = 0;
        long[] parGenre = new long[4];
        char courant = 0;
        int avant = 0;

        ObjectView view = new ObjectView(Data.sinCos());
        ModelPoints mp = new ModelPoints(Data.sinCos());
        ModelParts parts = new ModelParts();
        ModelFace face = new ModelFace();
        ModelEdges edges = new ModelEdges();
        ModelPaint paint = new ModelPaint();
        Screen screen = new Screen();
        byte[] attendu = new byte[Screen.WIDTH * Screen.HEIGHT];
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
            int topClip = c.get("topClip").getAsInt();
            int botClip = c.get("botClip").getAsInt();
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
                JsonArray cols = o.getAsJsonArray("columns");
                JsonArray dits = o.getAsJsonArray("pixels");
                String quoi = String.format("%c vue %d, entite %d (modele %d, pose %d)",
                        lettre, i, e, modele, pose);

                motif(screen);

                int depth = view.rotZ(point);
                int centreY = c.get("centreY").getAsInt();
                if (depth == 0 && e == lvl.weaponEntity()) {
                    depth = 1;
                    centreY = Screen.FULL_HEIGHT / 2;
                }
                if (depth <= 0 || horsTables) {
                    if (depth <= 0 && cols.size() > 0) {
                        ecarts += note("%s : ecarte (profondeur %d), l'oracle en dit %d"
                                + " colonnes", quoi, depth, cols.size());
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
                    if (cols.size() > 0) {
                        ecarts += note("%s : abandonne, l'oracle en dit %d colonnes", quoi,
                                cols.size());
                    }
                    continue;
                }
                parts.clearOverflow();
                if (!parts.sort(vm, pose, mp)) {
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
                        if (face.sort(f, mp) != ModelFace.SHOWN) {
                            continue;
                        }
                        edges.start();
                        edges.lines(f, mp, leftClip, rightClip, f.gouraud());
                        if (!edges.clip(leftClip, rightClip)) {
                            continue;
                        }
                        int kind = ModelFace.kind(f);
                        int tex = face.texture(f);
                        int pal = face.palette(f, angles, objAngle, mp, rings);
                        int peintes = paint.fill(edges, kind, tex, pal,
                                Data.textureMaps(), Data.shades(), screen, topClip, botClip);
                        colonnes += peintes;
                        parGenre[kind] += peintes;
                        k += peintes;
                    }
                }
                if (casse) {
                    continue;
                }
                if (k != cols.size()) {
                    ecarts += note("%s : %d colonnes peintes, l'oracle en dit %d", quoi, k,
                            cols.size());
                    continue;
                }
                // L'ecran attendu : le motif, puis les colonnes que l'oracle dit, dans
                // l'ordre. On le confronte ENTIEREMENT a celui qu'on vient de peindre, de
                // sorte qu'une colonne de trop, de moins ou decalee se voie aussi.
                motif(attendu);
                for (int n = 0; n < cols.size(); n++) {
                    JsonArray q = cols.get(n).getAsJsonArray();
                    int cx = q.get(0).getAsInt();
                    int cy = q.get(1).getAsInt();
                    byte[] dit = decodeur.decode(dits.get(n).getAsString());
                    pixels += dit.length;
                    for (int j = 0; j < dit.length; j++) {
                        attendu[(cy + j) * Screen.WIDTH + cx] = dit[j];
                    }
                }
                byte[] mien = screen.pixels();
                for (int off = 0; off < Screen.WIDTH * Screen.FULL_HEIGHT; off++) {
                    if (mien[off] != attendu[off]) {
                        ecarts += note("%s : colonne %d ligne %d vaut %d, l'oracle dit %d",
                                quoi, off % Screen.WIDTH, off / Screen.WIDTH,
                                mien[off] & 0xFF, attendu[off] & 0xFF);
                        break;
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecarts - avant);
        }

        System.out.printf("%n%d vues, %d modeles, %d colonnes (%d ordinaires, %d a trous,"
                + " %d en degrade, %d qui miroitent), %d pixels, %d hors table, %d hors ecran,"
                + " %d diviseurs bornes, %d ecart(s) -> %s%n",
                cas.size(), modeles, colonnes, parGenre[ModelPaint.PLAIN],
                parGenre[ModelPaint.HOLES], parGenre[ModelPaint.GOURAUD],
                parGenre[ModelPaint.GLARE], pixels, paint.offTable(), paint.offScreen(),
                paint.clamped(), ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static void motif(Screen screen) {
        motif(screen.pixels());
    }

    /** Le motif de depart, fonction des coordonnees absolues de l'ecran. */
    private static void motif(byte[] out) {
        for (int y = 0; y < Screen.FULL_HEIGHT; y++) {
            int ligne = y * Screen.WIDTH;
            for (int x = 0; x < Screen.WIDTH; x++) {
                out[ligne + x] = (byte) ((x * 7 + y * 13) & 0xFF);
            }
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
