package ab3d2.full;

import ab3d2.full.level.Level;
import ab3d2.full.render.Bitmap;
import ab3d2.full.render.ObjectView;
import ab3d2.full.render.ScaleTable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifie la POSE DU SPRITE : ou l'image se pose, a quelle echelle, par quel bout.
 *
 * <p>Quatorze valeurs par sprite pose — le coin, la taille apres rognage, la bande de depart et
 * son pas, le depart dans la colonne de texture et son pas, la rampe de couleurs, les quatre
 * drapeaux, et la luminosite retenue avant qu'elle ne soit bornee.
 *
 * <p>Un sprite que la routine ecarte ne donne rien du tout : son ABSENCE est verifiee comme le
 * reste, et c'est la moitie du travail — il y a plus de facons d'ecarter un sprite que de le
 * poser.
 *
 * <p>Les champs de dessin sont SEMES par l'oracle et relus ici : au repos ils sont tous nuls et
 * rien ne se dessinerait. Voir l'oracle pour ce que la semence parcourt.
 */
public final class BitmapCheck {

    private BitmapCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.bitmap", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=bitmap");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonObject champs = root.getAsJsonObject("objects");
        JsonArray cas = root.getAsJsonArray("cases");

        ObjectView view = new ObjectView(Data.sinCos());
        Bitmap bitmap = new Bitmap(Data.database(), new ScaleTable());
        int ecarts = 0;
        int poses = 0;
        int ecartes = 0;
        int horsTable = 0;
        int horsEchelle = 0;
        char courant = 0;
        int ecartsNiveau = 0;

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
            }
            Level lvl = Data.level(lettre);
            JsonArray objets = champs.getAsJsonArray(String.valueOf(lettre));
            JsonArray attendus = c.getAsJsonArray("placed");
            boolean plein = c.get("full").getAsBoolean();

            List<ObjectView.Thing> choses = new ArrayList<>(objets.size());
            for (int e = 0; e < objets.size(); e++) {
                Level.Entity ent = lvl.entities().get(e);
                choses.add(new ObjectView.Thing(ent.x(), ent.z(), ent.zone(), ent.zone(),
                        ent.upperZone(), ent.kind() == Level.Kind.AUX, ent.point()));
            }
            view.update(choses, lvl.objectPoints(), c.get("x").getAsInt(),
                    c.get("z").getAsInt(), c.get("angle").getAsInt(), 0, plein);

            bitmap.view(plein, c.get("centreX").getAsInt(), c.get("centreY").getAsInt(),
                    c.get("eyeY").getAsInt(), c.get("roomTop").getAsInt(),
                    c.get("roomBottom").getAsInt(), c.get("topClip").getAsInt(),
                    c.get("bottomClip").getAsInt(), c.get("leftClip").getAsInt(),
                    c.get("rightClip").getAsInt());

            for (int e = 0; e < objets.size(); e++) {
                JsonArray o = objets.get(e).getAsJsonArray();
                int halfWidth = o.get(3).getAsInt();
                if (halfWidth == 0xFF) {
                    continue;                          // modele vectoriel, une autre voie
                }
                boolean aux = o.get(8).getAsInt() == 3;
                Bitmap.Thing t = new Bitmap.Thing(o.get(0).getAsInt(), o.get(1).getAsInt(),
                        o.get(2).getAsInt(), halfWidth, o.get(4).getAsInt(),
                        o.get(5).getAsInt(), o.get(6).getAsInt(), o.get(7).getAsInt(),
                        aux ? o.get(9).getAsInt() : 0, aux ? o.get(10).getAsInt() : 0);

                bitmap.clearOffTable();
                Bitmap.Placed p = bitmap.place(t, view);
                horsTable += bitmap.offTable();
                if (bitmap.offScale() > 0 || bitmap.offTable() > 0) {
                    horsEchelle += bitmap.offScale() > 0 ? 1 : 0;
                    continue;                          // l'oracle y lit de la memoire indefinie
                }
                JsonElement ref2 = attendus.get(e);
                if (ref2.isJsonNull()) {
                    ecartes++;
                    if (p != null) {
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d oeil %d fenetre"
                                + " %d..%d, objet %d : pose en (%d, %d), l'oracle l'ecarte",
                                lettre, c.get("angle").getAsInt(), c.get("eyeY").getAsInt(),
                                c.get("leftClip").getAsInt(), c.get("rightClip").getAsInt(), e,
                                p.left(), p.top());
                    }
                    continue;
                }
                poses++;
                JsonArray b = ref2.getAsJsonArray();
                if (p == null) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d oeil %d fenetre"
                            + " %d..%d, objet %d : ecarte, l'oracle le pose en (%d, %d)",
                            lettre, c.get("angle").getAsInt(), c.get("eyeY").getAsInt(),
                            c.get("leftClip").getAsInt(), c.get("rightClip").getAsInt(), e,
                            b.get(1).getAsInt(), b.get(0).getAsInt());
                    continue;
                }
                int[] moi = {p.top(), p.left(), p.columns(), p.rows(), p.strip(), p.stripStep(),
                    p.texStart(), p.texStep(), p.ramp(), p.flip() ? 1 : 0, p.light() ? 1 : 0,
                    p.additive() ? 1 : 0, p.lightPal(), p.bright()};
                for (int ch = 0; ch < moi.length; ch++) {
                    // La palette eclairee ne s'ecrit QUE lorsque l'image en prend une : sinon
                    // l'octet garde ce qu'un sprite precedent y avait mis, et l'oracle le
                    // releve tel quel. On ne confronte que ce qui a ete decide.
                    if (ch == 12 && !p.light()) {
                        continue;
                    }
                    if (moi[ch] != b.get(ch).getAsInt()) {
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c angle %d oeil %d fenetre"
                                + " %d..%d, objet %d : %s = %d, l'oracle dit %d", lettre,
                                c.get("angle").getAsInt(), c.get("eyeY").getAsInt(),
                                c.get("leftClip").getAsInt(), c.get("rightClip").getAsInt(), e,
                                CHAMPS[ch], moi[ch], b.get(ch).getAsInt());
                        break;
                    }
                }
            }
        }
        if (courant != 0) {
            ligne(courant, ecartsNiveau);
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d vues, %d sprites poses, %d ecartes, %d hors table de"
                + " luminosite, %d hors table d'echelle, %d ecart(s) -> %s%n", cas.size(),
                poses, ecartes, horsTable, horsEchelle, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static final String[] CHAMPS = {"ligne", "colonne", "largeur", "hauteur",
        "bande de depart", "pas de bande", "depart de texture", "pas de texture", "rampe",
        "retourne", "eclaire", "additif", "palette eclairee", "luminosite"};

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
