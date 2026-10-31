package ab3d2.full;

import ab3d2.full.defs.Database;
import ab3d2.full.defs.DatabaseReader;

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
 * Verifie que la lecture directe de {@code test.lnk} donne le meme resultat que le portage.
 *
 * <p>Le corpus de reference est {@code glf.json}, extrait par le remake en relisant la memoire
 * du portage apres chargement. La base n'ayant ni en-tete ni offsets, tout repose sur des
 * constantes recopiees du source : si une seule etait fausse, la table correspondante et toutes
 * les suivantes partiraient de travers. La comparaison est donc faite champ par champ.
 */
public final class DefsCheck {

    private DefsCheck() {
    }

    private static final List<String> remarques = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path media = Path.of(System.getProperty("ab3d2.media", "medias/original"));
        Path ref = Path.of(System.getProperty("ab3d2.glf", ""));
        System.out.println("donnees : " + media);
        System.out.println("reference : " + ref);
        System.out.println();

        Database db = DatabaseReader.read(media);
        System.out.printf("  %d niveaux, %d textures murales, %d objets, %d monstres,"
                + " %d armes, %d projectiles%n",
                db.levels().size(), db.wallTextures().size(), db.objects().size(),
                db.aliens().size(), db.guns().size(), db.bullets().size());

        if (!Files.isRegularFile(ref)) {
            System.out.println("\n(pas de reference, lecture seule)");
            return;
        }
        JsonObject o;
        try (Reader r = Files.newBufferedReader(ref)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        }

        List<String> pb = new ArrayList<>();
        verifieNiveaux(pb, db, o.getAsJsonArray("levels"));
        verifieRessources(pb, "texture murale", db.wallTextures(), o.getAsJsonArray("wallTextures"));
        verifieRessources(pb, "feuille de sprites", db.spriteSheets(),
                o.getAsJsonArray("spriteSheets"));
        verifieRessources(pb, "modele", db.vectorModels(), o.getAsJsonArray("vectorModels"));
        verifieObjets(pb, db, o.getAsJsonArray("objects"));
        verifieMonstres(pb, db, o.getAsJsonArray("aliens"));
        verifieArmes(pb, db, o.getAsJsonArray("guns"));
        verifieTirs(pb, db, o.getAsJsonArray("alienShootDefs"));
        verifieProjectiles(pb, db, o.getAsJsonArray("bullets"));
        verifieSols(pb, db, o.getAsJsonArray("floorData"));

        JsonArray amb = o.getAsJsonArray("ambientSfx");
        if (amb != null) {
            eq(pb, "nb bruits d'ambiance", db.ambientSfx().size(), amb.size());
            for (int i = 0; i < Math.min(db.ambientSfx().size(), amb.size()); i++) {
                eq(pb, "bruit d'ambiance " + i, db.ambientSfx().get(i), amb.get(i).getAsInt());
            }
        }
        JsonObject pg = o.getAsJsonObject("playerGraphics");
        if (pg != null) {
            eq(pb, "graphisme joueur 1", db.player1Graphic(), pg.get("player1").getAsInt());
            eq(pb, "graphisme joueur 2", db.player2Graphic(), pg.get("player2").getAsInt());
        }

