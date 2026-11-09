package ab3d2.full.sim;

import ab3d2.full.M68k;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * La VIE DES OBJETS : ce que la boucle fait, a chaque image, de chaque chose posee dans le
 * niveau. Port de {@code ItsAnObject} et de ses quatre familles (newaliencontrol.s:86-530), de
 * {@code Plr1_CheckObjectCollide} / {@code Plr1_CollectItem} et de {@code CheckHit}.
 *
 * <p>Le fichier de niveau ne dit d'un objet que ou il est et de quel genre il est. Tout le
 * reste — l'image qu'il montre, la hauteur a laquelle il flotte, l'angle sous lequel on le
 * voit, les portes qu'il tient fermees — est REFAIT a chaque image, et les quatre familles ne
 * se ressemblent pas :
 *
 * <ul>
 *   <li>le RAMASSABLE tient ses serrures tant qu'il est au sol, et disparait quand on le
 *       prend ({@code ObjT_ZoneID_w = -1}) ;</li>
 *   <li>l'INTERRUPTEUR les tient tant qu'il n'est pas enclenche ;</li>
 *   <li>le DESTRUCTIBLE les tient tant qu'il n'est pas casse ;</li>
 *   <li>le DECOR ne tient rien et se contente de vivre.</li>
 * </ul>
 *
 * <p>Les CLES fonctionnent a l'envers de ce qu'on croit : {@code Anim_DoorAndLiftLocks_l} est
 * reconstruit a chaque image et une chose encore presente y AJOUTE ses bits — la porte est donc
 * verrouillee TANT QUE la chose est la. Ramasser, enclencher, casser : trois facons d'ouvrir.
 *
 * <h2>Le destructible, et sa chute</h2>
 *
 * <p>C'est la seule des quatre familles qui lise les degats qu'elle a pris. Tant qu'ils restent
 * SOUS {@code ODefT_HitPoints_w}, {@code StillHere} la declare vivante, pose ses serrures,
 * cherche le joueur du regard — puis TOMBE dans {@code Decoration}, sans {@code rts} entre les
 * deux. Une chose intacte s'anime donc exactement comme une lampe, et c'est cette chute, qui ne
 * s'ecrit nulle part, qui lui donne son apparence.
 *
 * <p>Passe le seuil, elle dit son texte UNE fois, se declare morte, et joue des lors l'animation
 * d'ACTIVATION — celle qui la montre cassee. C'est tout le mecanisme de l'ordinateur-relais des
 * niveaux m, n et o : sans lui leur porte de sortie ne s'ouvre jamais.
 *
 * <p>Rien ne verrouille cet etat : si les degats retombent sous le seuil, la chose REVIT. Et
 * elle revit avec le pas d'animation ou l'animation de casse en etait — qu'elle va lire dans le
 * script du REPOS. Les deux scripts se croisent par ce seul entier.
 *
 * <h2>Ce que le portage ne calcule pas</h2>
 *
 * <p>Le jeu n'anime que les choses qu'il a VUES : {@code markVisibleAndWorry} (hires.s:2058)
 * arme {@code ShotT_Worry_b} sur celles des secteurs visibles, et les quatre familles
 * commencent par le tester. Ici tout est toujours vu. Seule la PHASE des animations peut donc
 * differer — jamais leur contenu — et une chose reste ramassable dans un secteur que le jeu
 * d'origine n'aurait pas dessine.
 *
 * <p>{@code AI_LookForPlayer1}, que {@code StillHere} appelle pour armer
 * {@code ObjT_SeePlayer_b}, n'est pas porte non plus : il ne servait qu'a l'auto-visee, que ce
 * portage remplace par la geometrie du rayon.
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Trois cent cinq mille etats de chose, six niveaux, trois series, aucun ecart. Sur
 * soixante et une mutations, cinquante-six mordent ; les cinq survivantes se mesurent :
 *
 * <ul>
 *   <li>la hauteur de dessin se TRONQUE au mot, mais le sol d'un secteur decale de sept bits
 *       plus deux fois un decalage d'animation ne sort jamais des seize bits : la troncature
 *       ne mord sur aucune donnee du jeu ;</li>
 *   <li>le pas MONTRE et la HAUTEUR de depart sont tous deux reecrits par la premiere image
 *       avant que quiconque les regarde, et le fichier de niveau laisse de toute facon le mot
 *       de hauteur a zero : les poser autrement ne se voit pas ;</li>
 *   <li>le compteur du refus se compare SIGNE. Pour que cela compte, il faudrait deux cent une
 *       tentatives refusees sur la MEME chose, soit quatre secondes a pietiner la meme trousse
 *       de soins pleine ;</li>
 *   <li>une CLE se ramasse toujours, inventaire plein ou non — et aucune des seize cartes du
 *       jeu ne pose de chose qui tienne une serrure ET ne donne qu'un consommable. Le raccourci
 *       est la, il est juste, et rien ne l'emprunte.</li>
 * </ul>
 */
