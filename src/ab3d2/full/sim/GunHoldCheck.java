package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie OU l'arme en main se tient, a chaque image.
 *
 * <p>Ce qui est eprouve ici est la queue de {@code Plr1_Use} : l'angle, le secteur, le point,
 * le type, le drapeau d'animation, l'etage et surtout la HAUTEUR, dont la formule mele l'oeil,
 * le quart de la taille du joueur et une fois et demie le balancement de son pas.
 *
 * <p>Ce qui suit — l'apparence du pas d'animation, l'angle et la hauteur qu'il ajoute, le pas
 * suivant — est deja eprouve par {@code objAnimCheck} sur les trente definitions d'objets, dont
 * les huit que les dix armes font tenir.
 */
public final class GunHoldCheck {

    private GunHoldCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.gunhold", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=gunhold");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        Database glf = Data.database();
        Level lvl = Data.level('a');

        int ecarts = 0;
        // Le rang du point : le jeu reconnait l'arme a sa place dans la table, deux
        // enregistrements apres le joueur. L'oracle dit lesquels, et on le verifie.
        int pointJoueur = lvl.entities().get(lvl.playerEntity()).point();
        int pointArme = lvl.entities().get(lvl.weaponEntity()).point();
        ecarts += compare("le point du joueur", pointJoueur, root.get("playerEntity").getAsInt());

        JsonArray objets = root.getAsJsonArray("gunObjects");
        for (int i = 0; i < objets.size(); i++) {
            ecarts += compare("l'objet de l'arme " + i, glf.gun(i).gunObject(),
                    objets.get(i).getAsInt());
        }

        JsonArray cas = root.getAsJsonArray("cases");
        int animes = 0;
        int tirs = 0;
        // Un joueur du niveau A, dont on ne garde que la pose : l'oracle la seme entierement.
        PlayerSim joueur = World.of('a').player;
        Weapon arme = new Weapon();
        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            joueur.xOff = c.get("x").getAsInt() << 16;
            joueur.zOff = c.get("z").getAsInt() << 16;
            joueur.angPos = c.get("angle").getAsInt();
            joueur.snapYOff = c.get("eye").getAsInt();
            joueur.height = c.get("height").getAsInt();
            joueur.bobbleY = c.get("bobbleY").getAsInt();
            joueur.stoodInTop = c.get("upper").getAsInt() != 0;
            joueur.zone = c.get("playerZone").getAsInt();

            arme.place(joueur, glf, c.get("gun").getAsInt(), 100);

            String quoi = String.format("cas %d (arme %d, angle %d, oeil %d, taille %d,"
                    + " balancement %d)", i, c.get("gun").getAsInt(), c.get("angle").getAsInt(),
                    c.get("eye").getAsInt(), c.get("height").getAsInt(),
                    c.get("bobbleY").getAsInt());
            ecarts += compare(quoi + " : angle", arme.angle, c.get("outAngle").getAsInt());
            ecarts += compare(quoi + " : secteur", arme.zone, c.get("outZone").getAsInt());
            ecarts += compare(quoi + " : objet", arme.object, c.get("outType").getAsInt());
            ecarts += compare(quoi + " : type", arme.typeId, c.get("outTypeId").getAsInt());
            ecarts += compare(quoi + " : animation active", arme.active ? 0xFF : 0,
                    c.get("outWhichAnim").getAsInt());
            ecarts += compare(quoi + " : hauteur", arme.height, c.get("outHeight").getAsInt());
            ecarts += compare(quoi + " : etage", arme.upper ? 0xFF : 0,
                    c.get("outUpper").getAsInt());
            ecarts += compare(quoi + " : abscisse", arme.x, c.get("outPointX").getAsInt());
            ecarts += compare(quoi + " : ordonnee", arme.z, c.get("outPointZ").getAsInt());
            ecarts += compare(quoi + " : point", pointArme, c.get("outPoint").getAsInt());

            // Puis ce que GUNHELD fait de cet enregistrement, pour chaque pas d'animation.
            int angle0 = arme.angle;
            int hauteur0 = arme.height;
            JsonArray pas = c.getAsJsonArray("anim");
            for (int k = 0; k < pas.size(); k++) {
                JsonObject a = pas.get(k).getAsJsonObject();
                arme.angle = angle0;
                arme.height = hauteur0;
                arme.step = a.get("step").getAsInt();
                arme.animate(glf);
                String ou = quoi + String.format(", pas %d", a.get("step").getAsInt());
                ecarts += compare(ou + " : type de dessin", arme.gfxType,
                        a.get("gfxType").getAsInt());
                ecarts += compare(ou + " : angle", arme.angle, a.get("angle").getAsInt());
                ecarts += compare(ou + " : hauteur", arme.height, a.get("height").getAsInt());
                ecarts += compare(ou + " : pas suivant", arme.step, a.get("next").getAsInt());
                ab3d2.full.render.Appearance vu =
                        ab3d2.full.render.Appearance.of(arme.shown, arme.gfxType);
                ecarts += compare(ou + " : feuille", vu.sheet(), a.get("sheet").getAsInt());
                ecarts += compare(ou + " : drapeaux", vu.flags(), a.get("flags").getAsInt());
                ecarts += compare(ou + " : image", vu.frame(), a.get("frame").getAsInt());
                ecarts += compare(ou + " : demi-largeur", vu.halfWidth(),
                        a.get("halfWidth").getAsInt());
                ecarts += compare(ou + " : demi-hauteur", vu.halfDepth(),
                        a.get("halfDepth").getAsInt());
                animes++;
            }

            // Et le TIR : il saute au pas un, et l'image suivante doit montrer ce pas-la.
            JsonObject unPas = pas.get(1).getAsJsonObject();
            arme.angle = angle0;
            arme.height = hauteur0;
            arme.step = 0;
            arme.fire();
            arme.animate(glf);
            ecarts += compare(quoi + ", apres le tir : angle", arme.angle,
                    unPas.get("angle").getAsInt());
            ecarts += compare(quoi + ", apres le tir : hauteur", arme.height,
                    unPas.get("height").getAsInt());
            ecarts += compare(quoi + ", apres le tir : pas suivant", arme.step,
                    unPas.get("next").getAsInt());
            ecarts += compare(quoi + ", apres le tir : image",
                    ab3d2.full.render.Appearance.of(arme.shown, arme.gfxType).frame(),
                    unPas.get("frame").getAsInt());
            tirs++;
        }

        // Le joueur mort : le jeu LIBERE l'entite, et l'arme disparait de l'image.
        arme.place(joueur, glf, 0, 0);
        ecarts += compare("joueur mort : secteur", arme.zone, -1);

        System.out.printf("%n%d poses d'arme en main, %d pas d'animation, %d tirs, %d ecart(s) -> %s%n",
                cas.size(), animes, tirs, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int compte;

    private static int compare(String quoi, int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        if (compte < 15) {
            System.out.printf("    %s = %d, l'oracle dit %d%n", quoi, mien, oracle);
        }
        compte++;
        return 1;
    }
}
