package ab3d2.full.sim;

import static ab3d2.full.M68k.asrw;
import static ab3d2.full.M68k.divs;
import static ab3d2.full.M68k.muls;
import static ab3d2.full.M68k.s16;
import static ab3d2.full.M68k.setw;

import ab3d2.full.M68k;
import ab3d2.full.defs.Database;
import ab3d2.full.level.Level;
import ab3d2.full.SinCos;

import java.util.ArrayList;
import java.util.List;

/**
 * Tir du joueur et vol des projectiles.
 *
 * <p>Port de {@code plrShot} / {@code nothingToShoot} / {@code fireProjectile}
 * (Newplayershoot.java:57-421) et de {@code ItsABullet} (Newanims.java:1650-1925).
 *
 * <p>Le jeu garde un POOL de 20 emplacements de tir ({@code NUM_PLR_SHOT_DATA}) : tirer, c'est
 * trouver le premier libre ({@code ObjT_ZoneID_w < 0}) et le remplir ; quand l'animation d'impact
 * est finie, l'emplacement est rendu ({@code FREE_ENT}). On reproduit ce pool tel quel — c'est lui
 * qui limite le nombre de projectiles en vol, donc la cadence reelle des armes rapides.
 *
 * <h2>L'AUTO-VISEE, et ce qui en a ete garde</h2>
 *
 * <p>Le jeu choisit sa cible AVANT de tirer : {@code CalcPLR1InLine} marque toute entite dans un
 * cone de quatre-vingts unites autour de l'axe de vue, et {@code .find_closest_in_line} retient
 * la plus proche qui soit vivante et vue en ligne directe. Cette recherche-la n'est PAS portee,
 * et ne le sera pas : le portage a une visee souris reelle, il prend donc la premiere entite SUR
 * LE RAYON. C'est le meme ecart assume que pour la hauteur du tir, et c'est le seul.
 *
 * <p>Mais ce que l'auto-visee commandait ENSUITE, lui, est porte a la ligne. Avec une cible
 * retenue, une arme instantanee ne trace aucun rayon : elle tire un de par plomb contre le carre
 * de la distance ({@link #lesPlombs}), et {@code plr1_HitscanSucceded} pose les degats et le
 * recul ({@link #reculX}). Voir {@link ShootCheck} : 3367 coups, quatre niveaux, 0 ecart.
 *
 * <p>Note PERIMEE, corrigee : {@code ComputeBlast} et l'illumination des points au passage
 * ({@code anim_BrightenPoints}) sont portes et branches depuis longtemps — {@link #computeBlast}
 * est appele a l'impact et verifie par {@code blastCheck} ; {@code dynLight.brighten} suit le
 * projectile en vol. Le javadoc disait encore le contraire.
 *
 * <p>Reste hors portage, et c'est un choix : la dispersion verticale aleatoire du rayon sans
 * cible — voir {@link #hitscanNoTarget}.
 */
public final class Shots {

    /** NUM_PLR_SHOT_DATA. */
    public static final int PLR_POOL = 20;
    /**
     * De combien on recule un impact de mur, en unites de position (64 = une unite monde). La
     * moitie d'un panneau d'explosion, en gros : de quoi le decoller de la surface sans qu'il
     * ait l'air de flotter.
     */
    private static final int IMPACT_BACKOFF = 24;

    /** NUM_ALIEN_SHOT_DATA : le jeu donne aux aliens leur PROPRE pool de projectiles. */
    public static final int ALIEN_POOL = 20;
    /** Total des emplacements rendus. */
    public static final int POOL = PLR_POOL + ALIEN_POOL;

    /** Un emplacement du pool (champs ShotT de l'entite d'origine). */
    public static final class Shot {
        /** ObjT_ZoneID_w &gt;= 0 : emplacement occupe. */
        public boolean active;
        public int x, z;                 // Lvl_ObjectPoints : 16.16 (MoveObject ne touche que le mot fort)
        public int accY;                 // ShotT_AccYPos_w (long)
        public int heightWord;           // 4(a0) : hauteur de dessin = AccYPos >> 7
        public int velX, velZ;           // ShotT_VelocityX/Z_w (16.16 par frame)
        public int velY;                 // ShotT_VelocityY_w (mot)
        public int zone;
        public boolean upperZone;        // ShotT_InUpperZone_b
        public int type;                 // ShotT_Size_b : index de definition de balle
        public int lifetime;             // ShotT_Lifetime_w
        public int anim;                 // ShotT_Anim_b : pas d'animation courant
        public boolean popping;          // ShotT_Status_b : animation d'impact en cours
        public int gravity;              // ShotT_Gravity_w
        public int power;                // ShotT_Power_w : degats a l'impact
        public int enemyFlags;           // EntT_EnemyFlags_l : quels types d'entites il touche
        public boolean bounceHoriz;      // ShotT_Flags_w
        public boolean bounceVert;       // ShotT_Flags_w+1

        // --- donnees de DESSIN posees par ItsABullet (9/11/10/8/6 (a0)) ---
        public int sheet;                // 9(a0) : feuille de sprites
        public int frame;                // 11(a0) : frame
        public boolean additive;         // 10(a0) = 6 : melange additif
        public int glareSize;            // 8(a0) : taille du halo (graphicType == 1)
        public int brightness;           // anim_Brightness_w : lumiere emise (non portee)
    }

    private final LevelSim lvl;
    private final Database glf;
    private final Move move;
    private final SinCos sinCos;
    private final List<Shot> pool = new ArrayList<>();
    /** Entites que les balles peuvent toucher (posees par le jeu). */
    private Aliens targets;
    /** Les choses du niveau : seules les CASSABLES comptent comme cibles. */
    private ObjectRuntime objects;
    /**
     * Monstres et choses cassables melanges, dans l'ORDRE DU NIVEAU.
     *
     * <p>L'original ne connait qu'UNE liste d'entites et la parcourt telle quelle
     * (newanims.s:2545) : la premiere qu'il rencontre sur le segment prend le coup. Separer les
     * monstres des choses donnerait une autre cible le jour ou une balle en traverse deux dans
     * la meme image, et c'est exactement ce qui arrive quand on tire dans un ventilateur devant
     * lequel passe un alien.
     */
    private final List<Object> inOrder = new ArrayList<>();
    /** File de bruitages (null = muet). */
    public SfxQueue sfx;
    /** Lumieres dynamiques : un projectile lumineux eclaire la piece (null = aucune). */
    public DynLight dynLight;
    /** Ligne de vue et generateur du jeu : le souffle ne traverse pas les murs. */
    public Los los;
    public Nav nav;

    /** PlrT_TimeToShoot_w : frames restantes avant le prochain tir. */
    public int timeToShoot;
    /** Anim_TempFrames_w : frames ecoulees. La simulation tourne a pas fixe, donc toujours 1. */
    public int tempFrames = 1;
    /** Vrai la frame ou un tir est parti (pour le son et l'animation de l'arme). */
    public boolean fired;
    /** Vrai la frame ou le tir a echoue faute de munitions (SFX 12 dans le jeu). */
    public boolean dryFire;
    /**
     * DIAGNOSTIC SEUL : ce que le dernier rayon instantane a rencontre, ou {@code null}.
     *
     * <p>Le jeu d'origine choisit sa cible par l'auto-visee et le portage par un rayon ; un
     * oracle ne peut comparer les des que sur les coups ou les deux ont designe la MEME entite,
     * et c'est ce champ qui le dit. Rien dans la simulation ne le relit.
     */
    public Object lastHitscanTarget;

    /**
     * Le POINT de chaque emplacement du pool dans la table des points d'objets.
     *
     * <p>Un projectile n'a pas de rang a lui : il occupe un emplacement de la table, et c'est
     * le POINT de cet emplacement que le jeu prend comme identifiant de source sonore. Les deux
     * pools se suivent dans la table, mais leurs points ne valent pas leur rang.
     */
    private final int[] points = new int[POOL];

    /**
     * Le rang du CORPS du joueur dans la table des objets.
     *
     * <p>Le jeu n'a qu'une boucle : {@code notasplut} parcourt la table d'un bout a l'autre, et
     * le corps du joueur y a son rang comme une caisse ou un monstre. C'est la PREMIERE entite
     * retenue qui arrete tout — donc, selon le niveau, tantot le monstre, tantot le joueur.
     */
    private final int playerRank;

    /**
     * {@code MOVING} (newanims.s:2686) : la balle a-t-elle VOULU bouger cette image ?
     *
     * <p>Le fanion est pose AVANT la collision, d'apres les positions voulues, et c'est lui que
     * le test de hauteur consulte. Une balle arretee net par un mur a donc bouge pour ce
     * test-la, bien que son segment soit reduit a un point ; une balle de vitesse nulle, elle,
     * n'a pas bouge, et touche alors a n'importe quelle hauteur.
     */
    private boolean moving;

    public Shots(LevelSim lvl, Database glf, SinCos sinCos, Level data) {
        this.lvl = lvl;
        this.glf = glf;
        this.sinCos = sinCos;
        this.move = new Move(lvl);
        this.playerRank = data.playerEntity();
        for (int i = 0; i < POOL; i++) {
            pool.add(new Shot());
            int slot = i < PLR_POOL ? data.playerShotEntity() + i
                    : data.alienShotEntity() + (i - PLR_POOL);
            points[i] = slot >= 0 && slot < data.entities().size()
                    ? data.entities().get(slot).point() : i;
        }
    }

    public List<Shot> shots() {
        return pool;
    }

    /**
     * Harnais : repose les reglages de collision que le jeu laisse derriere un projectile en
     * vol.
     *
     * <p>Ce sont des GLOBALES dans l'original, et les flammes d'une explosion s'en servent
     * sans les redefinir : elles heritent de la derniere balle qui a vole. Le jeu n'a donc
     * jamais a les poser, mais un oracle qui rejoue une explosion seule, si.
     *
     * <p>Ces QUATRE-la seulement. Le rayon et le decalage d'arete sont poses par la flamme
     * elle-meme, et le rebond par {@code ComputeBlast} avant la premiere : il n'y a que ces
     * quatre reglages qu'une explosion prenne vraiment a la balle qui l'a precedee.
     */
    void bulletMoveDefaults() {
        move.wallFlags = 1024;
        move.stepUp = 0;
        move.stepDown = 16777216;
        move.thingHeight = 1280;
    }

    /**
     * Branche les CHOSES : sans elles une balle ne peut casser ni ordinateur ni ventilateur,
     * et trois niveaux n'ont pas de sortie.
     */
    public void setObjects(ObjectRuntime o) {
        this.objects = o;
        rebuildOrder();
    }

    /** Refait la liste melangee, une fois pour toutes : ni les monstres ni les choses ne
     *  changent de rang. */
    private void rebuildOrder() {
        inOrder.clear();
        if (targets != null) {
            inOrder.addAll(targets.aliens());
        }
        if (objects != null) {
            inOrder.addAll(objects.destructibles());
        }
        inOrder.sort(java.util.Comparator.comparingInt(Shots::rankOf));
    }

    /** Le rang d'une cible dans le niveau. */
    private static int rankOf(Object o) {
        return o instanceof Aliens.Alien a ? a.index : ((ObjectRuntime.Thing) o).rank;
    }

    /** Branche les monstres : sans eux une balle ne peut toucher que les murs. */
    public void setTargets(Aliens a) {
        this.targets = a;
        rebuildOrder();
    }

    /** Nombre d'emplacements ALIEN occupes (validation). */
    public int alienShotsActive() {
        int n = 0;
        for (int i = PLR_POOL; i < pool.size(); i++) {
            if (pool.get(i).active) {
                n++;
            }
        }
        return n;
    }

    /** Branche le joueur : c'est la cible des tirs d'alien. */
    public void setPlayer(PlayerSim p) {
        this.player = p;
    }

    private PlayerSim player;