public final class ObjectRuntime {

    /** ENT_TYPE_* (defs.i:279) : 0 = ramassable, 1 = activable, 2 = destructible, 3 = decor. */
    private static final int ENT_TYPE_COLLECTABLE = 0;
    private static final int ENT_TYPE_ACTIVATABLE = 1;
    private static final int ENT_TYPE_DESTRUCTABLE = 2;
    private static final int ENT_TYPE_DECORATION = 3;

    /** Le type de dessin d'un modele en trois dimensions : seul lui fait tourner l'angle. */
    private static final int GFX_VECTOR = 1;

    private final LevelSim lvl;
    private final Database glf;
    /** Toutes les choses, dans l'ORDRE DU NIVEAU : c'est celui ou la boucle les visite. */
    private final List<Thing> all = new ArrayList<>();
    private final List<Thing> collectables = new ArrayList<>();
    private final List<Thing> activatables = new ArrayList<>();
    private final List<Thing> destructibles = new ArrayList<>();
    private final List<Thing> decorations = new ArrayList<>();
    private final List<Thing> collectedThisFrame = new ArrayList<>();

    /**
     * Une chose du niveau et ce qu'il en advient.
     *
     * <p>Le niveau, lui, ne bouge pas : il dit ce qui est pose et ou, une fois pour toutes. Ce
     * qui change — la chose ramassee, l'interrupteur enclenche, le pas d'animation courant,
     * les degats encaisses — vit ici, comme pour les monstres.
     */
    public static final class Thing {

        /** Ce que le fichier de niveau dit de cette chose. */
        public final Level.Entity entity;
        /** Son rang parmi les entites du niveau : l'ordre ou les balles la rencontrent. */
        public final int rank;
        /** Secteur courant ; passe a -1 quand la chose est ramassee. */
        public int zone;
        /** {@code EntT_ZoneID_w}, recopie du precedent par la boucle avant tout aiguillage. */
        public int entZone;
        /** {@code EntT_WhichAnim_b} : l'interrupteur est enclenche et joue son animation active. */
        public boolean activated;
        /** {@code EntT_Timer2_w} : temps ecoule depuis l'enclenchement. */
        public int activeTimer;
        /** {@code EntT_Timer1_w} : le pas d'animation SUIVANT. */
        public int animStep;
        /** Le pas que l'animation vient d'ecrire — celui qu'on voit a l'image. */
        public int shownStep;
        /** Vrai si ce pas vient du script d'ACTIVATION et non de celui du repos. */
        public boolean shownActive;
        /** Decalage vertical de ce pas, applique en double a la hauteur de dessin. */
        public int animDelta;
        /** {@code EntT_CurrentAngle_w} : un modele CUMULE l'angle de chacun de ses pas. */
        public int angle;
        /** {@code 4(a0)} : la hauteur de dessin, refaite a chaque image. */
        public int height;
        /** {@code EntT_HitPoints_b} : 1 tant que la chose tient, 0 des qu'elle est cassee. */
        public int hitPoints;
        /** {@code EntT_DamageTaken_b} : les degats cumules, sur un OCTET. */
        public int damageTaken;
        /** {@code EntT_ImpactX_w} / {@code EntT_ImpactZ_w} : la poussee du dernier coup recu. */
        public int impactX;
        public int impactZ;
        /** Vrai si l'animation a tourne au moins une fois : sinon le dessin n'a rien a montrer. */
        public boolean drawn;

