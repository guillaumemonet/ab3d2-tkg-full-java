package ab3d2.full.sim;

import ab3d2.full.Data;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;

/**
 * Un niveau en train d'etre joue : toutes les pieces de la simulation, assemblees et avancees
 * ensemble.
 *
 * <p>Chacune de ces pieces a ete portee separement, mais elles ne valent rien prises une a une :
 * ce qui fait le jeu, c'est l'ORDRE dans lequel la boucle d'origine les appelle. Le joueur dit
 * d'abord ou il veut aller, tombe, puis se cogne ; les objets qu'il ramasse liberent des portes ;
 * les balles partent et volent ; les monstres jouent ensuite, de sorte qu'ils reagissent a un tir
 * dans la meme image ; le sol fait ses degats ; et les portes et ascenseurs bougent en dernier,
 * ce qui est aussi ce qui emporte le joueur quand il se tient sur un ascenseur.
 *
 * <p>Changer cet ordre ne casse rien de visible et change tout : un monstre riposterait une image
 * plus tard, une porte se refermerait avant d'avoir ete franchie.
 *
 * <p>Rien ici ne dessine ni ne fait de bruit. Les sons sont mis dans une file que l'appelant vide
 * s'il le veut, et les textes dans une autre.
 */
public final class World {

    /** Ce que le joueur demande pendant une image. */
    public static final class Input {

        /** Avancer, reculer, pas de cote. */
        public boolean forward, backward, stepLeft, stepRight;
        /** Courir, sauter, s'accroupir. */
        public boolean run, jump, duck;
        /** Tirer. */
        public boolean fire;
        /** Actionner ce qui est a portee : c'est la touche qui ouvre les portes a serrure. */
        public boolean action;
        /** Tourner, en unites de la table de sinus ; positif vers la droite. */
        public int turn;
        /** Pente de la visee, en centiemes : le tir suit le regard. */
        public int aim;
    }

    /** Le niveau tel que le fichier le decrit. */
    public final Level level;
    /** Les definitions : ce que sont les monstres, les objets, les armes. */
    public final Database glf;
    /** La geometrie, sous la forme ou la collision la veut. */
    public final LevelSim lvl;
    public final Nav nav;
    public final Los los;
    public final PlayerSim player;
    public final Shots shots;
    public final Aliens aliens;
    public final ObjectRuntime objects;
    public final Anims anims;
    public final SfxQueue sfx = new SfxQueue();

    /**
     * L'eclairage, quand la boucle en fournit un. {@code null} : rien ne clignote et aucune
     * source mobile n'eclaire — c'est l'etat des harnais, qui ne dessinent pas.
     */
    public ZoneLight light;

    /** Anim_Timer_w : le compte a rebours du clignotement, decremente a chaque image. */
    private int brightTimer;
    public final Messages messages = new Messages();

    /** L'arme en main. */
    public int gun;

    /**
     * L'arme en main : un OBJET du monde, pose a chaque image a l'endroit de l'oeil.
     *
     * <p>Ce n'est pas un decor plaque sur l'image. Le jeu lui donne l'entite situee deux rangs
     * apres celle du joueur, et le rendu la traite comme n'importe quel modele.
     */
    public final Weapon weapon = new Weapon();
    /** Le numero de l'image courante. */
    public int frame;

    /**
     * {@code TELVAL} ({@code Game_TeleportFrame_w}) : ou en est la dematerialisation.
     *
     * <p>Deux choses l'alimentent, et une seule le consomme. La zone de SORTIE y ajoute DEUX par
     * image tant que le joueur s'y tient ; un teleporteur le pose a HUIT d'un coup. La
     * presentation en retire UN a chaque image, quoi qu'il arrive — c'est elle qui donne a
     * l'effet sa marche arriere, et c'est pourquoi ressortir de la zone avant la fin
     * <b>re-materialise</b> le joueur au lieu de l'achever : le compte redescend tout seul.
     *
     * <p>Le solde est donc de +1 par image dans la zone et de -1 dehors. Neuf images a rester
     * suffisent a partir.
     */
    public int teleportFrame;

    /**
     * La boucle doit s'arreter : {@code endlevel} l'attend. {@link Campaign#endLevel()} dira si
     * c'est une victoire ou une mort — la sante seule en decide.
     */
    public boolean finished;

