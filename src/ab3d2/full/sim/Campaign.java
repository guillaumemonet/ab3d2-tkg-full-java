package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;

/**
 * LA PARTIE : ce qui survit a un niveau.
 *
 * <p>{@link World} ne connait qu'un niveau et ne sait pas qu'il y en a d'autres. Au-dessus de
 * lui il faut quelque chose qui tienne le compte des seize niveaux et, surtout, l'inventaire —
 * car le jeu en garde DEUX, et c'est la toute la mecanique de la progression.
 *
 * <h2>Les deux inventaires</h2>
 *
 * <p>{@code Plr_Health_w} et {@code Plr_Shield_w} sont ceux de la PARTIE ; {@code Plr1_Health_w}
 * et {@code Plr1_Shield_w} ceux du joueur dans le niveau en cours. Avant chaque niveau,
 * {@code Game_Start} (controlloop.s:150-165) recopie les premiers dans les seconds — onze longs
 * pour la sante et les munitions, six pour le bouclier et les armes. Apres le niveau, il ne
 * recopie EN SENS INVERSE que si {@code Game_FinishedLevel_b} est pose.
 *
 * <p>Toute la regle du jeu tient dans cette dissymetrie : <b>ce qu'on ramasse dans un niveau
 * qu'on ne finit pas est perdu.</b> On recommence avec ce qu'on avait en y entrant, pas avec ce
 * qu'on avait en mourant. Aucune ligne ne le dit ; c'est le drapeau qui le fait.
 *
 * <h2>La fin d'un niveau</h2>
 *
 * <p>{@code endlevel} (hires.s:3404) ne fait que trois choses, et l'ordre compte : il regarde la
 * sante, il avance le compteur de niveau si elle est bonne, puis il joue la musique jusqu'au
 * BOUT — la boucle d'origine tourne sur {@code mt_music} tant que {@code reachedend} est nul. Le
 * compteur avance donc AVANT que la musique ne commence, et le retour a zero du seizieme niveau
 * n'arrive qu'APRES.
 *
 * <p>Ici la musique appartient a la vue : {@link #endLevel()} applique l'etat et dit seulement
 * ce qui est arrive.
 */
public final class Campaign {

    /** {@code NUM_LEVELS} (defs.i:28). */
    public static final int LEVELS = 16;

    /** Ce qu'un niveau a donne. */
    public enum Outcome {
        /** Le joueur a atteint la sortie : on passe au suivant, et l'on emporte tout. */
        WON,
        /** Le joueur est mort : on recommence le meme, avec ce qu'on avait en entrant. */
        LOST
    }

    private final Database glf;

    /** {@code Plr_Health_w} / {@code Plr_Shield_w} : l'inventaire de la PARTIE. */
    private final Inventory party;

    /** {@code Game_LevelCounter_w} : de zero a quinze. */
    private int levelCounter;

    /** {@code Game_FinishedLevel_b} : pose par {@code endlevel}, lu par {@code Game_Start}. */
    private boolean finishedLevel;

    /** Le niveau en cours, ou {@code null} tant qu'aucun n'a commence. */
    private World world;

    public Campaign(Database glf) {
        this.glf = glf;
        this.party = new Inventory(Database.MAX_INVENTORY);
        // DEFAULTGAME (controlloop.s:143) : deux cents de sante, le fusil, vingt cartouches.
        party.defaultGame(glf);
    }

    /** Le rang du niveau en cours, de zero a quinze. */
    public int level() {
        return levelCounter;
    }

    /** La lettre du niveau en cours : les seize fichiers vont de {@code a} a {@code p}. */
    public char letter() {
        return (char) ('a' + levelCounter);
    }

    /** Ce que la base de liaison dit de ce niveau : son nom, et sa musique. */
    public Database.LevelInfo info() {
        return levelCounter >= 0 && levelCounter < glf.levels().size()
                ? glf.levels().get(levelCounter) : null;
    }

    /** Le niveau en cours. */
    public World world() {
        return world;
    }

