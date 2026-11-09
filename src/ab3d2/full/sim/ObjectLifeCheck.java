package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;
import ab3d2.full.render.Appearance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifie la VIE DES OBJETS : ce que la boucle fait, image apres image, de chaque chose posee
 * dans le niveau.
 *
 * <p>Jusqu'ici le portage savait ce qu'un PAS d'animation donne a voir ({@code objAnimCheck})
 * mais personne ne faisait avancer ces pas : toutes les choses du jeu restaient figees sur leur
 * premiere image, et les trois familles qui ne sont pas du decor n'etaient eprouvees que par
 * leurs effets de bord. Celle-ci rejoue six niveaux pendant deux cent quarante images et
 * compare, pour chaque chose et a chaque image, les douze mots de son enregistrement.
 *
 * <p>Le DESTRUCTIBLE y reçoit une echelle de degats POSEE — le seuil moins un, le seuil, le
 * retour a zero, le maximum d'un octet, le seuil plus un — qui le casse, le fait revivre et le
 * recasse. C'est la seule facon d'eprouver les deux scripts d'animation d'une meme chose, et le
 * seul endroit du jeu ou un verrou de porte s'OUVRE en cours de partie sans qu'on ait rien
 * ramasse.
 */
public final class ObjectLifeCheck {

    private static int compte;