    // ------------------------------------------------------------------ tir

    /**
     * plrShot : cadence, munitions, puis tir. {@code fire} = touche de tir enfoncee
     * (PlrT_TmpFire_b), {@code aimSpeed} = visee verticale (PlrT_AimSpeed_l).
     *
     * @return vrai si un tir est parti
     */
    public boolean fire(PlayerSim player, Inventory inv, int gun, boolean fire,
                        float aimSlope) {
        fired = false;
        dryFire = false;
        if (timeToShoot != 0) {                        // tst.w PlrX_TimeToShoot_w ; beq .can_fire
            timeToShoot = s16(timeToShoot - tempFrames); // sub.w Anim_TempFrames_w
            if ((short) timeToShoot < 0) {
                timeToShoot = 0;
            }
            return false;                              // .no_fire
        }
        // .can_fire
        Database.Gun g = glf == null ? null : glf.gun(gun);
        if (g == null) {
            return false;
        }
        Database.BulletDef b = glf.bullet(g.shoot().bulletType());
        if (b == null) {
            return false;
        }
        int bulType = g.shoot().bulletType();                    // BULTYPE
        int ammoInMyGun = inv.ammo(bulType);           // AmmoInMyGun
        int bulletSpd = b.speed();                       // BulletSpd (mot bas de BulT_Speed_l)
        if (!fire) {                                   // tst.b PlrX_TmpFire_b ; beq .no_fire
            return false;
        }

        // La RECHERCHE de cible du jeu (CalcPLR1InLine + .find_closest_in_line) n'est pas
        // portee : on part donc toujours en .nothing_to_shoot, et le rayon de visee designe la
        // cible. Ce que l'auto-visee commandait ensuite — le de de precision et le recul — est
        // porte, lui, dans hitscanNoTarget. Il n'y a pas d'aide a la visee.
        if (ammoInMyGun < g.shoot().bulletCount()) {             // cmp.w d1,d2 ; bge .okcanshoot
            dryFire = true;
            if (sfx != null) {
                // newplayershoot.s:149 : le clic a vide. L'identifiant n'ecrit que son
                // octet de POIDS FORT, et l'octet bas garde celui du bruit precedent.
                sfx.idHigh(0xFB);                      // move.b #$fb,IDNUM
                sfx.notIfPlaying = false;              // clr.b notifplaying
                sfx.at(12, 100, s16(player.xOff >> 16), s16(player.zOff >> 16));
            }
            return false;
        }
        // .okcanshoot
        timeToShoot = g.shoot().delay();
        if (sfx != null) {
            // newplayershoot.s:180 : le coup part. Volume 300, trois fois celui du clic.
            sfx.idHigh(0xFB);                                    // move.b #$fb,IDNUM
            sfx.notIfPlaying = false;                            // clr.b notifplaying
            sfx.at(g.shoot().sfx(), 300, s16(player.xOff >> 16), s16(player.zOff >> 16));
        }                         // move.w ShootT_Delay_w(a6),PlrX_TimeToShoot_w
        inv.setAmmo(bulType, s16(ammoInMyGun - g.shoot().bulletCount()));
        fired = true;

        // ECART ASSUME. Le jeu derive la vitesse verticale de PlrX_AimSpeed_l, exprimee en
        // LIGNES D'ECRAN (« asr.w #8-BulletSpd »). Elle depend donc de l'ouverture de la vue et
        // SATURE des que le tangage depasse la demi-ouverture — sur Amiga c'etait sans objet,
        // la visee ne pouvant pas depasser le bord de l'ecran. En 3D le tir doit suivre le
        // rayon de visee a n'importe quelle inclinaison : on fait donc TOURNER le vecteur
        // vitesse du tangage, ce qui en conserve la norme et ne sature rien.
        if (b.hitScan() == 0) {                          // tst.w BulT_IsHitScan_l+2(a5) ; beq FireProjectile
            fireProjectile(player, g, b, bulType, bulletSpd, aimSlope);
        } else {
            hitscanNoTarget(player, bulType, aimSlope, g.shoot().bulletCount());
        }
        return true;
    }

    /** plrX_FireProjectile + firefive : {@code bulletCount} projectiles en eventail. */
    private void fireProjectile(PlayerSim player, Database.Gun g, Database.BulletDef b,
                                int bulType, int bulletSpd, float aimSlope) {
        // Le tangage decompose en cosinus (ce qui reste a l'horizontale) et sinus (la montee).
        double t = aimSlope;
        double invLen = 1.0 / Math.sqrt(1.0 + t * t);
        double cosPitch = invLen;
        double sinPitch = t * invLen;
        // Une unite monde vaut 64 unites horizontales et 8192 de hauteur ; la vitesse
        // horizontale par frame vaut (amplitude << bulletSpd) / 65536, soit 2^bulletSpd / 2.
        // La composante verticale qui donne la meme pente vaut donc 64 * 2^bulletSpd * sin.
        // La hauteur croit vers le BAS, d'ou le signe.
        int bulYSpd = (int) Math.round(-64.0 * (1 << (bulletSpd & 15)) * sinPitch);
        int d5 = setw(0, g.shoot().bulletCount());               // move.w ShootT_BulCount_w(a6),d5
        int d6 = setw(0, d5);                          // move.w d5,d6
        d6 = setw(d6, d6 - 1);                         // subq #1,d6
        d6 = setw(d6, d6 << 7);                        // asl.w #7,d6
        d6 = setw(d6, -s16(d6));                       // neg.w d6
        d6 = setw(d6, d6 + player.angPos);             // add.w tempangpos,d6
        d6 = setw(d6, d6 & SinCos.MASK);               // AMOD_A d6

        int tempXOff = s16(player.xOff >> 16);         // tempxoff (mot fort de PlrX_XOff_l)
        int tempZOff = s16(player.zOff >> 16);
        int tempYOff = player.yOff + 10 * 128;         // tempyoff

        while (true) {                                 // firefive
            Shot s = free();
            if (s == null) {
                return;                                // plus d'emplacement : le jeu abandonne aussi
            }
            s.active = true;
            s.popping = false;                         // (emplacement libere avec Status = 0)
            s.gravity = b.gravity();                     // move.w BulT_Gravity_l+2(a5),ShotT_Gravity_w(a0)
            s.bounceHoriz = b.bounceHoriz() != 0;        // ShotT_Flags_w
            s.bounceVert = b.bounceVert() != 0;          // ShotT_Flags_w+1
            // Le jeu borne ici bulyspd a +/-20*128. Cette borne etait un filet : avec son
            // aimSpeed plafonne, la valeur ne pouvait de toute facon pas la depasser. Depuis
            // que la vitesse vient de la pente reelle, elle mordrait des 39 degres de tangage
            // et rabattrait le tir sous le reticule — on ne la reprend donc pas.
            s.type = bulType;                          // move.b BULTYPE+1,ShotT_Size_b(a0)
            s.power = b.hitDamage() & 0xFF;              // move.b BulT_HitDamage_l+3(a5),ShotT_Power_w(a0)
            s.enemyFlags = 0b100011;                   // move.l #%100011,EntT_EnemyFlags_l (joueur 1)
            s.x = tempXOff << 16;                      // move.w tempxoff,(a1)
            s.z = tempZOff << 16;                      // move.w tempzoff,4(a1)
            int sin = s16(sinCos.value(d6));           // move.w (a1,d6.w),d0 ; ext.l d0
            int cos = s16(sinCos.value(d6 + SinCos.COSINE_OFFSET)); // move.w COSINE_OFS(a1,d6.w),d2
            d6 = setw(d6, d6 + 256);                   // add.w #256,d6
            d6 = setw(d6, d6 & SinCos.MASK);           // AMOD_A d6
            // L'horizontale est reduite du cosinus du tangage : le vecteur tourne, il ne
            // s'allonge pas.
            s.velX = (int) Math.round((double) (sin << (bulletSpd & 31)) * cosPitch);
            s.velZ = (int) Math.round((double) (cos << (bulletSpd & 31)) * cosPitch);
            s.velY = s16(bulYSpd);                     // move.w bulyspd,ShotT_VelocityY_w(a0)
            s.upperZone = player.stoodInTop;
            s.lifetime = 0;
            s.zone = player.zone;                      // move.w (a2),ObjT_ZoneID_w(a0)
            s.anim = 0;
            s.accY = tempYOff + 20 * 128;              // move.l tempyoff,d0 ; add.l #20*128,d0
            s.heightWord = s16(s.accY >> 7);           // asr.l #7,d0 ; move.w d0,4(a0)
            d5 = setw(d5, d5 - 1);                     // sub.w #1,d5
            if ((short) d5 <= 0) {                     // bgt firefive
                return;
            }
        }
    }