    /**
     * {@code Lvl_ExitZoneID_w} : le secteur par lequel on quitte le niveau.
     *
     * <p>ATTENTION, les sources publiees ne font PAS cela. Elles portent le bloc
     * {@code add.w #2,Game_TeleportFrame_w} en COMMENTAIRE, suivi d'un {@code jmp endlevel} sec
     * (hires.s:2099-2107), avec ce mot d'excuse : « This is a hacky way to do the teleport fx,
     * but it doesn't work now that it's handled by a trigger event. We'll come back to this ».
     * Le niveau s'y acheve donc a l'instant ou l'on entre dans la zone, sans effet.
     *
     * <p>Ce portage suit le JEU, pas le depot : la dematerialisation est bien ce qu'on voit en
     * jouant, et c'est la version que le portage litteral porte deja.
     */
    private void exitZone() {
        if (player.zone == level.exitZone()) {         // cmp.w Lvl_ExitZoneID_w,d0 ; bne noexit
            teleportFrame += 2;                        // add.w #2,Game_TeleportFrame_w
            if (teleportFrame >= 9) {                  // cmp.w #9,... ; blt noexit
                finished = true;                       // jmp endlevel
            }
        }
        // noexit : tst.w Plr1_Health_w ; bgt nnoend1 ; jmp endlevel
        if (health() <= 0) {
            finished = true;
        }
    }

    /**
     * Ce que la presentation fait de {@link #teleportFrame} : elle en retire un, et distord
     * l'image avec ce qui reste ({@code Vid_Present}, c2p).
     *
     * <p>A appeler une fois par image dessinee, et une seule. Un harnais qui ne dessine pas ne
     * l'appelle pas : le compte monte alors sans jamais redescendre, ce qui est exactement ce
     * que fait le jeu quand l'affichage est coupe.
     *
     * @return le pas de l'effet, ou -1 si l'image doit etre montree telle quelle
     */
    public int shimmerStep() {
        if (player.teleported) {                       // tst.b d5 ; bne
            teleportFrame = 8;                         // move.w #8,Game_TeleportFrame_w
        }
        if (teleportFrame == 0) {                      // tst.w ... ; beq
            return -1;
        }
        teleportFrame--;                               // sub.w #1,Game_TeleportFrame_w
        return teleportFrame;
    }

    private boolean previousAction;

    /** Monte un niveau, pret a etre joue. */
    public static World of(char letter) {
        return new World(Data.level(letter), Data.database());
    }

    public World(Level level, Database glf) {
        this.level = level;
        this.glf = glf;
        this.lvl = LevelSim.of(level);
        this.nav = new Nav(level);
        this.los = new Los(lvl, level);
        this.player = new PlayerSim(lvl, level, Data.sinCos());
        this.anims = new Anims(lvl, level);
        this.objects = new ObjectRuntime(lvl, level, glf);
        this.shots = new Shots(lvl, glf, Data.sinCos(), level);
        this.aliens = new Aliens(lvl, level, glf, Data.sinCos(), nav, los);

        objects.inventory.defaultGame(glf);            // 200 de sante, le fusil et vingt cartouches
        objects.sfx = sfx;
        objects.messages = messages;

        // Les sols toxiques et le bruit des pas viennent des definitions, pas du niveau : le
        // secteur ne porte qu'un numero de type de sol.
        for (Database.FloorType f : glf.floors()) {
            player.floorDamage[f.index()] = f.damage();
            player.floorSfx[f.index()] = f.sfx();
        }
        anims.ambientSfx = new int[glf.ambientSfx().size()];
        for (int i = 0; i < anims.ambientSfx.length; i++) {
            anims.ambientSfx[i] = glf.ambientSfx().get(i);
        }

        // Les pieces se tiennent entre elles : le souffle ne traverse pas les murs, les balles du
        // joueur touchent les monstres et celles des monstres le joueur, et l'on bute sur le
        // decor solide comme sur les monstres.
        shots.los = los;
        shots.nav = nav;
        shots.sfx = sfx;
        shots.setTargets(aliens);
        shots.setObjects(objects);                     // les cassables sont des cibles
        shots.setPlayer(player);
        aliens.shots = shots;                          // la gerbe de morceaux a la mort
        aliens.sfx = sfx;
        // Les portes, les ascenseurs et l'ambiance font du bruit eux aussi, et leur file
        // n'etait branchee nulle part : Anims garde un sfx null, et tous ses appels sont
        // gardes par un test de nullite — les sons partaient donc en silence, sans erreur.
        anims.sfx = sfx;
        anims.nav = nav;
        aliens.setFire(shots::fireAtPlayer);
        player.sfx = sfx;
        player.inventory = objects.inventory;          // le jetpack puise son carburant ici
        player.collide = new Collide(lvl, glf, objects);
        player.collide.aliens = aliens;

        anims.run();                                   // pose les toits et les sols de depart
    }

