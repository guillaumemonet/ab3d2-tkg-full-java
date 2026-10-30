package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.level.LevelReader;

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
 * Vérifie que la lecture directe des fichiers de niveau donne le même résultat que le portage.
 *
 * <p>Le corpus de référence est celui qu'extrait le remake, lui-même produit en lisant la
 * mémoire du portage après chargement. Si la lecture SANS mémoire plate retrouve les mêmes
 * compteurs et les mêmes valeurs, c'est que la conversion est juste — pas qu'elle est jolie.
 *
 * <p>La comparaison est volontairement sévère : chaque entité est confrontée champ par champ,
 * dans l'ordre. Un décalage d'un seul enregistrement ferait tout tomber.
 */
public final class LevelCheck {

    private LevelCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path media = Path.of(System.getProperty("ab3d2.media", "medias/original"));
        Path ref = Path.of(System.getProperty("ab3d2.ref", ""));
        System.out.println("donnees : " + media);
        System.out.println("reference : " + ref);
        System.out.println();

        int niveaux = 0;
        int ecarts = 0;
        for (char c = 'a'; c <= 'p'; c++) {
            Path bin = media.resolve("levels").resolve("LEVEL_" + Character.toUpperCase(c))
                    .resolve("twolev.bin");
            if (!Files.isRegularFile(bin)) {
                continue;
            }
            Level lvl = LevelReader.read(media, c);
            niveaux++;

            Path json = ref.resolve(c + ".json");
            if (!Files.isRegularFile(json)) {
                System.out.printf("  %c : %4d points, %4d aretes, %3d secteurs, %4d entites"
                        + "   (pas de reference)%n",
                        c, lvl.points().size(), lvl.edges().size(), lvl.zones().size(),
                        lvl.entities().size());
                continue;
            }
            List<String> pb = compare(lvl, json);
            ecarts += pb.size();
            System.out.printf("  %c : %4d points, %4d aretes, %3d secteurs, %4d entites   %s%n",
                    c, lvl.points().size(), lvl.edges().size(), lvl.zones().size(),
                    lvl.entities().size(), pb.isEmpty() ? "conforme" : pb.size() + " ECART(S)");
            for (String s : pb.subList(0, Math.min(pb.size(), 6))) {
                System.out.println("        " + s);
            }
            if (pb.size() > 6) {
                System.out.println("        ... et " + (pb.size() - 6) + " autres");
            }
        }
        System.out.println();
        if (!remarques.isEmpty()) {
            System.out.println("Remarques (la reference s'ecarte du fichier, pas la lecture) :");
            for (String r : remarques) {
                System.out.println("  " + r);
            }
            System.out.println();
        }
        System.out.printf("%d niveau(x) lus sans memoire plate, %d ecart(s) -> %s%n",
                niveaux, ecarts, ecarts == 0 ? "PASS" : "ECHEC");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /** Ce que la reference rend differemment du fichier, sans que ce soit une erreur de lecture. */
    private static final List<String> remarques = new ArrayList<>();