    private ObjectLifeCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.objectlife", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=objectlife");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        int ecarts = 0;
        int etats = 0;
        for (var el : root.getAsJsonArray("levels")) {
            JsonObject n = el.getAsJsonObject();
            String nom = n.get("level").getAsString();
            for (String quelle : new String[] {"idle", "contact", "full"}) {
                JsonObject serie = n.getAsJsonObject(quelle);
                etats += serie.getAsJsonArray("frames").size()
                        * serie.getAsJsonArray("entities").size();
                ecarts += serie(nom + "/" + quelle, nom.charAt(0), serie);
            }
        }
        System.out.printf("%n%d etats d'objet sur six niveaux et trois series, %d ecart(s)"
                + " -> %s%n", etats, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int serie(String nom, char lettre, JsonObject n) {
        Level data = Data.level(lettre);
        Database glf = Data.database();
        LevelSim lvl = LevelSim.of(data);
        ObjectRuntime objets = new ObjectRuntime(lvl, data, glf);
        // L'inventaire de DEPART, tel que l'oracle l'a releve : la partie neuve pour les deux
        // premieres series, pousse a ses plafonds pour la troisieme.
        objets.inventory.defaultGame(glf);
        JsonObject d0 = n.getAsJsonObject("startInventory");
        var c0 = d0.getAsJsonArray("consumables");
        for (int i = 0; i < c0.size(); i++) {
            objets.inventory.consumables[i] = c0.get(i).getAsInt();
        }
        var i0 = d0.getAsJsonArray("items");
        for (int i = 0; i < i0.size(); i++) {
            objets.inventory.items[i] = i0.get(i).getAsInt();
        }

        // Le joueur est POSE a chaque image d'apres ce que l'oracle a releve : hors de toute
        // zone dans la premiere serie, sur une chose a tour de role dans la seconde.
        PlayerSim joueur = new PlayerSim(lvl, data, Data.sinCos());

        JsonArray entites = n.getAsJsonArray("entities");
        List<ObjectRuntime.Thing> miennes = objets.things();
        int ecarts = 0;
        if (miennes.size() != entites.size()) {
            System.out.printf("    %s : %d choses, l'oracle en compte %d%n",
                    nom, miennes.size(), entites.size());
            return 1;
        }
        for (int i = 0; i < entites.size(); i++) {
            JsonObject e = entites.get(i).getAsJsonObject();
            ObjectRuntime.Thing t = miennes.get(i);
            ecarts += eq(nom + ", chose " + i + " : rang", t.rank, e.get("rank").getAsInt());
            ecarts += eq(nom + ", chose " + i + " : definition", t.entity.def(),
                    e.get("def").getAsInt());
            JsonObject d = e.getAsJsonObject("start");
            ecarts += eq(nom + ", chose " + i + " au depart : secteur", t.zone,
                    d.get("zone").getAsInt());
            ecarts += eq(nom + ", chose " + i + " au depart : pas", t.animStep,
                    d.get("timer1").getAsInt());
            ecarts += eq(nom + ", chose " + i + " au depart : angle", t.angle & 0xFFFF,
                    d.get("angle").getAsInt());
            ecarts += eq(nom + ", chose " + i + " au depart : hauteur", t.height & 0xFFFF,
                    d.get("height").getAsInt());
            ecarts += eq(nom + ", chose " + i + " au depart : vie", t.hitPoints,
                    d.get("hp").getAsInt());
            ecarts += eq(nom + ", chose " + i + " au depart : degats", t.damageTaken,
                    d.get("damage").getAsInt());
            ecarts += eq(nom + ", chose " + i + " au depart : enclenche",
                    t.activated ? 0xFF : 0, d.get("whichAnim").getAsInt());
        }

        // Les degats POSES, ranges par image puis par rang : le meme calendrier que l'oracle.
        JsonArray poses = n.getAsJsonArray("damage");
        JsonArray images = n.getAsJsonArray("frames");
        int p = 0;
        for (int f = 0; f < images.size(); f++) {
            while (p < poses.size() && poses.get(p).getAsJsonObject().get("frame").getAsInt() == f) {
                JsonObject un = poses.get(p).getAsJsonObject();
                int rang = un.get("rank").getAsInt();
                for (ObjectRuntime.Thing t : miennes) {
                    if (t.rank == rang) {
                        t.damageTaken = un.get("value").getAsInt();
                        break;
                    }
                }
                p++;
            }
            JsonObject img = images.get(f).getAsJsonObject();
            JsonObject j = img.getAsJsonObject("player");
            joueur.zone = j.get("zone").getAsInt();
            joueur.stoodInTop = j.get("upper").getAsInt() != 0;
            joueur.xOff = j.get("x").getAsInt();
            joueur.zOff = j.get("z").getAsInt();
            joueur.yOff = j.get("y").getAsInt();
            joueur.height = j.get("height").getAsInt();
            int verrous = objets.run(joueur, j.get("action").getAsBoolean());

            ecarts += eq(nom + ", image " + f + " : verrous", verrous,
                    img.get("locks").getAsInt());
            ecarts += inventaire(nom, f, objets.inventory, img.getAsJsonObject("inventory"));
            JsonArray etats = img.getAsJsonArray("objects");
            for (int i = 0; i < miennes.size(); i++) {
                ecarts += uneChose(nom, f, i, miennes.get(i), glf,
                        etats.get(i).getAsJsonObject());
            }
        }
        return ecarts;
    }

    /**
     * L'inventaire : les vingt-deux consommables et les douze objets.
     *
     * <p>C'est lui qui dit si un ramassage a REUSSI. Une chose qui ne donne rien se ramasse
     * toujours ; une qui donne un consommable deja au plafond reste au sol, et le jeu le dit
     * au joueur.
     */
    private static int inventaire(String nom, int f, Inventory mien, JsonObject o) {
        int ecarts = 0;
        var cons = o.getAsJsonArray("consumables");
        for (int i = 0; i < cons.size(); i++) {
            ecarts += eq(nom + ", image " + f + " : consommable " + i,
                    mien.consumables[i], cons.get(i).getAsInt());
        }
        var objs = o.getAsJsonArray("items");
        for (int i = 0; i < objs.size(); i++) {
            ecarts += eq(nom + ", image " + f + " : objet " + i,
                    mien.items[i], objs.get(i).getAsInt());
        }
        return ecarts;
    }

    /** Les douze mots de l'enregistrement d'une chose, compares un a un. */
    private static int uneChose(String nom, int f, int i, ObjectRuntime.Thing t, Database glf,
            JsonObject o) {
        String quoi = nom + ", image " + f + ", chose " + i + " : ";
        int ecarts = 0;
        ecarts += eq(quoi + "secteur", t.zone, o.get("zone").getAsInt());
        ecarts += eq(quoi + "secteur d'entite", t.entZone, o.get("entZone").getAsInt());
        ecarts += eq(quoi + "vie", t.hitPoints, o.get("hp").getAsInt());
        ecarts += eq(quoi + "degats", t.damageTaken, o.get("damage").getAsInt());
        ecarts += eq(quoi + "pas suivant", t.animStep, o.get("timer1").getAsInt());
        ecarts += eq(quoi + "compteur d'activation", t.activeTimer & 0xFFFF,
                o.get("timer2").getAsInt());
        ecarts += eq(quoi + "enclenche", t.activated ? 0xFF : 0, o.get("whichAnim").getAsInt());
        ecarts += eq(quoi + "hauteur", t.height & 0xFFFF, o.get("height").getAsInt());
        ecarts += eq(quoi + "angle", t.angle & 0xFFFF, o.get("angle").getAsInt());

        // Ce que l'animation vient d'ecrire dans les champs de dessin : la taille au mot six,
        // la feuille et l'image aux octets huit a onze.
        Database.ObjectDef def = glf.object(t.entity.def());
        List<Database.AnimStep> script = t.shownActive ? def.activeAnim() : def.idleAnim();
        Database.AnimStep pas = script.get(Math.floorMod(t.shownStep, script.size()));
        Appearance vu = Appearance.of(pas, def.gfxType());
        int taille = ((vu.halfWidth() & 0xFF) << 8) | (vu.halfDepth() & 0xFF);
        ecarts += eq(quoi + "demi-tailles", taille, o.get("size").getAsInt());
        // move.l #0,8(a0) efface les quatre octets ; seuls le mot de feuille et l'octet onze
        // sont ensuite ecrits, l'octet dix des choses reste donc nul.
        int gfx = ((vu.sheet() & 0xFFFF) << 16) | (vu.frame() & 0xFF);
        ecarts += eq(quoi + "champs de dessin", gfx, o.get("gfx").getAsInt());
        return ecarts;
    }

    private static int eq(String quoi, int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        if (compte < 20) {
            System.out.printf("    %s = %d, l'oracle dit %d%n", quoi, mien, oracle);
        }
        compte++;
        return 1;
    }
}
