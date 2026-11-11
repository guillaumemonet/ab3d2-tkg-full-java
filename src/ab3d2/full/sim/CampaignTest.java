package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.level.Level;

/**
 * LA PARTIE, eprouvee : la sortie, la mort, et ce qu'on emporte.
 *
 * <p>Il n'y a pas d'oracle a confronter ici — rien de tout cela ne calcule quoi que ce soit, ce
 * sont des REGLES. Ce harnais les verifie donc contre l'assembleur lui-meme, regle par regle,
 * et chacune porte la ligne qui la justifie.
 *
 * <h2>Les quatre regles</h2>
 *
 * <ol>
 *   <li><b>La sortie prend neuf images, pas une.</b> Le secteur ajoute deux par image, la
 *       presentation en retire un : le solde est de +1, et il faut atteindre neuf. Huit images
 *       passees dans la zone suffisent donc tout juste.</li>
 *   <li><b>Ressortir re-materialise.</b> Hors de la zone, plus personne n'ajoute et la
 *       presentation continue de retirer : le compte redescend a zero tout seul.</li>
 *   <li><b>Mourir ne fait pas avancer le compteur.</b> {@code endlevel} regarde la sante et
 *       elle seule ; a zero, on recommence le meme niveau.</li>
 *   <li><b>Ce qu'on ramasse dans un niveau qu'on ne finit pas est perdu.</b> L'inventaire de la
 *       partie n'est mis a jour que si {@code Game_FinishedLevel_b} est pose.</li>
 * </ol>
 *
 * <pre>gradle -p full campaignTest</pre>
 */
public final class CampaignTest {

    /** Le solde par image dans la zone de sortie : +2 du secteur, -1 de la presentation. */
    private static final int SEUIL = 9;

    private static int echecs;

    private CampaignTest() {
    }

    public static void main(String[] args) {
        regleDeLaSortie();
        regleDuRetour();
        regleDeLaMort();
        regleDeLInventaire();
        toutesLesSorties();

        if (echecs != 0) {
            System.out.printf("campaignTest : %d regle(s) en defaut%n", echecs);
            System.exit(1);
        }
        System.out.println("PASS");
    }

    /** Regle 1 : huit images dans la zone, et pas sept. */
    private static void regleDeLaSortie() {
        World w = World.of('a');
        int sortie = w.level.exitZone();
        int images = 0;
        while (!w.finished && images < 100) {
            w.player.zone = sortie;                    // on se tient dans la zone
            pied(w);
            images++;
            w.shimmerStep();                           // Vid_Present : -1
        }
        verifier("la sortie prend huit images", images == 8, "il en a fallu " + images);
        verifier("le compte atteint le seuil", w.teleportFrame >= SEUIL - 1,
                "compte = " + w.teleportFrame);
    }

    /** Regle 2 : ressortir avant la fin re-materialise. */
    private static void regleDuRetour() {
        World w = World.of('a');
        int sortie = w.level.exitZone();
        int dehors = sortie == 0 ? 1 : 0;
        for (int i = 0; i < 4; i++) {                  // quatre images dedans
            w.player.zone = sortie;
            pied(w);
            w.shimmerStep();
        }
        int haut = w.teleportFrame;
        verifier("quatre images font monter le compte", haut > 0, "compte = " + haut);
        int images = 0;
        while (w.teleportFrame > 0 && images < 50) {   // puis dehors
            w.player.zone = dehors;
            pied(w);
            w.shimmerStep();
            images++;
        }
        verifier("ressortir ramene le compte a zero", w.teleportFrame == 0,
                "compte = " + w.teleportFrame);
        verifier("ressortir n'acheve pas le niveau", !w.finished, "le niveau s'est acheve");
    }