        Thing(Level.Entity entity, int rank) {
            this.entity = entity;
            this.rank = rank;
            this.zone = entity.zone();
            this.entZone = entity.zone();
            this.animStep = entity.animStart();
            this.shownStep = entity.animStart();
            this.angle = entity.angle();
            this.height = entity.height();
        }

        /** Vrai si cette chose est un destructible encore debout. */
        public boolean standing() {
            return hitPoints != 0;
        }
    }

    public final Inventory inventory;
    /** File de bruitages (null = muet). */
    public SfxQueue sfx;
    /** File de messages (null = muet). */
    public Messages messages;
    /** Les 10 textes du niveau, pour les choses qui portent un {@code EntT_DisplayText_w}. */
    private final java.util.List<String> levelMessages;

    public ObjectRuntime(LevelSim lvl, Level data, Database glf) {
        this.lvl = lvl;
        this.glf = glf;
        this.levelMessages = data.messages();
        this.inventory = new Inventory(Database.MAX_INVENTORY);
        int rank = -1;
        for (Level.Entity o : data.entities()) {
            rank++;
            if (o.kind() != Level.Kind.OBJECT) {
                continue;                              // seuls les OBJETS ont une definition ODefT
            }
            Database.ObjectDef def = glf == null ? null : glf.object(o.def());
            if (def == null) {
                continue;
            }
            Thing t = new Thing(o, rank);
            all.add(t);
            switch (def.behaviour()) {
                case ENT_TYPE_COLLECTABLE -> collectables.add(t);
                case ENT_TYPE_ACTIVATABLE -> activatables.add(t);
                case ENT_TYPE_DESTRUCTABLE -> destructibles.add(t);
                case ENT_TYPE_DECORATION -> decorations.add(t);
                default -> all.remove(t);              // ItsAnObject sort sans rien faire
            }
        }
    }

    /** Toutes les choses du niveau, dans l'ordre ou la boucle les visite. */
    public List<Thing> things() {
        return all;
    }

    /**
     * Le DECOR anime : lampes, flammes, lueurs.
     *
     * <p>Port de {@code Decoration} (newaliencontrol.s:480). C'est la plus courte des quatre
     * routines, et par soustraction : un decor n'est pas ramassable, ne s'enclenche pas, ne se
     * casse pas. Il reste qu'il OCCUPE une hauteur — le sol ou le plafond de son secteur — et
     * qu'il avance son animation.
     *
     * <p>C'est par eux que les MIROITEMENTS arrivent a l'image : les trois definitions d'objet
     * qui portent une lueur — la douzieme, la treizieme et la vingt-et-unieme — sont toutes du
     * decor, et sans cette liste aucune n'aurait d'apparence.
     */
    public List<Thing> decorations() {
        return decorations;
    }

    /** Les choses ramassables du niveau, avec leur etat. */
    public List<Thing> collectables() {
        return collectables;
    }

    /** Les interrupteurs du niveau, avec leur etat. */
    public List<Thing> activatables() {
        return activatables;
    }

    /** Les choses cassables du niveau, avec leur etat. */
    public List<Thing> destructibles() {
        return destructibles;
    }

    /** Choses ramassees a la derniere image (a retirer de la scene). */
    public List<Thing> collected() {
        return collectedThisFrame;
    }