    /**
     * {@code .nothing_to_shoot}, branche HITSCAN (fusil, mitrailleuse) : un rayon part droit
     * devant, avance par pas doubles jusqu'a toucher un mur, et l'impact y est marque par un tir
     * deja en animation d'explosion ({@code Status = 1}).
     */
    private void hitscanNoTarget(PlayerSim player, int bulType, float aimSlope, int bulletCount) {
        int oldX = s16(player.xOff >> 16);             // move.w PlrX_XOff_l,oldx
        int oldZ = s16(player.zOff >> 16);
        int d0 = setw(0, sinCos.sin(player.angPos));   // move.w PlrX_SinVal_w,d0
        d0 = asrw(d0, 7);                              // asr.w #7,d0
        int newX = setw(0, d0 + oldX);                 // add.w oldx,d0 ; move.w d0,newx
        d0 = setw(0, sinCos.cos(player.angPos));       // move.w PlrX_CosVal_w,d0
        d0 = asrw(d0, 7);
        int newZ = setw(0, d0 + oldZ);
        int oldY = player.yOff + 10 * 128;             // move.l PlrX_YOff_l,d0 ; add.l #10*128,d0
        // ECART ASSUME. Ici le jeu fait « move.w #0,bulyspd » : le tir hitscan sans cible part a
        // PLAT, et c'est son auto-visee (non portee) qui rattrapait la hauteur. Sur Amiga cela ne
        // se voyait pas — regarder en haut y decale l'image sans incliner la vue. En 3D avec une
        // vraie visee souris, un rayon horizontal ne pourrait toucher que ce qui est pile a
        // hauteur d'oeil : on lui donne donc la pente de la visee.
        //
        // Conversion : une unite monde vaut 64 unites de position horizontale et 8192 unites de
        // hauteur, d'ou le facteur 128. La hauteur croit vers le BAS, d'ou le signe.
        moving = true;                                 // le rayon hitscan bouge toujours
        int stepDist = (int) Math.round(Math.sqrt(
                (double) s16(newX - oldX) * s16(newX - oldX)
                        + (double) s16(newZ - oldZ) * s16(newZ - oldZ)));
        int newY = oldY - Math.round(aimSlope * 128f * stepDist);
        // LA DISPERSION VERTICALE N'EST PAS REPRISE, et la raison a change.
        //
        // Le jeu fait ici « jsr GetRand ; and.w #$fff,d0 ; sub.w #$800,d0 ; add.l d0,d1 » : le
        // point d'arrivee du rayon monte ou descend de deux mille unites au hasard. L'ancienne
        // note invoquait l'absence d'un generateur commun ; il y en a un depuis
        // (voir lesPlombs), donc ce n'est plus l'obstacle.
        //
        // La vraie raison est que ce tirage appartient au tir A PLAT. Le jeu n'arrive ici que
        // s'il n'a RETENU AUCUNE CIBLE, et son rayon part alors horizontalement : la dispersion
        // est ce qui lui donne une chance de marquer un mur ailleurs qu'a hauteur d'oeil. Le
        // portage, lui, pointe le rayon ou le joueur vise — c'est l'ecart assume ci-dessus.
        // Ajouter la dispersion par-dessus une visee reelle ne restituerait rien : elle
        // rendrait au hasard ce que la visee vient justement d'oter.
        //
        // Elle coute aussi un tirage : le jeu en consomme UN ici et AUCUN dans
        // .fire_hitscanned_bullets, alors que le portage ne connait qu'un seul chemin. Le
        // reprendre decalerait le generateur de shootCheck, qui compare son etat apres coup.

        move.oldx = s16(oldX);
        move.oldz = s16(oldZ);
        move.newx = s16(newX);
        move.newz = s16(newZ);
        move.oldy = oldY;
        move.newy = newY;
        move.zone = player.zone;
        move.stoodInTop = player.stoodInTop;
        setupBulletMove();
        move.thingHeight = 0;                          // move.l #0,thingheight (vs 10*128 en vol)
        int guard = 0;
        Object struck = null;
        while (true) {                                 // .again
            // La zone AVANT le pas : c'est celle d'ou part le segment qu'on va tester. Prendre
            // celle d'apres laisserait passer un monstre des que le rayon change de zone.
            int segZone = move.zone;
            move.moveObject();
            // ECART ASSUME, et le seul de ce chemin. Le jeu decide AVANT de tirer quelle
            // entite est alignee, par son auto-visee ; le portage a une visee souris et teste
            // les entites le long du RAYON, segment par segment. C'est la geometrie seule qui
            // designe la cible — mais une fois designee, c'est le DE du jeu qui decide si le
            // coup porte (voir lesPlombs).
            struck = firstOnSegment(s16(move.oldx), s16(move.oldz), s16(move.newx),
                    s16(move.newz), s16(move.newy >> 7), segZone, move.zone,
                    player.stoodInTop);
            if (struck != null || move.hitwall || ++guard > 64) { // tst.b hitwall ; bne .nofurther
                break;
            }
            advanceMove();                             // (new - old) * 2 sur x/z/y
        }
        // .nofurther : le DE, puis la marque d'impact
        lastHitscanTarget = struck;
        Database.BulletDef bul = glf == null ? null : glf.bullet(bulType);
        int pellets = Math.max(1, bulletCount);
        int degats = bul == null ? 0 : (bul.hitDamage() & 0xFF);
        if (struck != null) {
            int touches = lesPlombs(player, struck, pellets);
            if (touches > 0) {
                // add.b d0,EntT_DamageTaken_b(a4) : un octet, une fois par plomb qui porte.
                int mal = (degats * touches) & 0xFF;
                if (struck instanceof Aliens.Alien a) {
                    targets.hit(a, mal);
                    a.impactX = reculX(player);
                    a.impactZ = reculZ(player);
                } else {
                    ObjectRuntime.Thing t = (ObjectRuntime.Thing) struck;
                    damage(t, mal);
                    t.impactX = reculX(player);
                    t.impactZ = reculZ(player);
                }
            } else {
                // TOUS les plombs ont rate. Le jeu passe alors par plr1_HitscanFailed, qui
                // lance un rayon vers le MILIEU du segment joueur-cible et le fait avancer
                // jusqu'au premier mur : la marque se pose DERRIERE la cible, pas dessus. On
                // reprend donc le balayage la ou il s'etait arrete, en ignorant les entites.
                struck = null;
                while (!move.hitwall && ++guard <= 64) { // .again
                    advanceMove();
                    move.moveObject();
                }
            }
        }
        Shot s = free();
        if (s == null) {
            return;
        }
        s.active = true;
        // L'impact se pose sur le monstre touche, sinon la ou le rayon a rencontre le mur.
        //
        // Sur un mur on le RECULE le long du rayon : le panneau d'explosion est centre sur le
        // point d'impact, donc pose pile sur la surface il s'enfonce a moitie dedans et le test
        // de profondeur en mange la moitie.
        int ix = s16(move.newx);
        int iz = s16(move.newz);
        if (struck == null) {
            int dx = s16(ix - s16(move.oldx));
            int dz = s16(iz - s16(move.oldz));
            int len = sqrt(muls(dx, dx) + muls(dz, dz));
            if (len > 0) {
                ix = s16(ix - divs(muls(dx, IMPACT_BACKOFF), len));
                iz = s16(iz - divs(muls(dz, IMPACT_BACKOFF), len));
            }
        }
        s.x = (struck != null ? targetX(struck) : ix) << 16;  // move.w newx,(a1,d2.w*8)
        s.z = (struck != null ? targetZ(struck) : iz) << 16;
        s.popping = true;                              // move.b #1,ShotT_Status_b(a0)
        s.gravity = 0;
        s.velX = 0;
        s.velZ = 0;
        s.velY = 0;
        s.type = bulType;                              // move.b BULTYPE+1,ShotT_Size_b(a0)
        s.anim = 0;
        s.lifetime = 0;
        s.zone = move.zone;                            // move.w (a1),ObjT_ZoneID_w(a0)
        s.upperZone = move.stoodInTop;
        s.accY = move.wallhitheight;                   // move.l wallhitheight,ShotT_AccYPos_w(a0)
        s.heightWord = s16(s.accY >> 7);
    }

    /**
     * LE DE DU TIR INSTANTANE : {@code .fire_hitscanned_bullets} (newplayershoot.s:215).
     *
     * <p>Avec une cible alignee, le jeu ne trace AUCUN rayon. Il tire un de par plomb, et le
     * seuil est le carre de la distance a plat :
     *
     * <pre>
     *   jsr GetRand ; and.w #$7fff,d0 ; ext.l d0 ; asl.l #1,d0
     *   d1 = (tx - Plr1_XOff)^2 + (tz - Plr1_ZOff)^2 ; asr.l #6,d1
     *   cmp.l d1,d0 ; bgt .hit
     * </pre>
     *
     * <p>Le plus grand tirage possible vaut {@code $fffe} : des que le carre de la distance
     * divise par soixante-quatre le depasse — soit a 2048 unites — le coup ne peut PLUS porter,
     * quoi qu'on vise. Entre les deux, la chance decroit comme {@code 1 - (d/2048)^2} : cent
     * pour cent a bout portant, trois quarts a mille vingt-quatre, la moitie a mille quatre cent
     * quarante-huit. Mesure sur l'oracle : 3229 coups, quatre niveaux, la falaise au bon
     * endroit.
     *
     * <p>Le meme de est roule par les monstres ({@code ai_AttackWithHitScan}), mais avec
     * {@code asl.l #2} : ils sont DEUX FOIS plus precis que le joueur a egale distance, et leur
     * portee utile va a 2896 unites. L'asymetrie est dans le jeu d'origine.
     *
     * <p>ECART ASSUME, et c'est le seul. Le jeu choisit sa cible par l'AUTO-VISEE
     * ({@code CalcPLR1InLine} + {@code .find_closest_in_line}) : l'entite vivante la plus proche
     * dans un cone de quatre-vingts unites autour de l'axe de vue, vue en ligne directe. Le
     * portage a une visee souris reelle et prend la premiere entite SUR LE RAYON — c'est l'ecart
     * deja assume pour la hauteur du tir. Seul le choix de la cible differe ; le dosage, lui,
     * est celui du jeu.
     *
     * <p>Le filtre vertical de l'auto-visee n'est donc pas repris, et il ne le merite pas :
     * {@code muls #93,d2} est une multiplication sur un MOT appliquee a un LONG
     * ({@code |dy| * 128}). Passe 512 unites d'ecart de hauteur, la valeur deborde et change de
     * signe — un monstre a mille unites au-dessus du joueur est TOUJOURS visable, un monstre a
     * deux cents ne l'est qu'a partir de 581 unites de profondeur. L'oracle le mesure.
     *
     * @return combien de plombs ont porte
     */
    private int lesPlombs(PlayerSim player, Object cible, int pellets) {
        int tx = targetX(cible);
        int tz = targetZ(cible);
        int dx = s16(tx - s16(player.xOff >> 16));     // move.w (a1),d1 ; sub.w Plr1_XOff_l,d1
        int dz = s16(tz - s16(player.zOff >> 16));     // move.w 4(a1),d2 ; sub.w Plr1_ZOff_l,d2
        int d1 = muls(dx, dx) + muls(dz, dz);          // muls d1,d1 ; muls d2,d2 ; add.l d2,d1
        d1 = d1 >> 6;                                  // asr.l #6,d1
        int touches = 0;
        for (int n = 0; n < pellets; n++) {            // .fire_hitscanned_bullets
            int d0 = nav == null ? 0x7FFF : nav.rand(); // jsr GetRand
            d0 = d0 & 0x7FFF;                          // and.w #$7fff,d0
            d0 = ((short) d0) << 1;                    // ext.l d0 ; asl.l #1,d0
            if (d0 > d1) {                             // cmp.l d1,d0 ; bgt .hit
                touches++;
            }
        }
        return touches;
    }

    /**
     * Le recul que {@code plr1_HitscanSucceded} pose sur sa cible : la DIRECTION DE TIR,
     * decalee de treize bits.
     *
     * <pre>move.w tempxdir,d1 ; ext.l d1 ; asl.l #3,d1 ; swap d1 ; move.w d1,EntT_ImpactX_w(a4)</pre>
     *
     * <p>C'est une pichenette, et c'est voulu : le sinus tient sur quinze bits, donc le recul
     * d'un coup de fusil vaut entre moins quatre et trois unites, la ou celui d'un projectile
     * vaut sa vitesse entiere. Le seul endroit du jeu qui le relise est
     * {@code Anim_ExplodeIntoBits}, qui en donne la moitie a chaque morceau : un monstre abattu
     * au fusil s'eparpille presque sur place, un monstre creve a la roquette part au loin.
     */
    private int reculX(PlayerSim player) {
        return s16((s16(sinCos.sin(player.angPos)) << 3) >> 16); // tempxdir
    }

    private int reculZ(PlayerSim player) {
        return s16((s16(sinCos.cos(player.angPos)) << 3) >> 16); // tempzdir
    }

    /** Bloc commun d'init avant MoveObject (exitfirst..Obj_ZonePtr_l). */
    private void setupBulletMove() {
        move.exitFirst = true;                         // st exitfirst
        move.wallBounce = false;                       // clr.b Obj_WallBounce_b
        move.extLen = 0;                               // move.w #0,Obj_ExtLen_w
        move.awayFromWall = 0xFF;                      // move.b #$ff,Obj_AwayFromWall_b
        move.wallFlags = 0b0000010000000000;           // move.w #%0000010000000000,wallflags
        move.stepUp = 0;                               // move.l #0,StepUpVal
        move.stepDown = 0x1000000;                     // move.l #$1000000,StepDownVal
        move.thingHeight = 10 * 128;                   // move.l #10*128,thingheight
    }

    /** Avance (new - old) * 2 sur x/z (mots) et y (long) entre deux passes de MoveObject. */
    private void advanceMove() {
        int d0 = s16(s16(move.newx) - s16(move.oldx));
        move.oldx = s16(move.oldx + d0);
        move.newx = s16(move.newx + d0);
        d0 = s16(s16(move.newz) - s16(move.oldz));
        move.oldz = s16(move.oldz + d0);
        move.newz = s16(move.newz + d0);
        int d0l = move.newy - move.oldy;
        move.oldy = move.oldy + d0l;
        move.newy = move.newy + d0l;
    }