    /**
     * Une image de jeu, dans l'ordre de la boucle d'origine.
     *
     * <p>La simulation avance d'un pas fixe : tous les compteurs du jeu sont en images, et c'est
     * a l'appelant de l'appeler cinquante fois par seconde, comme le balayage de l'ecran le
     * faisait.
     */
    public void frame(Input in) {
        frame++;
        brightTimer--;                                 // Anim_Timer_w, au balayage

        // L'action se prend au FRONT montant : rester appuye n'actionne qu'une fois.
        boolean tap = in.action && !previousAction;
        previousAction = in.action;

        player.beginFrame(1);
        player.forward = in.forward;
        player.backward = in.backward;
        player.stepLeft = in.stepLeft;
        player.stepRight = in.stepRight;
        player.run = in.run;
        player.jump = in.jump;
        player.duckTap = in.duck;
        if (in.turn != 0) {
            player.look(in.turn);
        }

        player.keyboardControl();                      // l'intention
        player.fall();                                 // la pesanteur, le sol, le plafond
        player.control();                              // la collision, puis la position commise

        // donetalking (hires.s:2567) : les luminosites du secteur regarde et de son entourage
        // se REFONT ici, apres le joueur et AVANT l'IA. C'est ce qui laisse les torches des
        // monstres et la lueur des tirs ecrire par-dessus ; l'ordre inverse les effacerait.
        if (light != null) {
            light.frame(player.zone);
        }

        // Un objet encore au sol VERROUILLE les portes dont il tient la cle : ramasser, c'est
        // ouvrir.
        // Anim_DoorAndLiftLocks_l est UN SEUL LONG, et les deux routines n'en lisent pas la
        // meme moitie : DoorRoutine fait « move.w Anim_DoorAndLiftLocks_l,d5 », soit le mot
        // FORT, et LiftRoutine « move.w anim_LiftOnlyLocks_w,d5 », soit le mot FAIBLE qui le
        // suit. Donner le long entier aux portes revenait a les verrouiller avec les cles des
        // ascenseurs, et a ne verrouiller aucun ascenseur.
        int verrous = objects.run(player, tap) | aliens.doorLocks();
        anims.doorLocks = (verrous >>> 16) & 0xFFFF;
        anims.liftLocks = verrous & 0xFFFF;

        shots.tempFrames = 1;
        if (shots.fire(player, objects.inventory, gun, in.fire, in.aim / 100f)) {
            weapon.fire();                             // newplayershoot.s:165, quand la balle PART
        }
        shots.update();                                // le vol des balles

        aliens.tempFrames = 1;
        aliens.run(player);                            // l'IA, apres les balles

        player.floorDamage();                          // les sols qui rongent
        int hurt = player.applyDamage(objects.inventory, nav);
        if (hurt != 0) {
            // hires.s:2308 : le cri du joueur touche.
            sfx.id = 0xFFFA;                           // move.w #$fffa,IDNUM
            sfx.notIfPlaying = false;                  // clr.b notifplaying
            sfx.here(19, 60, 0, 0);                    // le cri
        }

        anims.playerZone = player.zone;
        anims.playerSpaceTap = tap;
        anims.tempFrames = 1;
        anims.run();                                   // les ascenseurs, puis les portes
        player.floorSpd = anims.playerFloorSpd;        // le sol qui se derobe sous les pieds

        // newanims.s:801 : une image sur cinq, les sequences de clignotement avancent. Elles
        // touchent la table de DEPART, donc leur effet n'arrive qu'a l'image suivante.
        if (light != null && brightTimer <= 0) {
            light.tick();
            brightTimer = 5;                           // move.w #5,Anim_Timer_w
        }

        // Plr1_Use puis objmoveanim (hires.s:1668 et 1724) : l'arme en main se repose
        // entierement, puis son animation avance d'un pas. Les deux sont la derniere chose que
        // le jeu fait avant de dessiner, et elles ne lisent que l'etat qu'on vient d'arreter.
        weapon.place(player, glf, gun, health());
        weapon.animate(glf);

        // markVisibleAndWorry (hires.s:2017) : la DERNIERE chose que la boucle fait, apres
        // avoir dessine. Ce qu'elle arme ici, c'est l'image SUIVANTE qui le consomme.
        objects.markVisible(player.zone);
        aliens.seen = objects.seen();

        // hires.s:2084-2116, le pied de game_main_loop : la sortie, puis la sante.
        exitZone();
    }

    /** La sante du joueur. */
    public int health() {
        return objects.inventory.health();
    }

    /** Vrai si le joueur est mort. */
    public boolean dead() {
        return health() <= 0;
    }
}
