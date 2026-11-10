package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.level.Level;

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
 * Verifie {@code MoveObject} : la collision du jeu, au mot pres.
 *
 * <p>C'est la routine la plus appelee de la simulation, et la derniere qui n'avait pas d'oracle
 * a elle. Tout ce qui se deplace horizontalement y passe : les deux joueurs, chaque balle,
 * chaque monstre qui glisse, chaque flamme d'explosion. Le chapitre du souffle l'avait montre
 * sans pouvoir le prouver : deux pour cent des flammes n'atterrissaient pas ou le jeu les pose,
 * et l'on ne savait pas pourquoi.
 *
 * <p>Sept mille deplacements sur six niveaux, depuis le barycentre de chaque zone vers seize
 * directions et trois distances, sous les neuf reglages des appelants reels. Pour chacun, on
 * compare tout ce que la routine laisse derriere elle :
 *
 * <ul>
 *   <li>le point d'arrivee, mais aussi la ZONE d'arrivee et l'etage ({@code StoodInTop}) —
 *       c'est {@code CheckMoreFloorLines} qui les change, en recommencant tout a chaque
 *       portail franchi, jusqu'a cinquante fois ;</li>
 *   <li>{@code hitwall}, et la hauteur du contact ({@code wallhitheight}) : celle-ci est
 *       INTERPOLEE le long du pas, pas prise a l'arrivee ;</li>
 *   <li>les trois mots du mur touche, que le rebond relit — et qui ne sont ECRITS que si le
 *       rebond est arme, de sorte qu'un portage qui les ecrirait toujours se trahit ;</li>
 *   <li>le chemin de zones traversees ({@code Obj_RoomPath_vw}) ;</li>
 *   <li>et les ARETES ARMEES. Ce dernier point n'est pas decoratif : le bit pose dans
 *       {@code EdgeT_Flags_w} est le seul handshake qui ouvre les portes. Une collision juste
 *       qui n'arme pas la bonne arete laisse le joueur devant une porte close — et une qui en
 *       arme une de trop ouvre une porte qu'il n'a pas touchee.</li>
 * </ul>
 *
 * <h2>Les reglages sont des globales</h2>
 *
 * <p>{@code MoveObject} ne prend aucun parametre. Les neuf configurations relevees sont celles
 * des appelants reels, et elles ne different pas qu'en degre : {@code Obj_AwayFromWall_b} vaut
 * $FF pour une balle — NEGATIF, donc pas de rayon du tout — zero pour un joueur, et UN pour une
 * flamme, ou l'octet de normale est decale d'un bit. La neuvieme, synthetique, le pousse a deux
 * bits : aucun appelant ne le fait, mais rien ne l'interdit, et sans elle un portage qui ignore
 * le decalage ne se trahirait qu'a moitie.
 *
 * <h2>Ou l'on part</h2>
 *
 * <p>De CHAQUE secteur du niveau, pas d'un echantillon : c'est le sol et le plafond d'un
 * secteur qui decident si une arete est un mur ou un passage, et n'en prendre qu'une poignee,
 * c'est n'eprouver qu'une poignee de geometries. Un cas sur deux part du barycentre du
 * contour — les secteurs sont convexes, c'est ce que {@code checkwalls} suppose — et l'autre
 * d'un point pousse aux trois quarts vers le milieu d'une arete, tout contre le mur, la ou les
 * egalites se jouent.
 *
 * <p>Les hauteurs de depart sont FRANCHES une fois sur quatre : la tete pile au plafond, les
 * pieds pile sur le sol, et deux ancres a l'etage superieur quand le secteur en a un. Les
 * comparaisons de hauteur de la routine sont des {@code cmp.l} dont les bornes sont le sol et
 * le plafond des secteurs VOISINS, et deux secteurs mitoyens partagent tres souvent ces
 * hauteurs-la : sans une ancre franche, aucune ne tombe jamais a egalite, et le fait qu'elles
 * soient inclusives ou strictes ne se demontre pas. Et la hauteur BOUGE d'un bout a l'autre du
 * pas, parfois de douze mille, sans quoi l'interpolation au point de franchissement ne serait
 * jamais exercee.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Cinquante mille deplacements, six niveaux, aucun ecart. Sur soixante-dix-neuf mutations,
 * soixante-neuf mordent ; les dix survivantes se mesurent, et quatre d'entre elles sont
 * INATTEIGNABLES et non pas seulement rares :
 *
 * <ul>
 *   <li>faire lire a la marche de portails les groupes d'aretes au-dela du contour ne change
 *       rien : aucun des cinquante mille pas ne franchit un portail depuis un autre groupe ;</li>
 *   <li>ne pas rendre la zone de depart a la fin est EQUIVALENT : la seule facon d'en changer
 *       est le franchissement, qui reprend tout depuis la nouvelle, de sorte qu'a l'arrivee la
 *       zone courante est toujours deja celle-la ;</li>
 *   <li>le plancher a un de la distance parcourue le long d'un portail demande que les deux
 *       bouts soient a la MEME distance signee de l'arete : cela n'arrive jamais ;</li>
 *   <li>un mur plein est marque par une hauteur a moins seize millions, et la mutation la met
 *       a moins deux milliards : les deux repondent pareil, car seul le test du sol est
 *       atteint pour un mur plein, et tout ce qui est assez bas y passe ;</li>
 *   <li>le plancher a un de la distance a travers le mur est atteint mille cinq cent
 *       quatre-vingt-dix-sept fois — mais sur le 68060 il tient la routine a l'ecart d'une
 *       DIVISION PAR ZERO, et l'aide Java rend le dividende inchange au lieu de trapper, ce
 *       qui donne par accident la meme reponse qu'une division par un ;</li>
 *   <li>le test du sol de l'etage superieur ne tombe jamais a egalite — zero fois ;</li>
 *   <li>l'ouverture vaut exactement la hauteur de l'objet trois cent quatre fois, mais jamais
 *       dans une position d'ou la marche aurait laisse passer ;</li>
 *   <li>la marche MONTANTE tombe pile sur sa borne vingt-sept fois, meme remarque ;</li>
 *   <li>la marche DESCENDANTE ne le peut pas : sa borne vaut seize millions sept cent
 *       soixante-dix-sept mille, soit cent trente mille unites de monde. Aucun niveau n'est
 *       aussi haut, et aucun ne le sera ;</li>
 *   <li>un denominateur nul — le pas PARALLELE a l'arete — arrive quatre mille huit cent
 *       cinquante-trois fois, et cent deux d'entre elles passeraient le test mutile ; aucune ne
 *       change le resultat, le test du cote de l'ancien point les rattrapant toutes.</li>
 * </ul>
 *
 * <p>Une onzieme mesure, pour memoire : le quotient d'effleurement DEBORDE neuf fois sur les
 * cinquante mille, et la routine garde alors le produit entier au lieu du quotient, comme le
 * {@code divs} du 68000 le veut. Aucune de ces neuf fois n'a ses seize bits bas sous
 * trente-deux, de sorte que le debordement ne change pas qui arme quelle arete.
 *
 * <pre>gradle -p full moveCheck</pre>
 */