    /**
     * SHOOTPLAYER1 (newaliencontrol.s:1039) : le monstre a tire au juge sur le joueur et l'a
     * RATE. On trace quand meme le rayon pour marquer l'impact sur ce qu'il y a derriere.
     *
     * <p>La direction part du monstre vers la position VISEE du joueur, puis un tirage
     * {@code GetRand >> 4} la fait devier perpendiculairement (et decale aussi la hauteur
     * d'arrivee) — c'est ce qui donne l'impression de balles qui frolent. Le rayon avance par
     * pas doubles jusqu'au premier mur, et l'impact y est pose comme un tir du pool JOUEUR deja
     * en animation d'explosion, de type de balle 0 (le jeu ecrit {@code move.b #0,ShotT_Size_b}).
     *
     * @return true si un impact a pu etre pose
     */
    public boolean shootPlayer1(Aliens.Alien a, PlayerSim p) {
        int oldX = s16(a.x);                           // move.w (a1),oldx
        int oldZ = s16(a.z);                           // move.w 4(a1),oldz
        int newX = s16(p.snapXOff >> 16);              // move.w Plr1_TmpXOff_l,newx
        int newZ = s16(p.snapZOff >> 16);
        int d1 = s16(newX - oldX);
        int d2 = s16(newZ - oldZ);
        int d0 = nav == null ? 0 : asrw(setw(0, nav.rand()), 4);   // GetRand ; asr.w #4,d0
        d0 = s16(d0);
        int devZ = s16(muls(d0, d1) >> 16);            // muls d0,d1 ; swap d1
        int devX = s16(muls(d0, d2) >> 16);            // muls d0,d2 ; swap d2
        newZ = s16(newZ + devZ);                       // add.w d1,newz
        newX = s16(newX - devX);                       // sub.w d2,newx

        int y = (p.snapYOff + 15 * 128) >> 7;          // add.l #15*128 ; asr.l #7
        int yw = setw(0, y);
        int dy = s16(muls(d0, s16(yw)) >> 16);         // move.w d1,d2 ; muls d0,d2 ; swap d2
        yw = setw(yw, yw + dy);                        // add.w d2,d1
        int newY = s16(yw) << 7;                       // ext.l d1 ; asl.l #7,d1
        int oldY = s16(a.height) << 7;                 // move.w 4(a0),d1 ; ext.l ; asl.l #7

        move.oldx = oldX;
        move.oldz = oldZ;
        move.newx = newX;
        move.newz = newZ;
        move.oldy = oldY;
        move.newy = newY;
        move.zone = a.zone;
        move.stoodInTop = a.upperZone;
        setupBulletMove();
        move.thingHeight = 0;                          // move.l #0,thingheight
        int guard = 0;
        while (true) {                                 // .again
            move.moveObject();
            if (move.hitwall || ++guard > 64) {        // tst.b hitwall ; bne .nofurther
                break;
            }
            advanceMove();
        }
        // .foundonefree2 : l'impact, dans le pool du JOUEUR
        Shot s = free();
        if (s == null) {
            return false;                              // .findonefree2 echoue : le jeu abandonne
        }
        s.active = true;
        s.x = s16(move.newx) << 16;
        s.z = s16(move.newz) << 16;
        s.popping = true;                              // move.b #1,ShotT_Status_b(a0)
        s.gravity = 0;                                 // move.w #0,ShotT_Gravity_w(a0)
        s.type = 0;                                    // move.b #0,ShotT_Size_b(a0)
        s.anim = 0;                                    // move.b #0,ShotT_Anim_b(a0)
        s.lifetime = 0;
        s.power = 0;                                   // (l'original laisse la valeur precedente :
        s.enemyFlags = 0;                              //  sans effet, un tir en Status 1 ne touche rien)
        s.velX = 0;
        s.velZ = 0;
        s.velY = 0;
        s.zone = move.zone;                            // move.w (a1),ObjT_ZoneID_w(a0) : zone d'impact
        s.upperZone = move.stoodInTop;
        s.accY = move.wallhitheight;                   // move.l wallhitheight,ShotT_AccYPos_w(a0)
        s.heightWord = s16(s.accY >> 7);
        return true;
    }

    private Shot free() {                              // .findonefree (pool du joueur)
        for (int i = 0; i < PLR_POOL; i++) {
            if (!pool.get(i).active) {
                return pool.get(i);
            }
        }
        return null;
    }

    /** .findonefree du pool ALIEN (AI_AlienShotDataPtr_l). */
    private Shot freeAlien() {
        for (int i = PLR_POOL; i < pool.size(); i++) {
            if (!pool.get(i).active) {
                return pool.get(i);
            }
        }
        return null;
    }

    /**
     * FireAtPlayer1 (Newaliencontrol.java:924) : l'alien crache un projectile vers le joueur.
     *
     * <p>Il ne vise pas ou il est, mais ou il SERA : la position est avancee de
     * {@code XDiff * distance / vitesse / 16}. {@code offMult} decale ensuite le depart sur le
     * cote (les gros monstres tirent depuis une epaule), et la vitesse verticale est calculee pour
     * arriver a la tete du joueur ({@code 4(a2) - 20}) au bout du vol.
     */
    public void fireAtPlayer(Aliens.Alien a, PlayerSim p, int bulletType, int shotSpeed,
                             int shotShift, int yOff, int offMult) {
        Shot s = freeAlien();
        if (s == null) {
            return;                                    // .cantshoot
        }
        Database.BulletDef b = glf == null ? null : glf.bullet(bulletType);
        if (b == null) {
            return;
        }
        if (sfx != null) {
            // newaliencontrol.s:1171 : volume 100, voie 1, identifiant = le POINT de l'alien
            // — et AUCUN numero d'echantillon. Le tir joue ce que le bruit precedent a
            // laisse ; voir SfxQueue.num.
            sfx.id = a.point;                          // move.w (a0),IDNUM
            sfx.notIfPlaying = false;                  // clr.b notifplaying
            sfx.again(100, a.x, a.z);                  // move.w #100,Aud_NoiseVol_w
        }
        int oldX = a.x;
        int oldZ = a.z;
        int newX = s16(p.xOff >> 16);
        int newZ = s16(p.zOff >> 16);

        int dist = calcDist(oldX, oldZ, newX, newZ);   // CalcDist -> distaway
        int d6 = muls(p.xDiff, dist);                  // anticipation en X
        d6 = shotSpeed == 0 ? 0 : divs(d6, shotSpeed);
        d6 = asrw(setw(0, d6), 4);
        newX = s16(newX + s16(d6));
        d6 = muls(p.zDiff, dist);                      // anticipation en Z
        d6 = shotSpeed == 0 ? 0 : divs(d6, shotSpeed);
        d6 = asrw(setw(0, d6), 4);
        newZ = s16(newZ + s16(d6));
        int futureX = newX;
        int futureZ = newZ;

        int[] h = headTowards(oldX, oldZ, newX, newZ, shotSpeed, 0);
        newX = h[0];
        newZ = h[1];
        int d0 = s16(newX - oldX);
        int d1 = s16(newZ - oldZ);
        if (s16(offMult) != 0) {                       // .nooffset : depart decale sur le cote
            d0 = muls(d0, offMult) >> 8;
            d1 = muls(d1, offMult) >> 8;
            oldX = s16(oldX + s16(d1));
            oldZ = s16(oldZ - s16(d0));
            h = headTowards(oldX, oldZ, futureX, futureZ, shotSpeed, 0);
            newX = h[0];
            newZ = h[1];
        }

        s.active = true;
        s.popping = false;
        s.anim = 0;
        s.lifetime = 0;
        s.type = bulletType;
        s.power = b.hitDamage() & 0xFF;                  // move.b SHOTPOWER,ShotT_Power_w(a5)
        s.gravity = b.gravity();
        s.bounceHoriz = b.bounceHoriz() != 0;
        s.bounceVert = b.bounceVert() != 0;
        s.x = newX << 16;                              // move.w d0,(a2)
        s.z = newZ << 16;
        s.velX = s16(newX - oldX) << 16;               // move.w d0,ShotT_VelocityX_w(a5)
        s.velZ = s16(newZ - oldZ) << 16;
        s.enemyFlags = 0b110010;                       // move.l #%110010 : il ne vise PAS les aliens
        s.zone = a.zone;
        s.upperZone = a.upperZone;
        int accY = (s16(a.height) << 7) + yOff;        // 4(a0) << 7 + SHOTYOFF
        s.accY = accY;
        s.heightWord = s16(a.height);
        // Vitesse verticale : viser la tete du joueur au bout du vol.
        int d1v = s16(s16((p.yOff + (p.height >> 1)) >> 7) - 20);
        d1v = (d1v << 7) - accY;
        d1v += d1v;
        int d0v = asrw(setw(0, dist), shotShift & 63);
        if (s16(d0v) <= 0) {
            d0v = 1;
        }
        s.velY = s16(divs(d1v, d0v));
    }

    /** CalcDist (Objectmove.java:790) : distance entiere entre deux points. */
    private static int calcDist(int oldX, int oldZ, int newX, int newZ) {
        int d1 = s16(newX - oldX);
        int d2 = s16(newZ - oldZ);
        int d = muls(d1, d1) + muls(d2, d2);
        return d == 0 ? 0 : sqrt(d);
    }

    /**
     * HeadTowards (Objectmove.java:733) : avance de {@code speed} vers la cible en s'arretant a
     * {@code range}. Renvoie {x, z}.
     */
    private static int[] headTowards(int oldX, int oldZ, int newX, int newZ, int speed, int range) {
        int xdiff = s16(newX - oldX);
        int zdiff = s16(newZ - oldZ);
        int d2 = muls(xdiff, xdiff) + muls(zdiff, zdiff);
        if (d2 == 0) {
            return new int[] { newX, newZ };
        }
        int d0 = sqrt(d2);
        if (s16(d0) <= s16(range)) {
            int d1 = divs(muls(xdiff, range), d0);
            int dz = divs(muls(zdiff, range), d0);
            return new int[] { s16(newX - s16(d1)), s16(newZ - s16(dz)) };
        }
        int d3 = s16(speed + range);
        if (s16(d3) >= s16(d0)) {
            d3 = s16(d0);
        }
        d3 = s16(d3 - range);
        int d1 = divs(muls(xdiff, d3), d0);
        int dz = divs(muls(zdiff, d3), d0);
        return new int[] { s16(d1 + oldX), s16(dz + oldZ) };
    }

    // ------------------------------------------------------------------ vol

    /** ObjectHandler, branche JUMPBULLET : {@code ItsABullet} sur chaque emplacement occupe. */
    public void update() {
        for (Shot s : pool) {
            if (s.active) {
                itsABullet(s);
            }
        }
    }