    /**
     * Une image : la boucle d'objets de {@code ObjectHandler}, dans l'ordre du niveau. Renvoie
     * le masque de verrous a donner a {@link Anims#doorLocks}.
     */
    public int run(PlayerSim player, boolean actionTap) {
        collectedThisFrame.clear();
        long locks = 0;
        for (Thing t : all) {
            // newanims.s:1782 : la boucle recopie le secteur AVANT de choisir la famille, et
            // saute les entites retirees.
            t.entZone = t.zone;
            if (t.zone < 0) {
                continue;
            }
            Database.ObjectDef def = glf.object(t.entity.def());
            if (def == null) {
                continue;
            }
            switch (def.behaviour()) {
                case ENT_TYPE_COLLECTABLE -> locks |= collectable(t, def, player);
                case ENT_TYPE_ACTIVATABLE -> locks |= activatable(t, def, player, actionTap);
                case ENT_TYPE_DESTRUCTABLE -> locks |= destructable(t, def);
                default -> decoration(t, def);
            }
        }
        // DoorRoutine lit Anim_DoorAndLiftLocks_l en MOT : c'est le mot FORT du long qui porte
        // les bits de portes (cle 0x04000000 -> bit 10 -> porte 10).
        return (int) ((locks >>> 16) & 0xFFFF);
    }

    /** Collectable (newaliencontrol.s:113). */
    private long collectable(Thing t, Database.ObjectDef def, PlayerSim player) {
        if (t.activated) {
            // GUNHELD : une chose ramassable dont le drapeau d'animation est arme n'est plus au
            // sol, c'est l'arme que le joueur tient. Weapon s'en charge ; rien ici.
            return 0;
        }
        long locks = t.entity.unlocks() & 0xFFFFFFFFL;
        animate(t, def, false);
        if (collide(player, t, def) && collect(t, def)) {
            t.zone = -1;                               // ObjT_ZoneID_w = -1 : retire du niveau
            collectedThisFrame.add(t);
        }
        return locks;
    }

    /**
     * Activatable / ACTIVATED (newaliencontrol.s:194 et 287) : un interrupteur au repos
     * VERROUILLE ses portes ; une fois actionne (joueur a portee + touche action), il joue son
     * animation « active » et relache ses verrous, jusqu'a expiration de
     * {@code ODefT_ActiveTimeout_w} (negatif = jamais) ou nouvelle pression du joueur.
     */
    private long activatable(Thing t, Database.ObjectDef def, PlayerSim player,
            boolean actionTap) {
        if (!t.activated) {
            long locks = t.entity.unlocks() & 0xFFFFFFFFL;
            animate(t, def, false);
            if (collide(player, t, def) && actionTap) {
                collect(t, def);                       // Plr1_CollectItem (ce qu'il donne)
                t.animStep = 0;                        // move.w #0,EntT_Timer1_w
                t.activated = true;                    // st EntT_WhichAnim_b
                t.activeTimer = 0;                     // move.w #0,EntT_Timer2_w
            }
            return locks;
        }
        // ACTIVATED : les serrures sont COMMENTEES dans l'original, un interrupteur enclenche
        // ne tient plus rien.
        animate(t, def, true);
        t.activeTimer = M68k.setw(t.activeTimer, t.activeTimer + 1); // add.w Anim_TempFrames_w
        boolean timeout = def.activeTimeout() >= 0
                && def.activeTimeout() <= (short) t.activeTimer;     // cmp.w ; ble .DEACTIVATE
        if (timeout || (collide(player, t, def) && actionTap)) {
            t.animStep = 0;
            t.activated = false;
        }
        return 0;
    }

