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
            System.out.printf("  %c : %4d aretes, %3d secteurs, %2d portes, %d ascenseurs,"
                    + " %4d entites   %s%n",
                    c, lvl.edges().size(), lvl.zones().size(), lvl.doors().size(),
                    lvl.lifts().size(), lvl.entities().size(),
                    pb.isEmpty() ? "conforme" : pb.size() + " ECART(S)");
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
            if (e.has("angle")) {
                eq(pb, "entite " + i + " angle", a.angle(), e.get("angle").getAsInt());
            }
            // La reference ne releve l'etat de depart que pour les monstres.
            if (e.has("hitPoints")) {
                eq(pb, "entite " + i + " vie", a.hitPoints(), e.get("hitPoints").getAsInt());
                eq(pb, "entite " + i + " equipe", a.team(), e.get("team").getAsInt());
                eq(pb, "entite " + i + " mode", a.mode(), e.get("mode").getAsInt());
                eq(pb, "entite " + i + " pt controle", a.controlPoint(),
                        e.get("controlPoint").getAsInt());
                eq(pb, "entite " + i + " pt controle vise", a.targetControlPoint(),
                        e.get("targetControlPoint").getAsInt());
                eq(pb, "entite " + i + " hauteur", a.height(), e.get("height").getAsInt());
            }
            eq(pb, "entite " + i + " message", a.message(), e.get("displayText").getAsInt());
            eq(pb, "entite " + i + " etage", a.upperZone(),
                    e.get("upperZone").getAsInt());
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
                eq(pb, q + " bruits d'ambiance", a.ambientSoundMask(),
                        z.get("backSfxMask").getAsInt());
                eq(pb, q + " bruits d'ambiance haut", a.upperAmbientSoundMask(),
                        z.get("upperBackSfxMask").getAsInt());
                listeEntiers(pb, q + " points de bord", a.borderPoints(),
                        z.getAsJsonArray("borderPointsRaw"));
                listeEntiers(pb, q + " luminosites de points", a.pointBrights(),
                        z.getAsJsonArray("pointBrightsRaw"));
                eq(pb, q + " fond", a.drawBackdrop() ? 1 : 0,
                        z.get("drawBackdrop").getAsBoolean() ? 1 : 0);
                JsonArray pv = z.getAsJsonArray("pvs");
                if (pv != null) {
                    eq(pb, q + " nb secteurs visibles", a.visible().size(), pv.size());
                    for (int k = 0; k < Math.min(a.visible().size(), pv.size()); k++) {
                        JsonObject v = pv.get(k).getAsJsonObject();
                        eq(pb, q + " visible " + k, a.visible().get(k).zone(),
                                v.get("zone").getAsInt());
                        // La reference garde le rang du groupe, en mots ; nous gardons le
                        // groupe lui-meme, qui porte ce rang.
                        Level.Clip g = a.visible().get(k).clip();
                        eq(pb, q + " decoupe " + k, g == null ? -1 : g.wordIndex(),
                                v.get("clip").getAsInt());
                    }
                }
                JsonArray el = z.getAsJsonArray("edgeList");
                if (el != null) {
                    eq(pb, q + " taille liste d'aretes", a.edgeList().size(), el.size());
                    for (int k = 0; k < Math.min(a.edgeList().size(), el.size()); k++) {
                        eq(pb, q + " arete " + k, a.edgeList().get(k), el.get(k).getAsInt());
                    }
                }
            }
        }

        // --- les aretes ---
        JsonArray ed = o.getAsJsonArray("edges");
        if (ed != null) {
            for (int i = 0; i < Math.min(lvl.edges().size(), ed.size()); i++) {
                Level.Edge a = lvl.edges().get(i);
                JsonObject r = ed.get(i).getAsJsonObject();
                String q = "arete " + i;
                eq(pb, q + " x", a.x(), r.get("x").getAsInt());
                eq(pb, q + " z", a.z(), r.get("z").getAsInt());
                eq(pb, q + " dx", a.dx(), r.get("dx").getAsInt());
                eq(pb, q + " dz", a.dz(), r.get("dz").getAsInt());
                eq(pb, q + " secteur joint", a.joinZone(), r.get("join").getAsInt());
                eq(pb, q + " diviseur", a.divider(), r.get("w5").getAsInt());
                eq(pb, q + " normale X", a.normalX(), r.get("b12").getAsInt());
                eq(pb, q + " normale Z", a.normalZ(), r.get("b13").getAsInt());
            }
        }

        // --- le routage des monstres, a pied et en vol ---
        tableRoutage(pb, "a pied", lvl.walkRoutes(), o.getAsJsonArray("walkLinks"));
        tableRoutage(pb, "en vol", lvl.flyRoutes(), o.getAsJsonArray("flyLinks"));

        // --- l'habillage : murs et surfaces horizontales ---
        verifieMurs(pb, lvl, o.getAsJsonArray("walls"));
        verifieSurfaces(pb, lvl, o.getAsJsonArray("flats"));

        // --- les groupes de decoupe, confrontes a la table brute ---
        JsonArray cl = o.getAsJsonArray("clips");
        if (cl != null) {
            for (Level.Clip g : lvl.clips()) {
                int at = g.wordIndex();
                List<Integer> mots = new ArrayList<>();
                for (int v : g.left()) {
                    mots.add(v);
                }
                mots.add(-1);
                for (int v : g.right()) {
                    mots.add(v);
                }
                mots.add(-2);
                for (int k = 0; k < mots.size(); k++) {
                    if (at + k >= cl.size()) {
                        pb.add("decoupe en mot " + at + " : depasse la table de reference");
                        break;
                    }
                    eq(pb, "decoupe en mot " + at + ", mot " + k,
                            mots.get(k), cl.get(at + k).getAsInt());
                }
            }
        }

        // --- portes et ascenseurs ---
        verifieLiftables(pb, "porte", lvl.doors(), o.getAsJsonArray("doors"));
        verifieLiftables(pb, "ascenseur", lvl.lifts(), o.getAsJsonArray("lifts"));

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

    /** Portes et ascenseurs ont le meme format : une seule verification pour les deux. */
    private static void verifieLiftables(List<String> pb, String quoi,
            List<Level.Liftable> lus, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nombre de " + quoi + "s", lus.size(), ref.size());
        for (int i = 0; i < Math.min(lus.size(), ref.size()); i++) {
            JsonObject r = ref.get(i).getAsJsonObject();
            Level.Liftable a = lus.get(i);
            String q = quoi + " " + i;
            eq(pb, q + " bas", a.bottom(), r.get("bottom").getAsInt());
            eq(pb, q + " haut", a.top(), r.get("top").getAsInt());
            eq(pb, q + " vitesse ouverture", a.openSpeed(), r.get("openingSpeed").getAsInt());
            eq(pb, q + " vitesse fermeture", a.closeSpeed(), r.get("closingSpeed").getAsInt());
            eq(pb, q + " duree ouverte", a.openDuration(), r.get("openDuration").getAsInt());
            eq(pb, q + " x", a.x(), r.get("x").getAsInt());
            eq(pb, q + " z", a.z(), r.get("z").getAsInt());
            eq(pb, q + " position", a.position(), r.get("position").getAsInt());
            eq(pb, q + " secteur", a.zone(), r.get("zone").getAsInt());
            eq(pb, q + " condition ouverture", a.openBits(), r.get("openBits").getAsInt());
            eq(pb, q + " condition fermeture", a.closeBits(), r.get("closeBits").getAsInt());
            JsonArray w = r.getAsJsonArray("wallPatches");
            if (w != null) {
                eq(pb, q + " nb murs", a.walls().size(), w.size());
                for (int k = 0; k < Math.min(a.walls().size(), w.size()); k++) {
                    JsonObject rw = w.get(k).getAsJsonObject();
                    eq(pb, q + " mur " + k + " arete", a.walls().get(k).edge(),
                            rw.get("edge").getAsInt());
                    eq(pb, q + " mur " + k + " graphique", a.walls().get(k).graphics(),
                            rw.get("gfxOfs").getAsInt());
                    eq(pb, q + " mur " + k + " texture", a.walls().get(k).textureTop(),
                            rw.get("yBase").getAsInt());
                }
            }
        }
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

    /**
     * Les murs, confrontes un a un dans l'ordre ou le programme d'habillage les dessine.
     *
     * <p>La reference les a releves en parcourant les secteurs puis, pour chacun, l'etage bas
     * avant l'etage haut. On refait le meme parcours, et {@code gfxOfs} sert de garde-fou : si
     * un seul enregistrement etait mal dimensionne, tous les offsets suivants glisseraient.
     */
    private static void verifieMurs(List<String> pb, Level lvl, JsonArray ref) {
        if (ref == null) {
            return;
        }
        List<Level.Wall> murs = new ArrayList<>();
        List<Boolean> hauts = new ArrayList<>();
        for (Level.Zone z : lvl.zones()) {
            for (Level.Graphics g : etages(z)) {
                for (Level.Paint p : g.program()) {
                    if (p instanceof Level.Wall w) {
                        murs.add(w);
                        hauts.add(g == z.upper());
                    }
                }
            }
        }
        eq(pb, "nb murs", murs.size(), ref.size());
        for (int i = 0; i < Math.min(murs.size(), ref.size()); i++) {
            Level.Wall w = murs.get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "mur " + i;
            eq(pb, q + " offset", w.offset(), r.get("gfxOfs").getAsInt());
            eq(pb, q + " etage haut", hauts.get(i) ? 1 : 0,
                    r.get("upper").getAsBoolean() ? 1 : 0);
            // La reference ramene a -1 les murs absents de la carte, la ou le moteur se
            // contente de voir l'octet haut negatif et de ne rien tracer.
            eq(pb, q + " emplacement carte", w.onMap() ? w.mapSlot() : -1,
                    r.get("mapSlot").getAsInt());
            eq(pb, q + " porte sur la carte", w.onMap() && w.mapDoor() ? 1 : 0,
                    r.get("mapDoor").getAsBoolean() ? 1 : 0);
            eq(pb, q + " point gauche", w.leftPoint(), r.get("leftPt").getAsInt());
            eq(pb, q + " point droit", w.rightPoint(), r.get("rightPt").getAsInt());
            eq(pb, q + " lumiere gauche", w.leftSlot(), r.get("whichLeft").getAsInt());
            eq(pb, q + " lumiere droite", w.rightSlot(), r.get("whichRight").getAsInt());
            eq(pb, q + " longueur", w.length(), r.get("wallLen").getAsInt());
            eq(pb, q + " tuile de depart", w.textureX(), r.get("fromTile").getAsInt());
            eq(pb, q + " decalage vertical", w.textureY(), r.get("yOffset").getAsInt());
            eq(pb, q + " texture", w.texture(), r.get("texIndex").getAsInt());
            eq(pb, q + " masque hauteur", w.heightMask(), r.get("heightMask").getAsInt());
            eq(pb, q + " masque largeur", w.widthMask(), r.get("widthMask").getAsInt());
            eq(pb, q + " rangee lumiere", w.brightRow(), r.get("whichPBR").getAsInt());
            eq(pb, q + " ecart lumiere", w.brightOffset(), r.get("brightOffset").getAsInt());
            eq(pb, q + " haut", w.topWorld(), r.get("top").getAsInt());
            eq(pb, q + " bas", w.bottomWorld(), r.get("bottom").getAsInt());
            eq(pb, q + " autre secteur", w.otherZone(), r.get("otherZone").getAsInt());
        }
    }

    /** Les sols, plafonds et plans d'eau, dans le meme ordre de parcours que les murs. */
    private static void verifieSurfaces(List<String> pb, Level lvl, JsonArray ref) {
        if (ref == null) {
            return;
        }
        int manquants = 0;
        List<Level.Flat> plats = new ArrayList<>();
        for (Level.Zone z : lvl.zones()) {
            for (Level.Graphics g : etages(z)) {
                for (Level.Paint p : g.program()) {
                    if (p instanceof Level.Flat fl) {
                        plats.add(fl);
                    }
                }
            }
        }
        eq(pb, "nb surfaces", plats.size(), ref.size());
        for (int i = 0; i < Math.min(plats.size(), ref.size()); i++) {
            Level.Flat fl = plats.get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "surface " + i;
            eq(pb, q + " hauteur", fl.y(), r.get("y").getAsInt());
            eq(pb, q + " tuile", fl.tileIndex(), r.get("tile").getAsInt());
            JsonArray pts = r.getAsJsonArray("points");
            // La reference s'arrete un point trop tot : elle prend le compteur pour le nombre
            // de sommets alors que la boucle de rendu en parcourt un de plus. Sa propre liste
            // de points de bord la dement -- le secteur 0 du niveau A y porte [3,2,4,5] tandis
            // que sa surface n'a que [3,2,4]. On compte donc l'ecart comme une remarque.
            if (fl.corners().size() != pts.size()) {
                manquants++;
            }
            for (int k = 0; k < Math.min(fl.corners().size(), pts.size()); k++) {
                eq(pb, q + " point " + k, fl.corners().get(k).point(), pts.get(k).getAsInt());
            }
            JsonArray slots = r.getAsJsonArray("pointSlots");
            if (slots != null) {
                for (int k = 0; k < Math.min(fl.corners().size(), slots.size()); k++) {
                    eq(pb, q + " lumiere " + k, fl.corners().get(k).slot(),
                            slots.get(k).getAsInt());
                }
            }
        }
        if (manquants > 0) {
            remarques.add(String.format(
                    "  %c : %d surfaces sur %d ont un sommet de plus que la reference"
                    + " -- elle s'arrete au compteur, le rendu va un cran plus loin",
                    lvl.letter(), manquants, plats.size()));
        }
    }

    /** Les etages d'un secteur qui ont un habillage, le bas puis le haut. */
    private static List<Level.Graphics> etages(Level.Zone z) {
        List<Level.Graphics> out = new ArrayList<>(2);
        if (z.lower() != null) {
            out.add(z.lower());
        }
        if (z.upper() != null) {
            out.add(z.upper());
        }
        return out;
    }

    /** Une table de routage, octet par octet. */
    private static void tableRoutage(List<String> pb, String quoi, int[] notre, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "taille du routage " + quoi, notre.length, ref.size());
        for (int i = 0; i < Math.min(notre.length, ref.size()); i++) {
            if (notre[i] != ref.get(i).getAsInt()) {
                pb.add(String.format("routage %s, de %d vers %d : %d au lieu de %d",
                        quoi, i / Level.ROUTE_STRIDE, i % Level.ROUTE_STRIDE,
                        notre[i], ref.get(i).getAsInt()));
                return;                                // une suffit : le reste glisserait
            }
        }
    }

    /** Une liste d'entiers, element par element. */
    private static void listeEntiers(List<String> pb, String quoi, List<Integer> notre,
            JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, quoi + " taille", notre.size(), ref.size());
        for (int i = 0; i < Math.min(notre.size(), ref.size()); i++) {
            eq(pb, quoi + " " + i, notre.get(i), ref.get(i).getAsInt());
        }
    }
}