    /** ItsABullet (Newanims.java:1650) : duree de vie, graphisme, sol/plafond, deplacement. */
    private void itsABullet(Shot s) {
        boolean timeout = false;                       // move.b #0,timeout
        if (s.zone < 0 || s.zone >= lvl.zones.length) { // tst.w ObjT_ZoneID_w(a0) ; blt doneshot
            s.active = false;
            return;
        }
        Database.BulletDef b = glf == null ? null : glf.bullet(s.type);
        if (b == null) {
            s.active = false;
            return;
        }

        if (!s.popping) {                              // tst.b ShotT_Status_b(a0) ; bne noworrylife
            int d2 = s16(s.lifetime);                  // move.w ShotT_Lifetime_w(a0),d2
            if (d2 >= 0 && b.lifetime() >= 0) {          // blt.s infinite (x2)
                if (s16(b.lifetime()) >= d2) {           // cmp.w d2,d1 ; bge.s notdone
                    s.lifetime = s16(s.lifetime + tempFrames); // add.w Anim_TempFrames_w
                } else {
                    timeout = true;                    // st timeout
                }
            }
        }

        if (s.popping) {
            // ---- popping : animation d'impact ----
            int d2 = s.anim;                           // move.w d1,d2 (anim NON double ici)
            drawStep(s, b.popData(), d2, b.impactGraphicType());
            d2 = s16(d2 + 1);                          // addq #1,d2
            if (d2 > s16(b.popFrames())) {               // cmp.w BulT_PopFrames_l+2(a6),d2 ; ble notdonepopping
                s.active = false;                      // FREE_ENT a0
                s.popping = false;
                s.anim = 0;
                return;
            }
            s.anim = d2 & 0xFF;                        // move.b d2,ShotT_Anim_b(a0)
            if (dynLight != null && s.brightness != 0) {   // tst anim_Brightness_w ; beq .nobright
                dynLight.brighten(s16(-(s.brightness & 0xFF)), s16(s.x >> 16), s16(s.z >> 16),
                        s.zone, s16(s.heightWord) << 7);
            }
            return;
        }

        // ---- notpopping : vol ----
        // QUIRK d'origine : en vol l'index d'animation suivant est 2*anim+1 (d2 = anim*2 avant
        // le addq), alors que le graphisme lu l'est a l'index anim*6. On le preserve.
        int d2 = s16(s.anim * 2);
        drawStep(s, b.animData(), s.anim, b.graphicType());
        d2 = s16(d2 + 1);                              // addq #1,d2
        if (d2 > s16(b.animFrames())) {                  // cmp.w BulT_AnimFrames_l+2(a6) ; ble notdoneanim
            d2 = 0;
        }
        s.anim = d2 & 0xFF;

        LevelSim.Zone z = lvl.zones[s.zone];
        int roof = s.upperZone ? z.upperRoofH : z.roofH;   // 6(a3), +8 si InUpperZone
        int floor = s.upperZone ? z.upperFloorH : z.floorH; // 2(a3)

        // .notintop : plafond
        if (roof - s.accY >= 10 * 128) {               // cmp.l #10*128,d0 ; blt .nohitroof
            if (s.bounceVert) {                        // btst #0,ShotT_Flags_w+1(a0)
                s.velY = s16(-s16(s.velY));            // neg.w ShotT_VelocityY_w(a0)
                s.accY = roof + 10 * 128;
                if (b.gravity() != 0) {
                    s.velX = s.velX >> 1;
                    s.velZ = s.velZ >> 1;
                }
            } else {
                s.anim = 0;                            // .nobounce : impact
                s.popping = true;
            }
        }
        // .nohitroof : sol
        if (floor - s.accY <= 10 * 128) {              // cmp.l #10*128,d0 ; bgt .nohitfloor
            boolean skip = false;
            if (b.bounceVert() != 0) {                   // tst.l BulT_BounceVert_l(a6) ; beq .nobounceup
                skip = true;
                if (s16(s.velY) >= 0) {                // tst.w ShotT_VelocityY_w(a0) ; blt .nohitfloor
                    int d0 = setw(0, s.velY);
                    d0 = asrw(d0, 1);                  // asr.w #1,d0
                    d0 = setw(d0, -s16(d0));           // neg.w d0
                    s.velY = s16(d0);
                    s.accY = floor - 10 * 128;
                    if (b.gravity() != 0) {
                        s.velX = s.velX >> 1;
                        s.velZ = s.velZ >> 1;
                    }
                }
            }
            if (!skip) {
                s.anim = 0;                            // .nobounceup : impact
                s.popping = true;
            }
        }

        // .nohitfloor : deplacement (les positions sont des longs 16.16)
        int oldX = s.x;
        int oldZ = s.z;
        int newX = oldX + mulFrames(s.velX);           // velocite * Anim_TempFrames_w
        int newZ = oldZ + mulFrames(s.velZ);
        int oldY = s.accY;
        int d3 = muls(s16(s.velY), tempFrames);        // move.w VelocityY,d3 ; muls Anim_TempFrames_w,d3
        if (b.gravity() != 0) {                          // tst.l BulT_Gravity_l(a6) ; beq.s nograv
            int d5 = muls(b.gravity(), tempFrames);
            d3 = d3 + d5;
            int d6 = s16(s.velY) + d5;                 // ext.l d6 ; add.l d5,d6
            if (d6 >= 10 * 256) {                      // cmp.l #10*256,d6 ; blt okgrav
                d6 = 10 * 256;
            }
            s.velY = s16(d6);
        }
        // nograv
        int d4 = s.accY + d3;
        s.accY = d4;
        int newY = d4 - 5 * 128;                       // move.l d4,newy (apres sub.l #5*128)
        s.heightWord = s16(d4 >> 7);                   // move.w d4,4(a0)

        move.oldx = s16(oldX >> 16);
        move.oldz = s16(oldZ >> 16);
        move.newx = s16(newX >> 16);
        move.newz = s16(newZ >> 16);
        move.oldy = oldY;
        move.newy = newY;
        move.zone = s.zone;
        move.stoodInTop = s.upperZone;
        setupBulletMove();
        move.wallBounce = b.bounceHoriz() != 0;          // sne Obj_WallBounce_b
        move.exitFirst = b.bounceHoriz() == 0;           // seq exitfirst
        move.hitwall = false;
        moving = move.oldx != move.newx || move.oldz != move.newz; // st MOVING / clr.b MOVING
        if (moving) {                                  // nomovebul si les deux mots sont egaux
            if (move.oldx == move.newx) {
                move.wallLength = 1;                   // move.w #1,WallLength_w
            }
            move.moveObject();
            if (dynLight != null && s.brightness != 0) {   // lalal : juste apres MoveObject
                dynLight.brighten(s16(-(s.brightness & 0xFF)), s16(move.newx), s16(move.newz),
                        move.zone, move.newy);
            }
            // MoveObject ne modifie que le MOT FORT (move.w newx) : la fraction est conservee.
            newX = (s16(move.newx) << 16) | (newX & 0xFFFF);
            newZ = (s16(move.newz) << 16) | (newZ & 0xFFFF);
        }
        // nomovebul
        s.upperZone = move.stoodInTop;
        boolean hit = false;
        if (move.wallBounce) {
            if (move.hitwall) {
                bounceOffWall(s, b);
            }
            hit = timeout;                             // .nothitwall
        } else if (move.hitwall) {
            s.accY = move.wallhitheight;               // move.l wallhitheight,ShotT_AccYPos_w(a0)
            s.heightWord = s16(s.accY >> 7);
            hit = true;                                // .hitsomething
        } else {
            hit = timeout;
        }
        if (hit) {
            s.anim = 0;
            s.popping = true;                          // move.b #1,ShotT_Status_b(a0)
            impactSound(s, b);
        }
        // lab : la position et la zone sont posees meme apres un impact
        s.zone = move.zone;
        s.x = newX;
        s.z = newZ;
        // PAS de « et seulement si elle n'a rien touche » : le jeu arrive ici MEME apres un
        // mur. « .hitsomething » pose l'impact, joue le bruit, allume le souffle — puis tombe
        // dans « .nothitwall », ou le fanion de duree de vie vient d'etre efface, et de la
        // dans « lab », qui mene a « notasplut » comme si de rien n'etait. Une roquette qui
        // creve contre le mur derriere un alien lui fait donc mal, et ce portage ne le
        // faisait pas.
        if (s.enemyFlags != 0) {                       // tst.l EntT_EnemyFlags_l(a0) ; bne notasplut
            hitEnemies(s, s16(oldX >> 16), s16(oldZ >> 16));
        }
    }

    /**
     * {@code notasplut} (Newanims.java:2150-2110) : la balle a-t-elle touche une entite ?
     *
     * <p>Le test n'est pas un simple contact : le jeu prend le SEGMENT parcouru dans la frame,
     * calcule la distance perpendiculaire de chaque entite a ce segment (80 unites de tolerance,
     * 40 pour les petites), verifie que ses deux extremites sont dans un rayon de
     * {@code (longueur + 80)} et que l'ecart de hauteur est inferieur a 50. C'est ce qui permet
     * de toucher malgre une balle rapide qui « saute » par-dessus la cible.
     */
    private void hitEnemies(Shot s, int oldXw, int oldZw) {
        // Une SEULE boucle, et dans l'ordre de la table : le corps du joueur y passe a son
        // rang, entre les monstres et les choses. Le sortir en tete le ferait toucher a la
        // place d'un monstre qui le precede — et c'est ce que ce portage faisait.
        boolean joueurVu = false;
        for (Object cible : inOrder) {
            if (!joueurVu && rankOf(cible) > playerRank) {
                joueurVu = true;
                if (toucheJoueur(s, oldXw, oldZw)) {
                    return;                            // .hitnasty
                }
            }
            if (cible instanceof Aliens.Alien a) {
                // btst typeId,EnemyFlags : le monstre est le type ZERO.
                if ((s.enemyFlags & 1) == 0 || !a.alive || a.zone < 0
                        || a.upperZone != s.upperZone || a.hitPoints == 0) {
                    continue;
                }
                if (hitsEntity(s, oldXw, oldZw, a.x, a.z, s16(a.height), 80)) {
                    targets.hit(a, s.power);
                    // move.w ShotT_VelocityX_w(a0),EntT_ImpactX_w(a3) : la MEME ligne que pour
                    // une chose. Le jeu n'a qu'une boucle ; ce portage en avait trois, et
                    // celle des monstres avait perdu le recul en route. Il ne se relit qu'a la
                    // MORT, dans la gerbe de morceaux — un alien abattu de face s'eparpillait
                    // donc comme un alien abattu de dos.
                    a.impactX = s16(s.velX >> 16);
                    a.impactZ = s16(s.velZ >> 16);
                    s.anim = 0;
                    s.popping = true;
                    impactSound(s, glf == null ? null : glf.bullet(s.type));
                    return;                            // .hitnasty
                }
                continue;
            }
            // Une CHOSE : le type UN. L'original ne la retient que si sa definition la dit
            // cassable (newanims.s:2563) — un ramassable, un interrupteur ou une lampe laissent
            // passer la balle. La liste n'en contient donc que des cassables.
            ObjectRuntime.Thing t = (ObjectRuntime.Thing) cible;
            if ((s.enemyFlags & 2) == 0 || t.zone < 0 || t.hitPoints == 0) {
                continue;
            }
            // L'etage se compare par OU EXCLUSIF d'octets, et non par oui ou non : une entite
            // qui ne porte ni zero ni deux cent cinquante-cinq n'est d'AUCUN des deux.
            if (((t.entity.upperZone() ^ (s.upperZone ? 0xFF : 0)) & 0xFF) != 0) {
                continue;
            }
            if (hitsEntity(s, oldXw, oldZw, t.entity.x(), t.entity.z(), s16(t.height), 80)) {
                damage(t, s.power);
                // move.w, et non add.w : la poussee d'un coup REMPLACE la precedente.
                t.impactX = s16(s.velX >> 16);
                t.impactZ = s16(s.velZ >> 16);
                s.anim = 0;
                s.popping = true;
                impactSound(s, glf == null ? null : glf.bullet(s.type));
                return;                                // .hitnasty
            }
        }
        if (!joueurVu) {
            toucheJoueur(s, oldXw, oldZw);
        }
    }

    /**
     * Le corps du joueur, teste a son rang dans la table.
     *
     * <p>Bit 4 des EnemyFlags = le JOUEUR UN (un tir d'alien vaut %110010, un tir du joueur
     * %100011 — qui ne le porte pas, et c'est ainsi qu'on ne se tire pas dessus). La tolerance
     * n'est pas la meme pour tout le monde : quatre-vingts pour les types ZERO et UN (les
     * monstres et les choses), QUARANTE au-dela (newanims.s:2616). Le joueur est le type
     * QUATRE — il est donc deux fois moins large qu'un monstre.
     */
    private boolean toucheJoueur(Shot s, int oldXw, int oldZw) {
        if (player == null || (s.enemyFlags & (1 << 4)) == 0
                || player.zone < 0 || player.stoodInTop != s.upperZone
                || !hitsEntity(s, oldXw, oldZw, s16(player.snapXOff >> 16),
                        s16(player.snapZOff >> 16),
                        s16((player.yOff + (player.height >> 1)) >> 7), 40)) {
            return false;
        }
        // add.b : un OCTET, qui DEBORDE. Deux cent soixante points de degats en font
        // quatre — le corps du joueur n'est pas plus epargne qu'une caisse.
        player.damageTaken = (player.damageTaken + s.power) & 0xFF;
        // move.w, et non add.w : la poussee du coup REMPLACE la precedente, pour le joueur
        // comme pour une chose.
        player.impactX = s16(s.velX >> 16);
        player.impactZ = s16(s.velZ >> 16);
        s.anim = 0;
        s.popping = true;
        impactSound(s, glf == null ? null : glf.bullet(s.type));
        return true;
    }