    /**
     * Destructable (newaliencontrol.s:370) : la chose cassable.
     *
     * <p>La comparaison est un {@code blt} sur les degats pris : l'EGALITE casse. Et les degats
     * se lisent sur un octet non signe, donc de zero a deux cent cinquante-cinq, alors que le
     * seuil est un mot : une chose de plus de deux cent cinquante-cinq points de vie serait
     * indestructible. Aucune des trois n'en a autant.
     */
    private long destructable(Thing t, Database.ObjectDef def) {
        if ((t.damageTaken & 0xFF) < def.hitPoints()) {
            return stillHere(t, def);
        }
        if (t.hitPoints != 0) {                        // tst.b EntT_HitPoints_b ; beq .alreadydead
            if (t.entity.message() >= 0 && messages != null && levelMessages != null
                    && t.entity.message() < levelMessages.size()) {
                messages.push(levelMessages.get(t.entity.message()));
            }
            t.animStep = 0;                            // move.w #0,EntT_Timer1_w
        }
        // .alreadydead
        t.hitPoints = 0;                               // move.b #0,EntT_HitPoints_b
        if (t.zone < 0) {
            return 0;
        }
        animate(t, def, true);                         // ACTANIMOBJ : la chose cassee
        return 0;
    }

    /**
     * StillHere (newaliencontrol.s:446) : la chose tient encore.
     *
     * <p>Elle se declare vivante, pose ses serrures, et cherche le joueur du regard
     * ({@code AI_LookForPlayer1}, qui arme {@code ObjT_SeePlayer_b} — le portage ne s'en sert
     * pas, sa recherche de cible etant geometrique). Puis elle TOMBE dans {@code Decoration} :
     * il n'y a pas de {@code rts} entre les deux, et c'est cette chute qui lui donne son
     * apparence.
     */
    private long stillHere(Thing t, Database.ObjectDef def) {
        t.hitPoints = 1;                               // move.b #1,EntT_HitPoints_b
        long locks = t.entity.unlocks() & 0xFFFFFFFFL;
        decoration(t, def);                            // (chute)
        return locks;
    }

    /** Decoration (newaliencontrol.s:480). */
    private void decoration(Thing t, Database.ObjectDef def) {
        animate(t, def, false);
    }

    /**
     * DEFANIMOBJ / ACTANIMOBJ (newaliencontrol.s:808 et 872), le bloc commun a toutes les
     * familles : la hauteur se REFAIT depuis le sol (ou le plafond) du secteur, le pas courant
     * y ajoute deux fois son decalage, un modele y ajoute son angle, et l'animation passe au
     * pas suivant.
     *
     * <p>Le pas qu'on VOIT est celui d'avant l'avancee : la routine ecrit les champs de dessin
     * depuis {@code EntT_Timer1_w}, puis y range le suivant. Les deux ne sont pas le meme, et
     * les confondre decale toutes les animations d'une image.
     */
    private void animate(Thing t, Database.ObjectDef def, boolean active) {
        List<Database.AnimStep> script = active ? def.activeAnim() : def.idleAnim();
        if (script == null || script.isEmpty()) {
            return;
        }
        // Les deux scripts font toujours vingt pas et aucun « pas suivant » ne sort de la
        // table : le repli n'a jamais lieu, il garde seulement le portage dans ses bornes la ou
        // l'original lirait la definition voisine.
        int at = Math.floorMod(t.animStep, script.size());
        Database.AnimStep pas = script.get(at);
        t.shownStep = at;
        t.shownActive = active;
        t.animDelta = pas.delta();
        t.height = objectHeight(lvl, t.zone, t.entity.upperZone() != 0, pas.delta(), def);
        if (def.gfxType() == GFX_VECTOR) {             // .vector : add.w d1,EntT_CurrentAngle_w
            t.angle = M68k.setw(t.angle, t.angle + pas.word2()) & 0xFFFF;
        }
        t.animStep = pas.next() & 0xFF;                // moveq #0,d1 ; move.b 5(a3,d0.w),d1
        t.drawn = true;
    }