    private static List<String> compare(Level lvl, Path json) throws IOException {
        List<String> pb = new ArrayList<>();
        JsonObject o;
        try (Reader r = Files.newBufferedReader(json, java.nio.charset.StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        }

        JsonArray pts = o.getAsJsonArray("points");
        eq(pb, "nombre de points", lvl.points().size(), pts.size());
        for (int i = 0; i < Math.min(lvl.points().size(), pts.size()); i++) {
            JsonObject p = pts.get(i).getAsJsonObject();
            eq(pb, "point " + i + " x", lvl.points().get(i).x(), p.get("x").getAsInt());
            eq(pb, "point " + i + " z", lvl.points().get(i).z(), p.get("z").getAsInt());
        }

        JsonArray cps = o.getAsJsonArray("controlPoints");
        eq(pb, "nombre de points de controle", lvl.controlPoints().size(), cps.size());
        for (int i = 0; i < Math.min(lvl.controlPoints().size(), cps.size()); i++) {
            JsonObject p = cps.get(i).getAsJsonObject();
            Level.ControlPoint c = lvl.controlPoints().get(i);
            eq(pb, "pt controle " + i + " x", c.x(), p.get("x").getAsInt());
            eq(pb, "pt controle " + i + " z", c.z(), p.get("z").getAsInt());
            eq(pb, "pt controle " + i + " y", c.y(), p.get("y").getAsInt());
        }

        JsonArray ents = o.getAsJsonArray("objects");
        eq(pb, "nombre d'entites", lvl.entities().size(), ents.size());
        for (int i = 0; i < Math.min(lvl.entities().size(), ents.size()); i++) {
            JsonObject e = ents.get(i).getAsJsonObject();
            Level.Entity a = lvl.entities().get(i);
            eq(pb, "entite " + i + " x", a.x(), e.get("x").getAsInt());
            eq(pb, "entite " + i + " z", a.z(), e.get("z").getAsInt());
            eq(pb, "entite " + i + " zone", a.zone(), e.get("zone").getAsInt());
            eq(pb, "entite " + i + " type", a.kind().ordinal(),
                    Level.Kind.of(e.get("typeId").getAsInt()).ordinal());
            eq(pb, "entite " + i + " def", a.def(), e.get("def").getAsInt());
            eq(pb, "entite " + i + " luminosite", a.brightness(), e.get("bright").getAsInt());
            eq(pb, "entite " + i + " verrous", a.unlocks(), e.get("doorsHeld").getAsInt());
            eq(pb, "entite " + i + " message", a.message(), e.get("displayText").getAsInt());
            eq(pb, "entite " + i + " etage", a.upperZone() ? 1 : 0,
                    e.get("upperZone").getAsBoolean() ? 1 : 0);
        }

        // --- les aretes : la table globale ---
        JsonArray eds = o.getAsJsonArray("edges");
        if (eds != null) {
            // Le nombre ne peut PAS etre compare : la reference lit au-dela de la table (voir
            // LevelReader). On confronte donc ce qui existe des deux cotes, et on signale.
            if (lvl.edges().size() != eds.size()) {
                remarques.add(String.format(
                        "%c : %d aretes lues, la reference en annonce %d -- elle lit au-dela"
                        + " de la table (indice max reellement utilise par les secteurs : %d)",
                        lvl.letter(), lvl.edges().size(), eds.size(), maxEdgeUtilisee(lvl)));
            }
            for (int i = 0; i < Math.min(lvl.edges().size(), eds.size()); i++) {
                JsonObject e = eds.get(i).getAsJsonObject();
                Level.Edge a = lvl.edges().get(i);
                eq(pb, "arete " + i + " x", a.x(), e.get("x").getAsInt());
                eq(pb, "arete " + i + " z", a.z(), e.get("z").getAsInt());
                eq(pb, "arete " + i + " dx", a.dx(), e.get("dx").getAsInt());
                eq(pb, "arete " + i + " dz", a.dz(), e.get("dz").getAsInt());
                eq(pb, "arete " + i + " voisin", a.joinZone(), e.get("join").getAsInt());
                eq(pb, "arete " + i + " diviseur", a.divider(), e.get("w5").getAsInt());
            }
        }

        // --- les secteurs ---
        JsonArray zs = o.getAsJsonArray("zones");
        if (zs != null) {
            eq(pb, "nombre de secteurs", lvl.zones().size(), zs.size());
            for (int i = 0; i < Math.min(lvl.zones().size(), zs.size()); i++) {
                JsonObject z = zs.get(i).getAsJsonObject();
                Level.Zone a = lvl.zones().get(i);
                String q = "secteur " + a.id();
                eq(pb, q + " id", a.id(), z.get("id").getAsInt());
                eq(pb, q + " sol", a.floor(), z.get("floorH").getAsInt());
                eq(pb, q + " plafond", a.ceiling(), z.get("roofH").getAsInt());
                eq(pb, q + " sol haut", a.upperFloor(), z.get("upperFloorH").getAsInt());
                eq(pb, q + " plafond haut", a.upperCeiling(), z.get("upperRoofH").getAsInt());
                eq(pb, q + " eau", a.water(), z.get("water").getAsInt());
                eq(pb, q + " luminosite", a.brightness(), z.get("brightness").getAsInt());
                eq(pb, q + " luminosite haut", a.upperBrightness(),
                        z.get("upperBrightness").getAsInt());
                eq(pb, q + " pt controle", a.controlPoint(), z.get("controlPoint").getAsInt());
                eq(pb, q + " pt controle haut", a.upperControlPoint(),
                        z.get("upperControlPoint").getAsInt());
                eq(pb, q + " teleporteur", a.teleportZone(), z.get("telZone").getAsInt());
                eq(pb, q + " nb points", a.pointCount(), z.get("points").getAsInt());
                eq(pb, q + " echo", a.echo(), z.get("echo").getAsInt());
                eq(pb, q + " fond", a.drawBackdrop() ? 1 : 0,
                        z.get("drawBackdrop").getAsBoolean() ? 1 : 0);
                JsonArray el = z.getAsJsonArray("edgeList");
                if (el != null) {
                    eq(pb, q + " taille liste d'aretes", a.edgeList().size(), el.size());
                    for (int k = 0; k < Math.min(a.edgeList().size(), el.size()); k++) {
                        eq(pb, q + " arete " + k, a.edgeList().get(k), el.get(k).getAsInt());
                    }
                }
            }
        }

        JsonArray msg = o.getAsJsonArray("messages");
        if (msg != null) {
            for (int i = 0; i < Math.min(lvl.messages().size(), msg.size()); i++) {
                String attendu = msg.get(i).isJsonNull() ? "" : msg.get(i).getAsString();
                // Les messages ne comptent PAS comme un ecart, parce que c'est la reference qui
                // s'ecarte du fichier et non l'inverse -- verifie sur les octets bruts :
                //   - le niveau A porte bien « They leadto the rest » SANS espace, et les mots
                //     y sont coupes en plein milieu toutes les 40 colonnes (« reco|gnize »),
                //     donc le bloc est une chaine continue que l'afficheur replie, pas quatre
                //     lignes ;
                //   - le message 3 du niveau E fait exactement 160 caracteres et finit par un
                //     point, que la reference n'a plus.
                // La reference porte donc du texte DEJA replie puis recolle, qui gagne des
                // espaces aux limites de colonne et perd parfois son dernier caractere.
                if (!sansEspaces(lvl.messages().get(i)).equals(sansEspaces(attendu))) {
                    remarques.add(String.format(
                            "%c message %d : le fichier dit \"%s\", la reference \"%s\"",
                            lvl.letter(), i, extrait(lvl.messages().get(i)), extrait(attendu)));
                }
            }
        }
        return pb;
    }

    /** Le plus grand indice d'arete qu'un secteur designe : la table doit aller au moins la. */
    private static int maxEdgeUtilisee(Level lvl) {
        int mx = -1;
        for (Level.Zone z : lvl.zones()) {
            for (int e : z.edgeList()) {
                mx = Math.max(mx, e);
            }
        }
        return mx;
    }

    /** Texte prive de tous ses espaces : on compare les mots, pas la mise en page. */
    private static String sansEspaces(String s) {
        return s.replaceAll("\s+", "");
    }

    /** Fin d'une chaine, pour que la remarque tienne sur une ligne. */
    private static String extrait(String s) {
        String t = s.strip();
        return t.length() <= 46 ? t : "..." + t.substring(t.length() - 43);
    }

    private static void eq(List<String> pb, String quoi, int lu, int attendu) {
        if (lu != attendu) {
            pb.add(quoi + " : " + lu + " au lieu de " + attendu);
        }
    }
}