    /**
     * {@code add.b d6,EntT_DamageTaken_b(a3)} : les degats s'accumulent sur un OCTET, et
     * DEBORDENT. Deux cent soixante points de degats en font quatre, et une chose qu'on
     * acharne assez longtemps se retrouve intacte.
     */
    private static void damage(ObjectRuntime.Thing t, int power) {
        t.damageTaken = (t.damageTaken + power) & 0xFF;
    }

    /** Le test geometrique commun : l'entite est-elle sur le segment parcouru cette frame ? */
    private boolean hitsEntity(Shot s, int oldXw, int oldZw, int ex, int ez, int eh, int tol) {
        return hitsSegment(oldXw, oldZw, s16(s.x >> 16), s16(s.z >> 16), s16(s.heightWord),
                ex, ez, eh, tol);
    }

    /**
     * Le meme test, mais sur un segment donne explicitement — le rayon hitscan s'en sert, lui
     * qui n'a pas de projectile a promener.
     */
    private boolean hitsSegment(int oldXw, int oldZw, int newXw, int newZw, int rayHeight,
                                int ex, int ez, int eh, int tol) {
        int xdiff = s16(newXw - oldXw);
        int zdiff = s16(newZw - oldZw);
        int range = sqrt(muls(xdiff, xdiff) + muls(zdiff, zdiff));
        int d0 = s16(range + 80);
        int sqrnum = muls(d0, d0);
        int dh = s16(eh - rayHeight);
        if (dh < 0) {
            dh = -dh;
        }
        if (moving && dh > 50) {                       // .ignoreheight
            return false;
        }
        int d2 = s16(ex - oldXw);
        int d3 = s16(ez - oldZw);
        int d4 = s16(ex - newXw);
        int d5 = s16(ez - newZw);
        int d6 = muls(d2, zdiff) - muls(d3, xdiff);    // produit vectoriel = aire du triangle
        if (d6 <= 0) {
            d6 = -d6;
        }
        d6 = divs(d6, range);                          // divs Range,d6 : Range ne vaut jamais zero
        if (s16(d6) > tol) {
            return false;
        }
        if (muls(d2, d2) + muls(d3, d3) > sqrnum) {
            return false;
        }
        return muls(d4, d4) + muls(d5, d5) <= sqrnum;
    }

    /**
     * La premiere cible rencontree sur un segment du rayon, ou {@code null} — un monstre ou
     * une chose cassable, dans l'ordre du niveau. Meme tolerance que pour les projectiles,
     * pour que les deux familles d'armes touchent pareil.
     */
    private Object firstOnSegment(int x0, int z0, int x1, int z1, int rayHeight,
                                  int zoneFrom, int zoneTo, boolean upper) {
        for (Object cible : inOrder) {
            // Le segment peut enjamber deux zones : on accepte les cibles de l'une ou l'autre.
            if (cible instanceof Aliens.Alien a) {
                if (!a.alive || (a.zone != zoneFrom && a.zone != zoneTo)
                        || a.upperZone != upper || a.hitPoints == 0) {
                    continue;
                }
                if (hitsSegment(x0, z0, x1, z1, rayHeight, a.x, a.z, s16(a.height), 80)) {
                    return a;
                }
                continue;
            }
            ObjectRuntime.Thing t = (ObjectRuntime.Thing) cible;
            if (t.zone < 0 || (t.zone != zoneFrom && t.zone != zoneTo) || t.hitPoints == 0
                    || ((t.entity.upperZone() ^ (upper ? 0xFF : 0)) & 0xFF) != 0) {
                continue;
            }
            if (hitsSegment(x0, z0, x1, z1, rayHeight, t.entity.x(), t.entity.z(),
                    s16(t.height), 80)) {
                return t;
            }
        }
        return null;
    }

    /** Ou se trouve une cible, monstre ou chose. */
    private static int targetX(Object o) {
        return o instanceof Aliens.Alien a ? a.x : ((ObjectRuntime.Thing) o).entity.x();
    }

    private static int targetZ(Object o) {
        return o instanceof Aliens.Alien a ? a.z : ((ObjectRuntime.Thing) o).entity.z();
    }

    /** bulletImpactFX : bruit d'impact (BulT_ImpactSFX_l - 1) puis SOUFFLE s'il explose. */
    private void impactSound(Shot s, Database.BulletDef b) {
        if (b == null) {
            return;
        }
        if (sfx != null) {
            // newanims.s:2171 : l'impact. Il n'ecrit PAS « pas si ca joue » et herite donc
            // de ce que le bruit precedent y avait laisse.
            // L'identifiant est le POINT de la balle dans la table des points d'objets, et
            // non son rang dans le pool : c'est lui qui decide si ce bruit remplace celui qui
            // joue deja sous le meme identifiant.
            sfx.id = points[pool.indexOf(s)];      // move.w d1,IDNUM
            sfx.at(b.impactSfx() - 1, 200, s16(s.x >> 16), s16(s.z >> 16));
        }
        if (b.explosiveForce() != 0) {                   // tst.l BulT_ExplosiveForce_l ; beq .noexplosion
            computeBlast(b.explosiveForce(), s16(s.x >> 16), s16(s.z >> 16), s.heightWord,
                    s.zone, s.upperZone, s.type);
        }
    }