    /** L'inventaire de la partie : ce qu'on emporte d'un niveau au suivant. */
    public Inventory party() {
        return party;
    }

    /** Se placer a un niveau donne, sans rien changer a l'inventaire ({@code PLAY GAME}). */
    public void goTo(int niveau) {
        levelCounter = Math.max(0, Math.min(LEVELS - 1, niveau));
    }

    /**
     * {@code Game_Start}, juste avant {@code Game_Begin} : le niveau est monte, et le joueur y
     * entre avec l'inventaire de la partie.
     *
     * <p>Le jeu remet aussi a zero, et c'est volontaire, l'angle de vue et l'arme en main
     * (controlloop.s:141-146) : on commence chaque niveau tourne vers le nord, le fusil en
     * main, quelles que soient les armes qu'on porte.
     */
    public World begin() {
        finishedLevel = false;                         // clr.b Game_FinishedLevel_b
        world = World.of(letter());
        world.objects.inventory.copyFrom(party);       // REPT 11 / REPT 6 : Plr_* -> Plr1_*
        // clr.w Plr1_AngPos_w ET clr.w Plr1_SnapAngPos_w : le portage n'a qu'un champ, les deux
        // mots du jeu ne differant que d'une image.
        world.player.angPos = 0;
        world.gun = 0;                                 // clr.b Plr1_GunSelected_b
        return world;
    }

    /**
     * {@code endlevel} (hires.s:3404) : le niveau est fini, d'une facon ou d'une autre.
     *
     * <p>La sante tranche, et elle seule. Au-dessus de zero on a gagne, quelle que soit la
     * raison pour laquelle la boucle s'est arretee ; a zero ou moins on a perdu.
     */
    public Outcome endLevel() {
        int energie = world == null ? 0 : world.health();  // move.w Plr1_Health_w,draw_Display...
        if (energie <= 0) {                                // tst.w ... ; bgt.s wevewon
            return Outcome.LOST;
        }
        // wevewon : le compteur avance AVANT la musique, et le drapeau avec lui.
        levelCounter++;                                    // add.w #1,Game_LevelCounter_w
        finishedLevel = true;                              // st Game_FinishedLevel_b
        return Outcome.WON;
    }

    /**
     * La suite de {@code wevewon}, apres que la musique s'est tue : le seizieme niveau ramene au
     * premier.
     *
     * <p>« back to the start for now :( », dit le commentaire d'origine — l'ecran de victoire
     * ({@code ENDGAMESCROLL}) n'a jamais ete ecrit : la routine se termine par un {@code rts}
     * precede de « better than blundering ».
     */
    public void wrapIfFinished() {
        if (levelCounter == LEVELS) {                      // cmp.w #NUM_LEVELS,...
            levelCounter = 0;                              // clr.w Game_LevelCounter_w
        }
    }

    /**
     * {@code Game_Start}, juste apres {@code Game_Begin} (controlloop.s:180-192) : l'inventaire
     * du joueur ne devient celui de la partie QUE si le niveau a ete fini.
     */
    public void carryInventory() {
        if (finishedLevel && world != null) {              // tst.b Game_FinishedLevel_b ; beq
            party.copyFrom(world.objects.inventory);       // REPT 11 / REPT 6, en sens inverse
        }
    }

    /** Vrai si le dernier niveau a ete mene a son terme. */
    public boolean finishedLevel() {
        return finishedLevel;
    }

    /** Une partie neuve : retour au premier niveau, inventaire de depart. */
    public void newGame() {
        levelCounter = 0;
        finishedLevel = false;
        party.defaultGame(glf);
    }

    /** Le nom du niveau en cours, tel que la base de liaison le porte. */
    public String name() {
        Database.LevelInfo i = info();
        return i == null ? String.valueOf(letter()) : i.name();
    }

    /** La racine des donnees, pour que l'appelant n'ait pas a la connaitre. */
    public static Campaign start() {
        return new Campaign(Data.database());
    }
}