    /** Regle 3 : la mort n'avance pas le compteur de niveau. */
    private static void regleDeLaMort() {
        Campaign c = new Campaign(Data.database());
        World w = c.begin();
        verifier("on commence au niveau A", c.letter() == 'a', "niveau " + c.letter());
        w.objects.inventory.consumables[0] = 0;        // move.w #0,Plr1_Health_w
        pied(w);
        verifier("la sante nulle acheve le niveau", w.finished, "le niveau continue");
        Campaign.Outcome r = c.endLevel();
        verifier("sante nulle = defaite", r == Campaign.Outcome.LOST, "resultat " + r);
        c.carryInventory();
        c.wrapIfFinished();
        verifier("on recommence le meme niveau", c.letter() == 'a', "niveau " + c.letter());
        verifier("la partie garde sa sante de depart", c.party().health() == 200,
                "sante " + c.party().health());
    }

    /** Regle 4 : on n'emporte que ce qu'on sort d'un niveau termine. */
    private static void regleDeLInventaire() {
        // a) niveau TERMINE : ce qu'on a ramasse est emporte.
        Campaign gagne = new Campaign(Data.database());
        World w = gagne.begin();
        w.objects.inventory.consumables[0] = 777;      // on a ramasse de la sante
        w.objects.inventory.items[Inventory.WEAPON_START + 3] = 0xFF;   // et une arme
        verifier("termine : victoire", gagne.endLevel() == Campaign.Outcome.WON, "defaite");
        gagne.carryInventory();
        verifier("termine : la sante suit", gagne.party().health() == 777,
                "sante " + gagne.party().health());
        verifier("termine : l'arme suit", gagne.party().hasGun(3), "pas d'arme");
        verifier("termine : on passe au niveau suivant", gagne.letter() == 'b',
                "niveau " + gagne.letter());

        // b) niveau PERDU : tout est perdu, on repart de ce qu'on avait en entrant.
        Campaign perdu = new Campaign(Data.database());
        World mort = perdu.begin();
        mort.objects.inventory.consumables[0] = 0;
        mort.objects.inventory.items[Inventory.WEAPON_START + 3] = 0xFF;
        verifier("perdu : defaite", perdu.endLevel() == Campaign.Outcome.LOST, "victoire");
        perdu.carryInventory();
        verifier("perdu : l'arme ramassee est perdue", !perdu.party().hasGun(3),
                "l'arme a ete gardee");
        verifier("perdu : la sante de la partie est intacte", perdu.party().health() == 200,
                "sante " + perdu.party().health());

        // c) le seizieme niveau ramene au premier.
        Campaign bout = new Campaign(Data.database());
        bout.goTo(Campaign.LEVELS - 1);
        World dernier = bout.begin();
        dernier.objects.inventory.consumables[0] = 10;
        verifier("dernier niveau : victoire", bout.endLevel() == Campaign.Outcome.WON, "defaite");
        bout.wrapIfFinished();
        verifier("le seizieme ramene au premier", bout.letter() == 'a', "niveau " + bout.letter());
    }

    /** Les seize niveaux ont une zone de sortie, et elle existe. */
    private static void toutesLesSorties() {
        int bonnes = 0;
        for (int i = 0; i < Campaign.LEVELS; i++) {
            char lettre = (char) ('a' + i);
            Level lvl = Data.level(lettre);
            int sortie = lvl.exitZone();
            boolean existe = false;
            for (Level.Zone z : lvl.zones()) {
                if (z.id() == sortie) {
                    existe = true;
                    break;
                }
            }
            if (existe) {
                bonnes++;
            } else {
                System.out.printf("  niveau %c : la zone de sortie %d n'existe pas%n",
                        lettre, sortie);
            }
        }
        verifier("les seize niveaux ont une sortie reelle", bonnes == Campaign.LEVELS,
                bonnes + " sur " + Campaign.LEVELS);
    }

    /**
     * Le pied de {@code game_main_loop} seul : ni joueur, ni monstres, ni portes. On ne veut
     * que la regle de sortie, et la faire tourner avec toute la simulation la noierait dans
     * cent autres choses.
     */
    private static void pied(World w) {
        w.frame(new World.Input());
    }

    private static void verifier(String regle, boolean vrai, String detail) {
        if (vrai) {
            System.out.printf("  ok   %s%n", regle);
        } else {
            System.out.printf("  FAUX %s : %s%n", regle, detail);
            echecs++;
        }
    }
}