    /**
     * ComputeBlast (Newanims.java:1387) : degats de SOUFFLE autour d'un impact.
     *
     * <p>Chaque entite visible depuis le point d'explosion (ligne de vue : le souffle ne traverse
     * pas les murs) prend des degats qui decroissent avec la distance —
     * {@code d = (force * (64 - (dist>>3 - 4))) >> 5}, borne par la force — et un recul dirige.
     * Puis six « flammes » (l'animation d'impact de la meme balle) sont semees autour du point,
     * en trois couronnes de plus en plus larges.
     *
     * <p>C'est la SEULE routine du jeu qui parcoure la table des objets entiere en la triant
     * par {@code ObjT_TypeID_b}, et c'est donc d'ici qu'on voit ce que cette table range :
     *
     * <ul>
     *   <li>le type UN — les CHOSES — est ecarte d'emblee, et le type TROIS — les auxiliaires —
     *       aussi : aucune explosion n'a jamais casse un ordinateur ni allume une lampe ;</li>
     *   <li>le type ZERO (monstre) et les types QUATRE et CINQ (les deux joueurs) passent par
     *       le MEME test de points de vie et recoivent leur recul dans
     *       {@code EntT_ImpactX/Y/Z_w} ;</li>
     *   <li>le type DEUX (projectile) n'est retenu que s'il PESE, et sa poussee entre
     *       directement dans sa vitesse, avec un coup vertical mille fois plus fort.</li>
     * </ul>
     *
     * <p>Et les degats s'ajoutent a {@code EntT_DamageTaken_b} AVANT ce tri, donc aussi sur un
     * projectile — ou cet octet-la est le poids faible de sa vitesse en X. Le souffle pousse
     * donc une grenade deux fois : une fois exprès, une fois par accident.
     */
    public void computeBlast(int force, int x, int z, int heightWord, int zone, boolean upper,
                             int bulletType) {
        int d6 = s16(force);
        // --- degats aux entites ---
        // L'original ne connait qu'UNE table et la trie par ObjT_TypeID_b (newanims.s:2873) :
        // le type UN (les CHOSES) et le type TROIS (les auxiliaires) sont ecartes d'emblee —
        // aucune explosion n'a jamais casse un ordinateur ni allume une lampe. Restent le type
        // ZERO (monstre), les types QUATRE et CINQ (les joueurs), qui passent par le meme test
        // de points de vie, et le type DEUX (projectile) s'il a de la gravite.
        if (targets != null) {
            for (Aliens.Alien a : targets.aliens()) {
                if (!a.alive || a.zone < 0 || a.hitPoints == 0) {
                    continue;                          // tst.b EntT_HitPoints_b ; beq HitObjLoop
                }
                int[] hit = blastAt(force, x, z, heightWord, zone, upper,
                        a.x, a.z, s16(a.height), a.zone, a.upperZone);
                if (hit == null) {
                    continue;
                }
                targets.hit(a, hit[0]);
                // Les reculs se POSENT sur l'entite. Un monstre n'en lit aucun de son vivant :
                // ils ne ressortent qu'a sa mort, par la gerbe de morceaux. Et le vertical va
                // dans EntT_ImpactY_w (octet 46), PAS dans EntT_VelocityY_w (octet 48) : un
                // monstre ne decolle pas sous l'explosion.
                a.impactX = s16(hit[1]);               // move.w d0,EntT_ImpactX_w(a2)
                a.impactZ = s16(hit[2]);               // move.w d1,EntT_ImpactZ_w(a2)
            }
        }
        if (player != null && player.zone >= 0) {
            int[] hit = blastAt(force, x, z, heightWord, zone, upper,
                    s16(player.snapXOff >> 16), s16(player.snapZOff >> 16),
                    s16((player.yOff + (player.height >> 1)) >> 7),
                    player.zone, player.stoodInTop);
            if (hit != null) {
                player.damageTaken += hit[0];
                player.impactX = s16(hit[1]);
                player.impactZ = s16(hit[2]);
                // Lui, en revanche, le lit : Plr1_Use le verse dans sa vitesse verticale,
                // decale de huit. Le recul vertical est PLAFONNE a huit, donc une explosion ne
                // souleve jamais de plus de deux mille quarante-huit — le double d un saut, et
                // pas un cran de plus, qu elle soit a bout portant ou qu elle vienne du ciel.
                player.impactY = s16(hit[3]);          // move.w d1,EntT_ImpactY_w(a2)
            }
        }
        // Un PROJECTILE qui PESE (type deux, gravite non nulle) est pousse lui aussi — mais
        // dans sa vitesse, directement, et par un coup vertical mille fois plus fort : la meme
        // force decalee de DOUZE au lieu de quatre, bornee a moins deux mille quarante-huit.
        // C'est ce qui fait sauter les grenades posees au sol quand une roquette tombe a cote.
        // Les flammes, elles, n'ont aucune gravite et restent ou elles sont.
        for (Shot b : pool) {
            // La gravite lue est celle de la DEFINITION, pas celle que l'emplacement porte :
            // une flamme garde la gravite du projectile mort avant elle, mais c'est sa
            // definition a elle qui decide si le souffle la pousse.
            Database.BulletDef bd = glf == null ? null : glf.bullet(b.type);
            if (!b.active || b.zone < 0 || bd == null || bd.gravity() == 0) {
                continue;                              // tst.l BulT_Gravity_l(a3) ; beq HitObjLoop
            }
            int[] hit = blastAt(force, x, z, heightWord, zone, upper,
                    s16(b.x >> 16), s16(b.z >> 16), s16(b.heightWord), b.zone, b.upperZone);
            if (hit == null) {
                continue;
            }
            // Et ici le jeu se cogne a sa propre table. Le "add.b d5,EntT_DamageTaken_b" qui
            // precede la branche s'applique a TOUT ce que le souffle touche, projectiles
            // compris — or dans l'enregistrement d'un projectile cet octet-la n'est pas des
            // degats : EntT_DamageTaken_b (octet 19) est le POIDS FAIBLE du mot entier de
            // ShotT_VelocityX_w (octet 18). Les degats d'une explosion entrent donc dans la
            // vitesse horizontale d'une grenade, par l'octet bas et en debordant a deux cent
            // cinquante-six. Personne ne l'a voulu ; c'est ce que le jeu fait.
            int hi = s16(b.velX >> 16);
            hi = s16((hi & ~0xFF) | ((hi + hit[0]) & 0xFF)); // add.b d5,EntT_DamageTaken_b(a2)
            b.velX = (hi << 16) | (b.velX & 0xFFFF);
            // add.w : le mot fort d'une vitesse 16.16 pour les deux horizontales...
            b.velX = (s16((b.velX >> 16) + hit[1]) << 16) | (b.velX & 0xFFFF);
            b.velZ = (s16((b.velZ >> 16) + hit[2]) << 16) | (b.velZ & 0xFFFF);
            b.velY = s16(b.velY + hit[4]);             // ...et le mot entier pour la verticale
        }
        // --- les FLAMMES (DOFLAMES) : trois couronnes de deux, de plus en plus larges ---
        //
        // Chacune part du centre de l'explosion, se decale au hasard, et PASSE PAR LA
        // COLLISION : le jeu lui fait traverser le decor avec MoveObject, quatre-vingts
        // unites d'encombrement, « ecarte du mur ». Une flamme nee dans un mur en ressort
        // donc, et c'est le secteur d'ARRIVEE qu'elle porte, pas celui du tir.
        //
        // Elle n'efface rien de ce que l'emplacement contenait : ni vitesse, ni gravite, ni
        // duree de vie, ni degats. L'original ne le fait pas non plus — une flamme est
        // IMMOBILE (son etat d'impact est arme), donc rien de tout cela n'est relu de sa vie.
        // Mais la gravite reste, et une flamme garde ainsi celle du projectile qui l'a
        // precedee dans cet emplacement : la prochaine explosion la poussera ou l'ignorera
        // selon ce qui est mort la avant elle.
        if (nav == null) {
            return;
        }
        int middleX = s16(x);                          // move.w (a2,d0.w*8),anim_MiddleX_w
        int middleZ = s16(z);
        int oldY = s16(heightWord) << 7;               // move.w 4(a0),d0 ; ext.l ; asl.l #7
        int d5 = 2;                                    // moveq #2,d5
        int restants = PLR_POOL - 1;                   // move.w #NUM_PLR_SHOT_DATA-1,NUMTOCHECK
        int ou = 0;                                    // a3 = Plr_ShotDataPtr_l
        for (int couronne = 2; couronne >= 0; couronne--) {   // radiusloop ; dbra d6
            for (int deux = 1; deux >= 0; deux--) {    // DOFLAMES ; dbra d7
                // .findonefree : la recherche NE REPART PAS du debut. Elle reprend la ou la
                // precedente s'est arretee, et ne regarde que les emplacements restants : un
                // trou laisse derriere elle ne sera pas repris par cette explosion-ci.
                Shot f = null;
                int d1 = restants;
                while (true) {
                    if (!pool.get(ou).active) {        // move.w ObjT_ZoneID_w(a3),d2 ; blt
                        f = pool.get(ou);
                        break;
                    }
                    ou++;                              // adda.w #ObjT_SizeOf_l,a3
                    d1--;
                    if (d1 < 0) {                      // dbra d1,.findonefree ; rts
                        break;
                    }
                }
                if (f == null) {
                    return;
                }
                restants = d1;                         // move.w d1,NUMTOCHECK
                int d1x = middleX;
                int d2z = middleZ;
                int dx = M68k.asrw(muls((byte) nav.rand(), d5), 1); // ext.w ; muls ; asr.w #1
                if (s16(dx) == 0) {
                    dx = 2;                            // bne .xnz ; moveq #2,d0
                }
                d1x = s16(d1x + s16(dx));
                int dz = M68k.asrw(muls((byte) nav.rand(), d5), 1);
                if (s16(dz) == 0) {
                    dz = 2;                            // bne .znz ; moveq #2,d0
                }
                d2z = s16(d2z + s16(dz));
                // Le hasard VERTICAL prend le mot entier du generateur, pas son octet bas.
                int d3 = oldY + (muls(s16(nav.rand()), d5) >> 3); // muls d5,d0 ; asr.l #3
                move.oldx = middleX;
                move.oldz = middleZ;
                move.newx = d1x;
                move.newz = d2z;
                move.oldy = oldY;
                move.newy = d3;
                move.zone = zone;                      // Obj_ZonePtr_l = anim_MiddleRoom_l
                move.stoodInTop = upper;               // StoodInTop
                move.extLen = 80;                      // move.w #80,Obj_ExtLen_w
                move.awayFromWall = 1;                 // move.b #1,Obj_AwayFromWall_b
                // Le rebond et la sortie anticipee, eux, ne sont pas herites : ComputeBlast
                // les pose lui-meme, UNE fois, juste avant la premiere flamme (newanims.s:3060,
                // « clr.b exitfirst / st.b Obj_WallBounce_b » a CheckedEmAll). Une flamme
                // s'arrete donc au point de contact du mur au lieu de glisser le long, quelle
                // que soit la balle qui vient d'exploser — mais la sortie anticipee est
                // EFFACEE, de sorte qu'elle continue d'eprouver les murs suivants.
                move.wallBounce = true;                // st.b Obj_WallBounce_b
                move.exitFirst = false;                // clr.b exitfirst
                move.moveObject();                     // jsr MoveObject
                f.active = true;
                f.zone = move.zone;                    // move.w (a2),12(a3)
                int y = move.newy;                     // move.l newy,d0
                LevelSim.Zone zz = lvl.zones[Math.max(0, Math.min(move.zone,
                        lvl.zones.length - 1))];
                // L'etage lu est celui du TIR SOURCE, pas celui ou la flamme a atterri.
                int sol = upper ? zz.upperFloorH : zz.floorH;
                int toit = upper ? zz.upperRoofH : zz.roofH;
                if (sol <= y) {
                    y = sol;                           // cmp.l d0,d1 ; bgt .abovefloor
                }
                if (toit >= y) {
                    y = toit;                          // cmp.l d0,d2 ; blt .belowroof
                }
                f.accY = y;                            // move.l d0,ShotT_AccYPos_w(a3)
                f.heightWord = s16(y >> 7);            // asr.l #7,d0 ; move.w d0,4(a3)
                f.anim = 0;                            // move.b #0,ShotT_Anim_b(a3)
                f.popping = true;                      // st ShotT_Status_b(a3)
                // StoodInTop est une globale que MoveObject REECRIT : une flamme qui a glisse
                // d'un etage a l'autre porte celui ou elle a fini, pas celui du tir. Le
                // plafonnement de hauteur, lui, lit l'etage du TIR : les deux peuvent differer.
                f.upperZone = move.stoodInTop;         // move.b StoodInTop,ShotT_InUpperZone_b
                f.type = bulletType;                   // move.b BLOODYGREATBOMB,ShotT_Size_b
                f.x = s16(move.newx) << 16;            // move.w newx,(a2,d0.w*8)
                f.z = s16(move.newz) << 16;
                ou++;                                  // adda.w #64,a3
                restants--;                            // sub.w #1,NUMTOCHECK ; blt .nomore
                if (restants < 0) {
                    return;
                }
            }
            d5 += 2;                                   // add.w #2,d5
        }
    }

    /**
     * Un objet dans le souffle : renvoie {degats, reculX, reculZ, reculY d'une entite, reculY
     * d'un projectile} ou null s'il est hors de portee ou hors de vue.
     */
    private int[] blastAt(int force, int x, int z, int h, int zone, boolean upper,
                          int tx, int tz, int th, int tzone, boolean tupper) {
        if (los != null) {
            los.viewerX = x;
            los.viewerZ = z;
            los.viewerY = h;
            los.viewerZone = zone;
            los.viewerTop = upper;
            los.targetX = tx;
            los.targetZ = tz;
            los.targetY = th;
            los.targetZone = tzone;
            los.targetTop = tupper;
            los.canItBeSeen();
            if (!los.canSee) {
                return null;                           // tst.b CanSee ; beq HitObjLoop
            }
        }
        int d0 = s16(tx - x);
        int d1 = s16(tz - z);
        int dist = blastSqrt(muls(d0, d0) + muls(d1, d1), d1);
        int d7 = Math.max(s16(dist), 256);             // cmp.w #256,d7 ; bge .okd
        int d3 = s16(M68k.asrw(setw(0, dist), 3) - 4); // asr.w #3 ; sub.w #4
        if (d3 < 0) {
            d3 = 0;
        }
        if (d3 > 64) {
            return null;                               // cmp.w #64,d3 ; bgt HitObjLoop
        }
        d3 = s16(64 - d3);
        int d5 = muls(s16(force), d3) >> 5;            // asr.l #5
        if (d5 >= s16(force)) {
            d5 = s16(force);                           // borne a anim_MaxDamage_w
        }
        int kx = divs(d0 * force, d7);
        int kz = divs(d1 * force, d7);
        int ky = s16(-divs(force << 4, d7));           // asl.l #4 ; divs d7,d1 ; neg.w d1
        if (ky < -8) {
            ky = -8;                                   // cmp.w #-8,d1 ; bge .okbl2
        }
        int kyb = s16(-divs(force << 12, d7));         // asl.l #8 ; asl.l #4 ; divs ; neg.w
        if (kyb < -8 * 256) {
            kyb = -8 * 256;                            // cmp.w #-8*256,d1 ; bge .okbl
        }
        return new int[] { d5 & 0xFF, kx, kz, ky, kyb };
    }

    /**
     * La racine carree du SOUFFLE — et ce n'est pas celle du reste du jeu.
     *
     * <p>{@code ai_CalcSqrt} (ai.s:2058) fait trois pas de Newton, chacun de la forme
     * {@code x <- x - (x*x - a) / 2x}. {@code ComputeBlast} en a sa propre copie, recopiee a
     * la main dans le corps de la boucle — et la DEUXIEME des trois y multiplie par
     * {@code d1}, l'ecart en Z, au lieu de carrer {@code x} (newanims.s:2952). La meme routine
     * inline une seconde fois dans {@code notasplut} (newanims.s:2518) est correcte : la faute
     * est dans cette copie-la, et dans elle seule.
     *
     * <p>Le troisieme pas, lui, est juste, et un pas de Newton rattrape beaucoup : ce qui sort
     * reste voisin d'une racine. Voisin, pas egal — le souffle ne porte donc pas tout a fait a
     * la distance qu'on croit, et d'autant moins qu'on est decale en Z.
     *
     * <p>Autre ecart avec {@code ai_CalcSqrt} : une somme NULLE sort d'ici a UN et non a zero
     * (le {@code move.w #1,d4} est pose avant le {@code beq}). Sans consequence : les deux
     * valeurs tombent dans le meme pas de huit.
     */
    private static int blastSqrt(int d2, int dz) {
        if (d2 == 0) {                                 // move.w #1,d4 ; add.l d3,d2 ; beq .oksqr
            return 1;
        }
        int d4 = 31;                                   // move.w #31,d4
        while ((d2 & (1 << (d4 & 31))) == 0) {         // btst d4,d2 ; dbne d4,.findhigh
            d4 = s16(d4 - 1);
            if (d4 == -1) {
                break;
            }
        }
        d4 = 1 << (s16(d4) >> 1);                      // asr.w #1,d4 ; clr.l d3 ; bset d4,d3
        for (int pas = 0; pas < 3; pas++) {
            int d3 = setw(0, d4);                      // move.w d4,d3
            d3 = pas == 1 ? muls(d3, dz) : muls(d3, d3); // muls d1,d3 (!) au DEUXIEME pas
            d3 = d3 - d2;                              // sub.l d2,d3
            d3 = d3 >> 1;                              // asr.l #1,d3
            d3 = divs(d3, d4);                         // divs d4,d3
            d4 = setw(d4, d4 - d3);                    // sub.w d3,d4
            if (s16(d4) <= 0) {
                d4 = setw(d4, 1);                      // bgt .stillnot0 ; move.w #1,d4
            }
        }
        return s16(d4);
    }