    /** Plr1_CheckObjectCollide (newaliencontrol.s:727). */
    private boolean collide(PlayerSim player, Thing t, Database.ObjectDef def) {
        // L'etage se compare par OU EXCLUSIF d'octets, et non par oui ou non : une entite qui
        // ne porte ni zero ni deux cent cinquante-cinq n'est d'AUCUN des deux etages, et le
        // niveau n en a une.
        if (((t.entity.upperZone() ^ (player.stoodInTop ? 0xFF : 0)) & 0xFF) != 0) {
            return false;                              // eor.b d0,d1 ; bne .NotSameZone
        }
        if (player.zone != t.zone) {
            return false;
        }
        int d7 = player.yOff + (player.height >> 1);
        d7 = d7 >> 7;
        d7 = (short) (d7 - t.height);
        if (d7 <= 0) {
            d7 = (short) -d7;
        }
        if (d7 > def.collideHeight()) {
            return false;
        }
        // CheckHit : distance au carre < rayon au carre (mots signes).
        int dx = (short) (t.entity.x() - (short) (player.xOff >> 16));
        int dz = (short) (t.entity.z() - (short) (player.zOff >> 16));
        int dist = M68k.muls(dx, dx) + M68k.muls(dz, dz);
        int radius = M68k.muls(def.collideRadius(), def.collideRadius());
        return dist < radius;
    }

    /**
     * Hauteur de dessin d'une chose : {@code worry_about} pose le sol (ou le plafond) de sa
     * zone decale de 7 bits, PUIS l'animation ajoute deux fois le decalage de son pas courant.
     * Sans ce decalage, des ramassages deviennent impossibles : les cartouches de fusil ont une
     * tolerance verticale de 40 alors que le joueur est a 48 du sol.
     */
    public static int objectHeight(LevelSim lvl, int zone, boolean upperZone, int animDelta,
            Database.ObjectDef def) {
        LevelSim.Zone z = zone >= 0 && zone < lvl.zones.length ? lvl.zones[zone] : null;
        if (z == null) {
            return 0;
        }
        int h;
        if (def != null && def.floorCeiling() != 0) {
            h = upperZone ? z.upperRoofH : z.roofH;
        } else {
            h = upperZone ? z.upperFloorH : z.floorH;
        }
        return (short) ((h >> 7) + 2 * animDelta);
    }

    /** Plr1_CollectItem (newaliencontrol.s:600). */
    private boolean collect(Thing t, Database.ObjectDef def) {
        if (t.entity.unlocks() == 0
                && !inventory.canCollect(def.ammoGive(), def.gunGive(), def.gunGiveOverrun())) {
            // Le refus a un COMPTEUR, et ce compteur vit dans l'enregistrement de la chose —
            // chacune a donc le sien. Le jeu ne redit « je ne peux pas en porter plus » qu'une
            // fois sur deux cents tentatives, faute de quoi marcher sur une trousse de soins
            // pleine noierait le bandeau de messages. C'est le MEME mot que le compteur
            // d'activation : une chose ne peut pas etre les deux a la fois.
            if ((short) t.activeTimer <= 0) {          // tst.w EntT_Timer2_w ; bgt .skip
                if (messages != null) {                // Game_CantCollectItemText_vb
                    messages.push("I can't carry any more of these just now.");
                }
                t.activeTimer = 200;                   // move.w #200,EntT_Timer2_w
            }
            t.activeTimer = M68k.setw(t.activeTimer, t.activeTimer - 1); // sub.w #1
            return false;                              // plein : la chose reste au sol
        }
        inventory.add(def.ammoGive(), def.gunGive());
        if (sfx != null) {                             // ODefT_SFX_w (newaliencontrol.s:705)
            // volume 80, identifiant = le POINT de la chose.
            sfx.id = t.entity.point();                 // move.w (a0),IDNUM
            sfx.notIfPlaying = false;                  // clr.b notifplaying
            sfx.at(def.sfx(), 80, t.entity.x(), t.entity.z());
        }
        if (messages != null) {                        // texte du niveau, sinon le NOM
            if (t.entity.message() >= 0 && levelMessages != null
                    && t.entity.message() < levelMessages.size()) {
                messages.push(levelMessages.get(t.entity.message()));
            } else {
                messages.push(def.name());
            }
        }
        return true;
    }
}
