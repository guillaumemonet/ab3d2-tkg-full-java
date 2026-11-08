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

/**
 * Verifie le RECORD DE DESSIN D'UN MONSTRE : la queue de {@code ai_DoAttackAnim}.
 *
 * <p>L'oracle {@code objanim} eprouve deja ce qu'une chose du DECOR donne a voir. Un monstre
 * ne passe pas par la : son apparence se pose au bout de son animation, et c'est la que
 * naissent les drapeaux de dessin. Ce sont eux qui decident de la quatrieme boucle de sprite —
 * celle qui se fabrique une palette a chaque image. Tant qu'ils restent a zero, la voie
 * eclairee est branchee mais jamais prise.
 *
 * <p>On ne compare pas seulement la queue : l'appel entier est rejoue, donc le choix de
 * l'option d'animation ({@code ViewpointToDraw} compris), l'image forcee, la consommation du
 * brouillon d'animation, et l'objet AUXILIAIRE accroche a l'image.
 *
 * <h2>Ce que la comparaison doit traduire</h2>
 *
 * <p>L'original ecrit tout cela dans soixante-quatre octets d'enregistrement ; ce portage n'a
 * pas d'enregistrement. Deux equivalences sont donc posees, et verifiees :
 *
 * <ul>
 *   <li>l'AUXILIAIRE occupe l'entite qui precede le monstre, et la routine y RECOPIE le
 *       secteur, la hauteur et l'etage de son porteur. Ici l'auxiliaire n'existe pas comme
 *       entite : il emprunte ces trois valeurs au monstre, ce qui est la meme chose — et on
 *       compare bien les valeurs du monstre aux trois copies de l'oracle ;</li>
 *   <li>{@code ai_FinishedAnim_b} est un OCTET recopie, modelise ici par un booleen. Les
 *       quatre lecteurs de l'original le testent tous par {@code tst.b} : seul son caractere
 *       nul ou non est observable.</li>
 * </ul>
 *
 * <pre>gradle -p full alienAnimCheck</pre>
 */
public final class AlienAnimCheck {

    /** L'orientation du regard que l'oracle fixe. */
    private static final int REGARD = 1024;

    /** Le secteur seme dans l'enregistrement. */
    private static final int SECTEUR = 7;

    /** La hauteur semee. */
    private static final int HAUTEUR = 1234;

    /** L'action en attente semee dans le brouillon d'animation. */
    private static final int ACTION = 0x33;

    /** La trace laissee dans l'auxiliaire avant chaque appel : la routine doit l'effacer. */
    private static final int TRACE_PAS = 7;
    private static final int TRACE_X = 999;
    private static final int TRACE_Y = -999;

