package ab3d2.full;

import ab3d2.full.render.EdgePvs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifie le PVS par arete contre le portage, octet par octet.
 *
 * <p>C'est la plus volumineuse des verifications : un octet par secteur potentiellement visible
 * et par ouverture, sur tous les secteurs des seize niveaux. Elle est aussi la plus sensible,
 * parce qu'elle eprouve une RECURSION : une seule arete franchie a tort ouvre une branche
 * entiere, et le resultat diverge loin du point de depart.
 *
 * <p>On compare aussi ce qui structure les masques — l'ordre des ouvertures, les sommets qui les
 * portent, l'ordre de la liste de visibilite — parce qu'un masque juste range dans le mauvais
 * ordre serait tout aussi faux.
 */
public final class EdgePvsCheck {

    private EdgePvsCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path ref = Path.of(System.getProperty("ab3d2.edgepvs", ""));
        System.out.println("oracle : " + ref);
        System.out.println();
        if (!Files.isRegularFile(ref)) {
            System.out.println("oracle absent — le produire avec :");
            System.out.println("    gradle -p ../rebirth extract -Pwhat=edgepvs");
            System.exit(1);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(ref)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray niveaux = root.getAsJsonArray("levels");

        int ecarts = 0;
        int zones = 0;
        int cases = 0;

        for (int i = 0; i < niveaux.size(); i++) {
            JsonObject n = niveaux.get(i).getAsJsonObject();
            char lettre = n.get("level").getAsString().charAt(0);
            EdgePvs pvs = Data.edgePvs(lettre);
            int ecartsNiveau = 0;

            // Les portes d'abord : c'est leur rang qui donne les bits de condition, donc une
            // liste decalee fausserait tous les masques sans toucher a la geometrie.
            JsonArray portes = n.getAsJsonArray("doors");
            if (!memeListe(pvs.doorZones(), portes)) {
                ecartsNiveau++;
                System.out.printf("  %c : portes %s, l'oracle donne %s%n",
                        lettre, pvs.doorZones(), portes);
            }

            JsonArray oracleZones = n.getAsJsonArray("zones");
            for (int z = 0; z < oracleZones.size(); z++) {
                JsonObject o = oracleZones.get(z).getAsJsonObject();
                zones++;
                int listSize = o.get("listSize").getAsInt();
                var ouvertures = pvs.openings(z);

                if (pvs.listSize(z) != listSize) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d :"
                            + " %d secteurs visibles, l'oracle en donne %d",
                            lettre, z, pvs.listSize(z), listSize);
                    continue;
                }
                if (!memeInts(pvs.potentiallyVisible(z), o.getAsJsonArray("pvs"))) {
                    ecartsNiveau += note(ecarts + ecartsNiveau,
                            "%c secteur %d : liste de visibilite differente", lettre, z);
                    continue;
                }

                JsonArray aretes = o.getAsJsonArray("edges");
                JsonArray debuts = o.getAsJsonArray("startPoints");
                JsonArray fins = o.getAsJsonArray("endPoints");
                if (ouvertures.size() != aretes.size()) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d :"
                            + " %d ouvertures, l'oracle en donne %d",
                            lettre, z, ouvertures.size(), aretes.size());
                    continue;
                }
                boolean bonnesOuvertures = true;
                for (int k = 0; k < ouvertures.size(); k++) {
                    EdgePvs.Opening op = ouvertures.get(k);
                    if (op.edge() != aretes.get(k).getAsInt()
                            || op.startPoint() != debuts.get(k).getAsInt()
                            || op.endPoint() != fins.get(k).getAsInt()) {
                        bonnesOuvertures = false;
                        ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d ouverture %d :"
                                + " arete %d/%d, sommets %d-%d/%d-%d", lettre, z, k,
                                op.edge(), aretes.get(k).getAsInt(),
                                op.startPoint(), op.endPoint(),
                                debuts.get(k).getAsInt(), fins.get(k).getAsInt());
                        break;
                    }
                }
                if (!bonnesOuvertures) {
                    continue;
                }

                String vis = o.get("vis").getAsString();
                // Gson laisse tomber les champs nuls : un secteur sans porte dans son
                // voisinage n'a pas de clef "cond" du tout.
                JsonElement condElement = o.get("cond");
                String cond = (condElement == null || condElement.isJsonNull())
                        ? null : condElement.getAsString();
                if (pvs.conditional(z) != (cond != null)) {
                    ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d :"
                            + " conditions de portes %s, l'oracle dit %s", lettre, z,
                            pvs.conditional(z) ? "oui" : "non", cond != null ? "oui" : "non");
                    // On continue quand meme : ce qui compte d'abord est que la VISIBILITE
                    // soit la meme, et c'est ce desaccord-la qu'il faut voir s'il existe.
                    cond = null;
                }

                for (int k = 0; k < ouvertures.size(); k++) {
                    for (int slot = 0; slot < listSize; slot++) {
                        cases++;
                        int plat = k * listSize + slot;
                        int attendu = Integer.parseInt(vis.substring(plat * 2, plat * 2 + 2), 16);
                        if (pvs.visibility(z, k, slot) != attendu) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d"
                                    + " ouverture %d vers le rang %d : vu %d, l'oracle dit %d",
                                    lettre, z, k, slot, pvs.visibility(z, k, slot), attendu);
                        }
                        if (cond == null) {
                            continue;
                        }
                        int c = Integer.parseInt(cond.substring(plat * 4, plat * 4 + 4), 16);
                        if (pvs.condition(z, k, slot) != c) {
                            ecartsNiveau += note(ecarts + ecartsNiveau, "%c secteur %d"
                                    + " ouverture %d vers le rang %d : portes %x, l'oracle dit %x",
                                    lettre, z, k, slot, pvs.condition(z, k, slot), c);
                        }
                    }
                }
            }

            System.out.printf("  %c : %s%n", lettre,
                    ecartsNiveau == 0 ? "conforme" : ecartsNiveau + " ECART(S)");
            ecarts += ecartsNiveau;
        }

        System.out.printf("%n%d secteurs, %d cases de visibilite, %d ecart(s) -> %s%n",
                zones, cases, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    /** Signale un ecart, mais n'inonde pas la sortie. */
    private static int note(int dejaVus, String format, Object... args) {
        if (dejaVus < 10) {
            System.out.printf("    " + format + "%n", args);
        }
        return 1;
    }

    private static boolean memeListe(java.util.List<Integer> mine, JsonArray oracle) {
        if (mine.size() != oracle.size()) {
            return false;
        }
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i) != oracle.get(i).getAsInt()) {
                return false;
            }
        }
        return true;
    }

    private static boolean memeInts(int[] mine, JsonArray oracle) {
        if (mine.length != oracle.size()) {
            return false;
        }
        for (int i = 0; i < mine.length; i++) {
            if (mine[i] != oracle.get(i).getAsInt()) {
                return false;
            }
        }
        return true;
    }
}