        System.out.println();
        for (String s : pb.subList(0, Math.min(pb.size(), 20))) {
            System.out.println("  " + s);
        }
        if (pb.size() > 20) {
            System.out.println("  ... et " + (pb.size() - 20) + " autres");
        }
        if (!remarques.isEmpty()) {
            System.out.println("\nRemarques (la reference s'ecarte du fichier, pas la lecture) :");
            for (String r : remarques) {
                System.out.println(r);
            }
        }
        System.out.printf("%nbase lue sans memoire plate, %d ecart(s) -> %s%n",
                pb.size(), pb.isEmpty() ? "PASS" : "FAIL");
        if (!pb.isEmpty()) {
            System.exit(1);
        }
    }

    private static void verifieNiveaux(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb niveaux", db.levels().size(), ref.size());
        int centres = 0;
        for (int i = 0; i < Math.min(db.levels().size(), ref.size()); i++) {
            Database.LevelInfo a = db.levels().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            // Le fichier CENTRE les noms de niveau, par des espaces de tete que la reference
            // rogne. On garde ce que le fichier porte, et on le signale une fois.
            if (!a.name().equals(a.name().stripLeading())) {
                centres++;
            }
            eqStr(pb, "niveau " + i + " nom", a.name().stripLeading(),
                    r.get("name").getAsString());
            eqStr(pb, "niveau " + i + " musique", a.music(), r.get("music").getAsString());
            eqStr(pb, "niveau " + i + " lettre", String.valueOf(a.letter()),
                    r.get("letter").getAsString());
        }
        if (centres > 0) {
            remarques.add(String.format(
                    "  %d noms de niveau sur %d sont CENTRES dans le fichier, par des espaces de"
                    + " tete que la reference rogne", centres, db.levels().size()));
        }
    }

    private static void verifieRessources(List<String> pb, String quoi,
            List<Database.Resource> notre, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb " + quoi, notre.size(), ref.size());
        for (int i = 0; i < Math.min(notre.size(), ref.size()); i++) {
            JsonObject r = ref.get(i).getAsJsonObject();
            eqStr(pb, quoi + " " + i + " fichier", notre.get(i).file(),
                    r.get("file").getAsString());
            eqStr(pb, quoi + " " + i + " nom", notre.get(i).name(), r.get("name").getAsString());
        }
    }

    private static void verifieObjets(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb objets", db.objects().size(), ref.size());
        for (int i = 0; i < Math.min(db.objects().size(), ref.size()); i++) {
            Database.ObjectDef a = db.objects().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "objet " + i;
            eqStr(pb, q + " nom", a.name(), r.get("name").getAsString());
            eq(pb, q + " conduite", a.behaviour(), r.get("behaviour").getAsInt());
            eq(pb, q + " type de graphisme", a.gfxType(), r.get("gfxType").getAsInt());
            eq(pb, q + " duree d'activation", a.activeTimeout(),
                    r.get("activeTimeout").getAsInt());
            eq(pb, q + " resistance", a.hitPoints(), r.get("hitPoints").getAsInt());
            eq(pb, q + " rayon", a.collideRadius(), r.get("collideRadius").getAsInt());
            eq(pb, q + " hauteur", a.collideHeight(), r.get("collideHeight").getAsInt());
            eq(pb, q + " sol ou plafond", a.floorCeiling(), r.get("floorCeiling").getAsInt());
            eq(pb, q + " accroche au mur", a.lockToWall(), r.get("lockToWall").getAsInt());
            eq(pb, q + " bruit", a.sfx(), r.get("sfx").getAsInt());
            entiers(pb, q + " munitions donnees", a.ammoGive(), r.getAsJsonArray("ammoGive"));
            entiers(pb, q + " armes donnees", a.gunGive(), r.getAsJsonArray("gunGive"));
            anim(pb, q + " animation de repos", a.idleAnim(), r.getAsJsonArray("defAnim"));
            anim(pb, q + " animation active", a.activeAnim(), r.getAsJsonArray("actAnim"));
        }
    }

    private static void verifieMonstres(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb monstres", db.aliens().size(), ref.size());
        for (int i = 0; i < Math.min(db.aliens().size(), ref.size()); i++) {
            Database.AlienDef a = db.aliens().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "monstre " + i;
            eqStr(pb, q + " nom", a.name(), r.get("name").getAsString());
            eq(pb, q + " type de graphisme", a.gfxType(), r.get("gfxType").getAsInt());
            eq(pb, q + " luminosite", a.bright(), r.get("bright").getAsInt());
            eq(pb, q + " resistance", a.hitPoints(), r.get("hitPoints").getAsInt());
            eq(pb, q + " hauteur", a.height(), r.get("height").getAsInt());
            eq(pb, q + " largeur", a.girth(), r.get("girth").getAsInt());
            eq(pb, q + " projectile", a.bulletType(), r.get("bulletType").getAsInt());
            eq(pb, q + " giclee", a.splatType(), r.get("splatType").getAsInt());
            eq(pb, q + " auxiliaire", a.auxiliary(), r.get("auxiliary").getAsInt());
            eq(pb, q + " temps de reaction", a.reactionTime(), r.get("reactionTime").getAsInt());
            eq(pb, q + " conduite par defaut", a.defaultBehaviour(),
                    r.get("defaultBehaviour").getAsInt());
            eq(pb, q + " vitesse par defaut", a.defaultSpeed(), r.get("defaultSpeed").getAsInt());
            eq(pb, q + " conduite en reponse", a.responseBehaviour(),
                    r.get("responseBehaviour").getAsInt());
            eq(pb, q + " vitesse en reponse", a.responseSpeed(),
                    r.get("responseSpeed").getAsInt());
            eq(pb, q + " duree de reponse", a.responseTimeout(),
                    r.get("responseTimeout").getAsInt());
            eq(pb, q + " conduite en poursuite", a.followupBehaviour(),
                    r.get("followupBehaviour").getAsInt());
            eq(pb, q + " vitesse en poursuite", a.followupSpeed(),
                    r.get("followupSpeed").getAsInt());
            eq(pb, q + " duree de poursuite", a.followupTimeout(),
                    r.get("followupTimeout").getAsInt());
            eq(pb, q + " conduite en retraite", a.retreatBehaviour(),
                    r.get("retreatBehaviour").getAsInt());
            eq(pb, q + " vitesse en retraite", a.retreatSpeed(),
                    r.get("retreatSpeed").getAsInt());
            eq(pb, q + " duree de retraite", a.retreatTimeout(),
                    r.get("retreatTimeout").getAsInt());
            eq(pb, q + " degats pour fuir", a.damageToRetreat(),
                    r.get("damageToRetreat").getAsInt());
            eq(pb, q + " degats pour poursuivre", a.damageToFollowup(),
                    r.get("damageToFollowup").getAsInt());

            JsonArray opts = r.getAsJsonArray("anims");
            if (opts == null) {
                continue;
            }
            eq(pb, q + " nb options d'animation", a.anims().size(), opts.size());
            for (int k = 0; k < Math.min(a.anims().size(), opts.size()); k++) {
                List<Database.AlienFrame> mine = a.anims().get(k);
                JsonArray fr = opts.get(k).getAsJsonArray();
                eq(pb, q + " option " + k + " nb images", mine.size(), fr.size());
                for (int n = 0; n < Math.min(mine.size(), fr.size()); n++) {
                    Database.AlienFrame im = mine.get(n);
                    JsonObject rf = fr.get(n).getAsJsonObject();
                    String f = q + " option " + k + " image " + n;
                    eq(pb, f + " graphisme", im.gfx(), rf.get("gfx").getAsInt());
                    eq(pb, f + " numero", im.frame(), rf.get("frame").getAsInt());
                    eq(pb, f + " mot2", im.word2(), rf.get("word2").getAsInt());
                    eq(pb, f + " bruit", im.sfx(), rf.get("sfx").getAsInt());
                    eq(pb, f + " action", im.action(), rf.get("action").getAsInt());
                    eq(pb, f + " special", im.special(), rf.get("special").getAsInt());
                    eq(pb, f + " auxiliaire", im.aux(), rf.get("aux").getAsInt());
                    eq(pb, f + " auxiliaire X", im.auxX(), rf.get("auxX").getAsInt());
                    eq(pb, f + " auxiliaire Y", im.auxY(), rf.get("auxY").getAsInt());
                }
            }
        }
    }

    private static void verifieArmes(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb armes", db.guns().size(), ref.size());
        for (int i = 0; i < Math.min(db.guns().size(), ref.size()); i++) {
            Database.Gun a = db.guns().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "arme " + i;
            eqStr(pb, q + " nom", a.name(), r.get("name").getAsString());
            eq(pb, q + " projectile", a.shoot().bulletType(), r.get("bulletType").getAsInt());
            eq(pb, q + " delai", a.shoot().delay(), r.get("delay").getAsInt());
            eq(pb, q + " nb projectiles", a.shoot().bulletCount(),
                    r.get("bulletCount").getAsInt());
            eq(pb, q + " bruit", a.shoot().sfx(), r.get("sfx").getAsInt());
            eq(pb, q + " objet en main", a.gunObject(), r.get("gunObject").getAsInt());
        }
    }

    private static void verifieTirs(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb tirs de monstre", db.alienShots().size(), ref.size());
        for (int i = 0; i < Math.min(db.alienShots().size(), ref.size()); i++) {
            Database.Shoot a = db.alienShots().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "tir de monstre " + i;
            eq(pb, q + " projectile", a.bulletType(), r.get("bulletType").getAsInt());
            eq(pb, q + " delai", a.delay(), r.get("delay").getAsInt());
            eq(pb, q + " nb projectiles", a.bulletCount(), r.get("bulletCount").getAsInt());
            eq(pb, q + " bruit", a.sfx(), r.get("sfx").getAsInt());
        }
    }

    private static void verifieProjectiles(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb projectiles", db.bullets().size(), ref.size());
        for (int i = 0; i < Math.min(db.bullets().size(), ref.size()); i++) {
            Database.BulletDef a = db.bullets().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            String q = "projectile " + i;
            eqStr(pb, q + " nom", a.name(), r.get("name").getAsString());
            eq(pb, q + " instantane", a.hitScan(), r.get("hitScan").getAsInt());
            eq(pb, q + " pesanteur", a.gravity(), r.get("gravity").getAsInt());
            eq(pb, q + " duree de vie", a.lifetime(), r.get("lifetime").getAsInt());
            eq(pb, q + " munitions par chargeur", a.ammoInClip(), r.get("ammoInClip").getAsInt());
            eq(pb, q + " rebond horizontal", a.bounceHoriz(), r.get("bounceHoriz").getAsInt());
            eq(pb, q + " rebond vertical", a.bounceVert(), r.get("bounceVert").getAsInt());
            eq(pb, q + " degats", a.hitDamage(), r.get("hitDamage").getAsInt());
            eq(pb, q + " force explosive", a.explosiveForce(),
                    r.get("explosiveForce").getAsInt());
            eq(pb, q + " vitesse", a.speed(), r.get("speed").getAsInt());
            eq(pb, q + " nb images de vol", a.animFrames(), r.get("animFrames").getAsInt());
            eq(pb, q + " nb images d'impact", a.popFrames(), r.get("popFrames").getAsInt());
            eq(pb, q + " bruit d'impact", a.impactSfx(), r.get("impactSFX").getAsInt());
            eq(pb, q + " type de graphisme", a.graphicType(), r.get("graphicType").getAsInt());
            eq(pb, q + " type de graphisme d'impact", a.impactGraphicType(),
                    r.get("impactGraphicType").getAsInt());
            anim(pb, q + " animation de vol", a.animData(), r.getAsJsonArray("animData"));
            anim(pb, q + " animation d'impact", a.popData(), r.getAsJsonArray("popData"));
        }
    }

    private static void verifieSols(List<String> pb, Database db, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, "nb types de sol", db.floors().size(), ref.size());
        for (int i = 0; i < Math.min(db.floors().size(), ref.size()); i++) {
            Database.FloorType a = db.floors().get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            eq(pb, "sol " + i + " degats", a.damage(), r.get("damage").getAsInt());
            eq(pb, "sol " + i + " bruit", a.sfx(), r.get("sfx").getAsInt());
        }
    }

    /** Un script d'animation, pas a pas. */
    private static void anim(List<String> pb, String quoi, List<Database.AnimStep> notre,
            JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, quoi + " nb pas", notre.size(), ref.size());
        for (int i = 0; i < Math.min(notre.size(), ref.size()); i++) {
            Database.AnimStep a = notre.get(i);
            JsonObject r = ref.get(i).getAsJsonObject();
            eq(pb, quoi + " pas " + i + " graphisme", a.gfx(), r.get("gfx").getAsInt());
            eq(pb, quoi + " pas " + i + " numero", a.frame(), r.get("frame").getAsInt());
            eq(pb, quoi + " pas " + i + " mot2", a.word2(), r.get("word2").getAsInt());
            eq(pb, quoi + " pas " + i + " delta", a.delta(), r.get("delta").getAsInt());
            eq(pb, quoi + " pas " + i + " suivant", a.next(), r.get("next").getAsInt());
        }
    }

    private static void entiers(List<String> pb, String quoi, List<Integer> notre, JsonArray ref) {
        if (ref == null) {
            return;
        }
        eq(pb, quoi + " taille", notre.size(), ref.size());
        for (int i = 0; i < Math.min(notre.size(), ref.size()); i++) {
            eq(pb, quoi + " " + i, notre.get(i), ref.get(i).getAsInt());
        }
    }

    private static void eq(List<String> pb, String quoi, int notre, int attendu) {
        if (notre != attendu) {
            pb.add(quoi + " : " + notre + " au lieu de " + attendu);
        }
    }

    private static void eqStr(List<String> pb, String quoi, String notre, String attendu) {
        if (!notre.equals(attendu)) {
            pb.add(quoi + " : \"" + notre + "\" au lieu de \"" + attendu + "\"");
        }
    }
}