    private AlienAnimCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.alienanim", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=alienanim");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray cas = root.getAsJsonArray("cases");

        Level lvl = Data.level('A');
        Database glf = Data.database();
        if (lvl == null || glf == null) {
            System.err.println("niveau ou base GLF introuvable");
            System.exit(1);
            return;
        }
        LevelSim sim = LevelSim.of(lvl);
        Aliens al = new Aliens(sim, lvl, glf, Data.sinCos(), new Nav(lvl), new Los(sim, lvl));
        PlayerSim joueur = new PlayerSim(sim, lvl, Data.sinCos());
        joueur.angPos = REGARD;
        Aliens.Alien a = al.aliens().isEmpty() ? null : al.aliens().get(0);
        if (a == null) {
            System.err.println("le niveau A ne porte aucun monstre");
            System.exit(1);
            return;
        }

        int ecarts = 0;
        int modeles = 0;
        int eclaires = 0;
        int simples = 0;
        int retournes = 0;
        int auxiliaires = 0;
        int forcees = 0;
        int horsAnim = 0;
        for (int i = 0; i < cas.size(); i++) {
            JsonObject c = cas.get(i).getAsJsonObject();
            int type = c.get("type").getAsInt();
            int quelAux = c.get("auxObj").getAsInt();
            int force = c.get("forced").getAsInt();
            // Au-dela du terminateur, l'original lit la suite de la table — l'option suivante.
            // Le jeu n'y va jamais : la boucle d'animation remet le compteur a zero des qu'elle
            // voit le terminateur, et une commande qui saute plus loin est rattrapee par le meme
            // test. Ce portage, lui, s'arrete au terminateur. L'ORACLE dit ou il est.
            if (c.get("used").getAsInt() > c.get("last").getAsInt()) {
                horsAnim++;
                continue;
            }

            // L'entree, posee exactement comme l'oracle la pose.
            a.type = type;
            a.timer2 = c.get("frame").getAsInt();
            a.angle = c.get("angle").getAsInt();
            a.zone = SECTEUR;
            a.height = HAUTEUR;
            a.upperZone = true;
            a.wsView = c.get("view").getAsInt();
            a.wsSpecial = force < 0 ? 0xFF : force;
            a.wsAction = ACTION;
            a.wsFinished = true;
            // On ne remet PAS l'auxiliaire a l'absence : c'est la routine qui doit le liberer
            // a chaque image ({@code FREE_ENT_2}). On laisse au contraire une trace bien visible,
            // sans quoi l'oubli de cette liberation ne se verrait pas.
            a.auxFrame = TRACE_PAS;
            a.auxX = TRACE_X;
            a.auxY = TRACE_Y;
            joueur.angPos = REGARD;

            al.animateForCheck(a, joueur, quelAux);

            String quoi = String.format("monstre %d, vue %d, image %d (aux %d)",
                    type, c.get("view").getAsInt(), c.get("frame").getAsInt(), quelAux);

            // Le type de dessin, tire de la definition : c'est lui qui decide de tout le reste.
            int vecObj = al.vecObj();
            ecarts += compare(ecarts, quoi, "type de dessin", vecObj, c.get("vecObj").getAsInt());

            // L'animation a-t-elle bien consomme son brouillon ?
            ecarts += compare(ecarts, quoi, "action relevee", al.doAction(),
                    c.get("doAction").getAsInt());
            ecarts += compare(ecarts, quoi, "fin de boucle relevee", al.finishedAnim() ? 1 : 0,
                    c.get("finished").getAsInt() != 0 ? 1 : 0);
            ecarts += compare(ecarts, quoi, "action remise a zero", a.wsAction,
                    c.get("wsAction").getAsInt());
            ecarts += compare(ecarts, quoi, "image forcee relachee", a.wsSpecial & 0xFF,
                    c.get("wsSpecial").getAsInt());
            ecarts += compare(ecarts, quoi, "fin de boucle remise a zero", a.wsFinished ? 1 : 0,
                    c.get("wsFinished").getAsInt());
            ecarts += compare(ecarts, quoi, "increment d'angle", al.animFacing() & 0xFFFF,
                    c.get("animFacing").getAsInt());

            // Le record de dessin lui-meme.
            Appearance vu = Appearance.ofAlien(a.sheet, a.frame, a.flip, a.drawWord2, vecObj);
            ecarts += compare(ecarts, quoi, "feuille", vu.sheet(), c.get("sheet").getAsInt());
            ecarts += compare(ecarts, quoi, "image", vu.frame(), c.get("image").getAsInt());
            ecarts += compare(ecarts, quoi, "drapeaux", vu.flags(), c.get("flags").getAsInt());
            ecarts += compare(ecarts, quoi, "demi-largeur", vu.halfWidth(),
                    c.get("halfWidth").getAsInt());
            ecarts += compare(ecarts, quoi, "demi-hauteur", vu.halfDepth(),
                    c.get("halfDepth").getAsInt());

            if (vu.vector()) {
                modeles++;
            } else if ((vu.flags() & 127) != 0) {
                eclaires++;
            } else {
                simples++;
            }
            if ((vu.flags() & Appearance.FLIPPED) != 0) {
                retournes++;
            }
            if (force >= 0) {
                forcees++;
            }

            // L'auxiliaire. L'oracle dit s'il y en a un : la routine LIBERE l'entite precedente
            // (secteur a -1) et ne la remplit que si l'image en reclame un.
            boolean oracleEnAUn = c.get("auxZone").getAsInt() != -1;
            boolean mienEnAUn = a.auxFrame >= 0;
            if (oracleEnAUn != mienEnAUn) {
                ecarts += note(ecarts, "%s : auxiliaire %s, l'oracle dit %s", quoi,
                        mienEnAUn ? "present" : "absent", oracleEnAUn ? "present" : "absent");
                continue;
            }
            if (!oracleEnAUn) {
                continue;
            }
            auxiliaires++;
            ecarts += compare(ecarts, quoi, "pas de l'auxiliaire", a.auxFrame,
                    c.get("auxStep").getAsInt());
            ecarts += compare(ecarts, quoi, "decalage X de l'auxiliaire", a.auxX,
                    c.get("auxOffX").getAsInt());
            ecarts += compare(ecarts, quoi, "decalage Y de l'auxiliaire", a.auxY,
                    c.get("auxOffY").getAsInt());
            // Les trois valeurs que la routine RECOPIE du porteur vers l'auxiliaire.
            ecarts += compare(ecarts, quoi, "secteur recopie", a.zone,
                    c.get("auxZone").getAsInt());
            ecarts += compare(ecarts, quoi, "secteur d'entite recopie", a.zone,
                    c.get("auxEntZone").getAsInt());
            ecarts += compare(ecarts, quoi, "hauteur recopiee", a.height,
                    c.get("auxHeight").getAsInt());
            ecarts += compare(ecarts, quoi, "etage recopie", a.upperZone ? 0xFF : 0,
                    c.get("auxUpper").getAsInt());

            Database.ObjectDef def = glf.object(quelAux);
            if (def == null || a.auxFrame >= def.idleAnim().size()) {
                ecarts += note(ecarts, "%s : definition %d sans pas %d", quoi, quelAux,
                        a.auxFrame);
                continue;
            }
            Appearance vuAux = Appearance.of(def.idleAnim().get(a.auxFrame), def.gfxType());
            ecarts += compare(ecarts, quoi, "feuille de l'auxiliaire", vuAux.sheet(),
                    c.get("auxSheet").getAsInt());
            ecarts += compare(ecarts, quoi, "image de l'auxiliaire", vuAux.frame() & 0xFF,
                    c.get("auxImage").getAsInt());
            ecarts += compare(ecarts, quoi, "drapeaux de l'auxiliaire", vuAux.flags(),
                    c.get("auxFlags").getAsInt());
            ecarts += compare(ecarts, quoi, "demi-largeur de l'auxiliaire", vuAux.halfWidth(),
                    c.get("auxHalfWidth").getAsInt());
            ecarts += compare(ecarts, quoi, "demi-hauteur de l'auxiliaire", vuAux.halfDepth(),
                    c.get("auxHalfDepth").getAsInt());
        }

        System.out.printf("%n%d appels (%d hors animation) : %d eclaires, %d simples,"
                + " %d modeles, %d retournes, %d images forcees, %d auxiliaires,"
                + " %d ecart(s) -> %s%n",
                cas.size(), horsAnim, eclaires, simples, modeles, retournes, forcees,
                auxiliaires, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int compare(int dejaVus, String quoi, String champ, int mien, int oracle) {
        if (mien == oracle) {
            return 0;
        }
        return note(dejaVus, "%s : %s = %d, l'oracle dit %d", quoi, champ, mien, oracle);
    }

    private static int note(int dejaVus, String format, Object... args) {
        if (dejaVus < 20) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }
}