    /**
     * Anim_ExplodeIntoBits (newanims.s:605) : la gerbe de morceaux d'une entite qui meurt.
     *
     * <p>Chaque morceau part dans une direction tiree au hasard, a une vitesse decalee d'un a
     * quatre bits, PLUS LA MOITIE DU RECUL DU MORT, et monte de deux a dix unites ; la gravite
     * de sa definition (les « Splutch ») le fait ensuite retomber. C'est le seul endroit du jeu
     * qui relise {@code EntT_ImpactX_w} d'un monstre : le coup qui l'a tue decide de quel cote
     * il s'eparpille, et un alien abattu de face ne gicle pas comme un alien abattu de dos.
     *
     * <p>Les deux reculs se lisent en UN SEUL {@code move.l} : X dans le mot fort, Z dans le
     * mot faible. Le {@code asr.w #1,d0} ne rogne donc que le Z, et c'est le {@code swap}
     * suivant qui amene le X sous la meme lame.
     *
     * <h2>Ce que la routine ne pose PAS</h2>
     *
     * <ul>
     *   <li>la GRAVITE — la ligne est commentee dans l'original (newanims.s:675). Le champ est
     *       mort des deux cotes : le vol lit celle de la DEFINITION ;</li>
     *   <li>le pas d'ANIMATION : le morceau reprend celui que l'emplacement rendu avait ;</li>
     *   <li>la FRACTION d'une position ni d'une vitesse : {@code move.w} n'ecrit que le mot
     *       fort, et les seize bits du dessous restent ceux du tir precedent ;</li>
     *   <li>le TYPE D'OBJET — voir {@link #typoDuType(int)}.</li>
     * </ul>
     *
     * <p>En revanche elle efface les DEUX octets de {@code ShotT_Flags_w} : un morceau ne
     * rebondit pas au plafond, meme si sa definition de balle le dit.
     */
    public void explodeIntoBits(int splatType, int count, int x, int z, int heightWord, int zone,
                                boolean upper, int impactX, int impactZ) {
        if (nav == null) {
            return;                                    // harnais : pas de hasard, pas de gerbe
        }
        int d2 = s16(count) > 7 ? 7 : s16(count);      // cmp.w #7,d2 ; ble .oksplut ; move.w #7,d2
        int k = PLR_POOL;                              // move.l AI_AlienShotDataPtr_l,a5
        int d1 = ALIEN_POOL - 1;                       // move.w #NUM_ALIEN_SHOT_DATA-1,d1
        while (true) {
            // .findeight : le premier emplacement RENDU, en avancant — jamais en revenant.
            while (pool.get(k).active) {               // move.w ObjT_ZoneID_w(a5),d0 ; blt .gotonehere
                k++;                                   // adda.w #ObjT_SizeOf_l,a5
                if (--d1 < 0) {
                    return;                            // dbra d1,.findeight ; rts
                }
            }
            // .gotonehere
            Shot s = pool.get(k);
            s.power = 0;                               // move.b #0,ShotT_Power_w(a5)
            s.x = (s.x & 0xFFFF) | (s16(x) << 16);     // move.w newx,(a2)
            s.z = (s.z & 0xFFFF) | (s16(z) << 16);     // move.w newz,4(a2)
            typoDuType(k);                             // move.b #2,16(a2)
            int ang = nav.rand() & SinCos.MASK;        // jsr GetRand ; AMOD_A d0
            int d3 = s16(sinCos.value(ang));           // move.w (a2),d3
            int d4 = s16(sinCos.value(ang + SinCos.COSINE_OFFSET)); // move.w COSINE_OFS(a2),d4
            int d0 = (nav.rand() & 3) + 1;             // jsr GetRand ; and.w #3,d0 ; add.w #1,d0
            d3 = d3 << d0;                             // ext.l d3 ; asl.l d0,d3
            d4 = d4 << d0;                             // ext.l d4 ; asl.l d0,d4
            // move.l EntT_ImpactX_w(a0),d0 — un seul long pour les deux reculs
            d4 = s16((d4 >> 16) + (s16(impactZ) >> 1)); // swap d4 ; asr.w #1,d0 ; add.w d0,d4
            s.velZ = (s.velZ & 0xFFFF) | (d4 << 16);   // move.w d4,ShotT_VelocityZ_w(a5)
            d3 = s16((d3 >> 16) + (s16(impactX) >> 1)); // swap d3 ; swap d0 ; asr.w #1,d0 ; add.w
            s.velX = (s.velX & 0xFFFF) | (d3 << 16);   // move.w d3,ShotT_VelocityX_w(a5)
            s.velY = s16(-((nav.rand() & 1023) + 2 * 128)); // and.w #1023 ; add.w #2*128 ; neg.w
            s.enemyFlags = 0;                          // move.l #0,EntT_EnemyFlags_l(a5)
            s.zone = zone;                             // move.w ObjT_ZoneID_w(a0),ObjT_ZoneID_w(a5)
            s.active = zone >= 0;                      // un secteur negatif = emplacement rendu
            s.heightWord = s16(heightWord);            // move.w 4(a0),d0 ; move.w d0,4(a5)
            s.accY = s16(s16(heightWord) + 6) << 7;    // add.w #6,d0 ; ext.l d0 ; asl.l #7,d0
            s.type = splatType & 0xFF;                 // move.b Anim_SplatType_w,ShotT_Size_b(a5)
            s.bounceHoriz = false;                     // move.w #0,ShotT_Flags_w(a5) : les DEUX
            s.bounceVert = false;                      //   octets, et non ceux de la definition
            s.lifetime = 0;                            // move.w #0,ShotT_Lifetime_w(a5)
            s.popping = false;                         // clr.b ShotT_Status_b(a5) : il VOLE
            s.upperZone = upper;                       // move.b ShotT_InUpperZone_b(a0),...
            k++;                                       // adda.w #64,a5
            d2 = s16(d2 - 1);                          // sub.w #1,d2
            if (d2 < 0) {
                return;                                // blt.s .gotemall
            }
            if (--d1 < 0) {
                return;                                // dbra d1,.findeight ; rts
            }
        }
    }

    /**
     * {@code move.b #2,16(a2)} — le type d'objet ecrit DANS LE MAUVAIS REGISTRE.
     *
     * <p>La routine vient de poser le point du morceau dans {@code a2}. Elle voulait
     * visiblement ecrire {@code OBJ_TYPE_PROJECTILE} dans {@code ObjT_TypeID_b(a5)}, a seize
     * octets du debut de l'entite — c'est mot pour mot ce que fait ai.s:234 pour un alien
     * nouveau-ne. Mais elle l'ecrit a seize octets du POINT, et un point fait huit octets :
     * elle tape donc l'octet fort du X du point situe DEUX RANGS PLUS LOIN.
     *
     * <p>Le type manquant ne se voit jamais : le pool des projectiles aliens arrive du fichier
     * de niveau avec {@code ObjT_TypeID_b} deja a deux, et rien ne l'en fait sortir. Le X, lui,
     * se voit : le projectile deux emplacements plus loin se retrouve a une abscisse comprise
     * entre cinq cent douze et sept cent soixante-sept, c'est-a-dire a peu pres n'importe ou.
     *
     * <p>Les deux derniers emplacements du pool debordent sur les objets AUXILIAIRES qui le
     * suivent dans la table, et ceux-la ne sont pas portes : au-dela du pool, on ne fait rien.
     */
    private void typoDuType(int k) {
        int cible = k + 2;
        if (cible >= pool.size()) {
            return;                                    // hors du pool : des objets non portes
        }
        Shot t = pool.get(cible);
        t.x = (t.x & 0x00FFFFFF) | (2 << 24);
    }

    /** ai_CalcSqrt (3 iterations de Newton), comme le jeu. */
    private static int sqrt(int d2) {
        // UN, et non zero. La copie inline de notasplut pose « move.l #1,d0 » AVANT de tester
        // la somme, et saute a « .oksqr » en la gardant : une distance nulle rend donc une
        // portee de un, et le rayon de recherche vaut (1 + 80) au carre, soit six mille cinq
        // cent soixante et un — pas six mille quatre cents. Une balle immobile touche ainsi ce
        // qui est a quatre-vingt-une unites d'elle, et pas ce qui est a quatre-vingt-deux.
        if (d2 == 0) {
            return 1;
        }
        int d0 = 31;
        while (d0 >= 0 && (d2 & (1 << d0)) == 0) {
            d0--;
        }
        d0 = 1 << (d0 >> 1);
        for (int i = 0; i < 3; i++) {
            int d1 = s16(d0);
            d1 = muls(d1, d1);
            d1 -= d2;
            d1 >>= 1;
            d1 = divs(d1, d0);
            d0 = s16(d0 - d1);
            if (s16(d0) <= 0) {
                d0 = 1;
            }
        }
        return s16(d0);
    }

    /** Reflexion de la vitesse sur le mur touche (ItsABullet, branche Obj_WallBounce_b). */
    private void bounceOffWall(Shot s, Database.BulletDef b) {
        int d0 = muls(s16(s.velZ), s16(move.wallXSize)); // move.w VelZ,d0 ; muls WallXSize_w,d0
        int d1 = muls(s16(s.velX), s16(move.wallZSize)); // move.w VelX,d1 ; muls WallZSize_w,d1
        d0 = d0 - d1;
        d0 = divs(d0, s16(move.wallLength));           // divs WallLength_w,d0
        d1 = setw(0, s.velX);
        int d2 = setw(0, move.wallZSize);
        d2 = setw(d2, d2 + d2);                        // add.w d2,d2
        d2 = muls(s16(d2), d0);
        d2 = divs(d2, s16(move.wallLength));
        d1 = setw(d1, d1 + d2);
        s.velX = s16(d1);                              // move.w d1,ShotT_VelocityX_w(a0)
        d1 = setw(0, s.velZ);
        d2 = setw(0, move.wallXSize);
        d2 = setw(d2, d2 + d2);
        d2 = muls(s16(d2), d0);
        d2 = divs(d2, s16(move.wallLength));
        d1 = setw(d1, d1 - d2);
        s.velZ = s16(d1);
        if (b.gravity() != 0) {
            s.velX = s.velX >> 1;
            s.velZ = s.velZ >> 1;
        }
    }

    /**
     * Pose les donnees de dessin d'un pas d'animation : bitmap, halo (« glare ») ou bitmap
     * additif selon {@code BulT_GraphicType_l} / {@code BulT_ImpactGraphicType_l}.
     */
    private static void drawStep(Shot s, List<Database.AnimStep> data, int index, int graphicType) {
        if (data == null || index < 0 || index >= data.size()) {
            return;
        }
        Database.AnimStep st = data.get(index);
        s.glareSize = 0;                               // move.l #0,8(a0)
        s.additive = false;
        if (graphicType < 1) {                         // .bitmapgraph
            s.sheet = st.gfx();
            s.frame = st.frame();
        } else if (graphicType == 1) {                 // .glaregraph
            s.glareSize = -(byte) st.gfx();              // ext.w d0 ; neg.w d0
            s.frame = st.frame();
        } else {                                       // .additivegraph
            s.sheet = st.gfx();
            s.frame = st.frame();
            s.additive = true;                         // move.b #6,10(a0)
        }
        s.brightness = st.next();                        // anim_Brightness_w (octet 5 du pas)
    }

    /** {@code velocite * Anim_TempFrames_w} en 16.16 (swap/muls/mulu de l'original). */
    private int mulFrames(int velocity) {
        int d4 = velocity & 0xFFFF;                    // move.w d3,d4 (fraction, non signee)
        int d3 = velocity >> 16;                       // swap d3
        d3 = muls(s16(d3), tempFrames);
        d4 = d4 * tempFrames;                          // mulu d5,d4
        d3 = (d3 << 16) + d4;                          // swap d3 ; clr.w d3 ; add.l d4,d3
        return d3;
    }
}