public final class MoveCheck {

    private MoveCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.move", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=move");
            System.exit(1);
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }

        int ecarts = 0;
        int pas = 0;
        for (var el : root.getAsJsonArray("levels")) {
            JsonObject n = el.getAsJsonObject();
            pas += n.getAsJsonArray("cases").size();
            ecarts += unNiveau(n);
        }
        System.out.printf("%n%d deplacements sur six niveaux, %d ecart(s) -> %s%n",
                pas, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static int unNiveau(JsonObject n) {
        char lettre = n.get("level").getAsString().charAt(0);
        Level data = Data.level(lettre);
        LevelSim lvl = LevelSim.of(data);
        Move move = new Move(lvl);

        int ecarts = 0;
        int montres = 0;
        for (var el : n.getAsJsonArray("cases")) {
            JsonObject c = el.getAsJsonObject();
            int e = unCas(lvl, move, c, montres < 6);
            if (e != 0) {
                montres++;
            }
            ecarts += e;
        }
        System.out.printf("%s  %4d deplacements  %s%n", lettre,
                n.getAsJsonArray("cases").size(), ecarts == 0 ? "OK" : ecarts + " ecart(s)");
        return ecarts;
    }

    private static int unCas(LevelSim lvl, Move move, JsonObject c, boolean bavard) {
        int bit = c.get("wallFlags").getAsInt();
        // Les drapeaux d'arete sont CUMULATIFS — le jeu ne les efface qu'une fois par image. On
        // efface le seul bit qu'on va poser, pour que ce qu'on releve soit l'oeuvre de CE
        // deplacement et non la somme des precedents.
        for (int i = 0; i < lvl.edgeFlags.length; i++) {
            lvl.edgeFlags[i] &= ~bit;
        }

        move.oldx = c.get("oldx").getAsInt();
        move.oldz = c.get("oldz").getAsInt();
        move.oldy = c.get("oldy").getAsInt();
        move.newx = c.get("newx").getAsInt();
        move.newz = c.get("newz").getAsInt();
        move.newy = c.get("newy").getAsInt();
        move.zone = c.get("zone").getAsInt();
        move.stoodInTop = c.get("stoodInTop").getAsInt() != 0;
        move.extLen = c.get("extLen").getAsInt();
        move.awayFromWall = c.get("awayFromWall").getAsInt();
        move.wallBounce = c.get("wallBounce").getAsInt() != 0;
        move.exitFirst = c.get("exitFirst").getAsInt() != 0;
        move.wallFlags = bit;
        move.stepUp = c.get("stepUp").getAsInt();
        move.stepDown = c.get("stepDown").getAsInt();
        move.thingHeight = c.get("thingHeight").getAsInt();
        // Les trois mots du mur touche sont des sorties que le jeu n'efface pas : on les met a
        // une valeur reconnaissable pour voir si le deplacement les a ecrites ou non.
        move.wallXSize = 0x5A5A;
        move.wallZSize = 0x5A5A;
        move.wallLength = 0x5A5A;

        move.moveObject();

        JsonObject a = c.getAsJsonObject("after");
        StringBuilder dit = new StringBuilder();
        int e = 0;
        e += cmp(dit, "newx", (short) move.newx, a.get("newx").getAsInt());
        e += cmp(dit, "newz", (short) move.newz, a.get("newz").getAsInt());
        e += cmp(dit, "newy", move.newy, a.get("newy").getAsInt());
        e += cmp(dit, "zone", move.zone, a.get("zone").getAsInt());
        e += cmp(dit, "hitwall", move.hitwall ? 1 : 0, a.get("hitwall").getAsInt());
        e += cmp(dit, "etage", move.stoodInTop ? 1 : 0, a.get("stoodInTop").getAsInt());
        e += cmp(dit, "wallhitheight", move.wallhitheight, a.get("wallhitheight").getAsInt());
        e += cmp(dit, "wallXSize", (short) move.wallXSize, a.get("wallXSize").getAsInt());
        e += cmp(dit, "wallZSize", (short) move.wallZSize, a.get("wallZSize").getAsInt());
        e += cmp(dit, "wallLength", (short) move.wallLength, a.get("wallLength").getAsInt());

        JsonArray chemin = a.getAsJsonArray("roomPath");
        e += cmp(dit, "chemin (longueur)", move.roomPath.size(), chemin.size());
        for (int i = 0; i < Math.min(move.roomPath.size(), chemin.size()); i++) {
            e += cmp(dit, "chemin[" + i + "]", move.roomPath.get(i), chemin.get(i).getAsInt());
        }

        List<Integer> armees = new ArrayList<>();
        for (int i = 0; i < lvl.edgeFlags.length; i++) {
            if ((lvl.edgeFlags[i] & bit) != 0) {
                armees.add(i);
            }
        }
        JsonArray att = a.getAsJsonArray("flagged");
        e += cmp(dit, "aretes armees (nombre)", armees.size(), att.size());
        for (int i = 0; i < Math.min(armees.size(), att.size()); i++) {
            e += cmp(dit, "arete[" + i + "]", armees.get(i), att.get(i).getAsInt());
        }

        if (e != 0 && bavard) {
            System.out.printf("  %s zone %d  (%d,%d,%d) -> (%d,%d,%d)%n",
                    c.get("config").getAsString(), c.get("zone").getAsInt(),
                    c.get("oldx").getAsInt(), c.get("oldz").getAsInt(), c.get("oldy").getAsInt(),
                    c.get("newx").getAsInt(), c.get("newz").getAsInt(), c.get("newy").getAsInt());
            System.out.print(dit);
        }
        return e;
    }

    private static int cmp(StringBuilder dit, String quoi, int got, int want) {
        if (got == want) {
            return 0;
        }
        dit.append(String.format("      %-24s %8d != %8d%n", quoi, got, want));
        return 1;
    }
}
