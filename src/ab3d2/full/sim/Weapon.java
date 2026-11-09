package ab3d2.full.sim;

import ab3d2.full.defs.Database;

import java.util.List;

/**
 * L'ARME EN MAIN : non pas un decor plaque sur l'image, mais un OBJET du monde.
 *
 * <p>C'est la trouvaille de ce chapitre, et elle explique pourquoi l'arme bouge si bien : le
 * jeu ne la dessine pas a part. Il lui donne l'entite situee DEUX RANGS apres celle du joueur,
 * la pose a chaque image a l'endroit exact de l'oeil, et laisse le rendu des objets faire le
 * reste. Elle passe donc par le meme eclairage, la meme animation et le meme dessin de modele
 * que tout ce qui l'entoure.
 *
 * <p>Deux routines s'en occupent, et ce portage les garde separees comme l'original :
 *
 * <ul>
 *   <li>{@link #place} est la queue de {@code Plr1_Use} (hires.s:2487). Elle repose TOUT a
 *       chaque image : l'angle, le secteur, le point, le type, la hauteur. Rien n'y est
 *       cumule — le cumul viendrait d'une image a l'autre, et il n'y en a pas ;</li>
 *   <li>{@link #animate} est {@code ACTANIMOBJ} appele par {@code GUNHELD}
 *       (newaliencontrol.s:103). Elle lit le pas ou l'animation en est, en tire l'apparence,
 *       AJOUTE son angle et sa hauteur a ce que la precedente a pose, puis passe au suivant.
 *       C'est elle, et elle seule, qui donne le recul du tir.</li>
 * </ul>
 *
 * <h2>Trois details qui ne vont pas de soi</h2>
 *
 * <ul>
 *   <li>l'arme REGARDE le joueur : son angle vaut celui du joueur plus un demi-tour ;</li>
 *   <li>son animation est TOUJOURS celle de l'activation ({@code st EntT_WhichAnim_b}), jamais
 *       celle du repos. Le premier pas de ce script boucle sur lui-meme et c'est l'arme au
 *       repos ; tirer saute au pas UN, et la suite ramene au repos toute seule ;</li>
 *   <li>changer d'arme ne remet pas l'animation a zero. Le jeu change le TYPE de l'objet et
 *       laisse le compteur de pas ou il etait — c'est pourquoi ce portage tient ce compteur a
 *       part, au lieu de reutiliser {@link ObjectAnim} qui repart de zero en changeant de
 *       script.</li>
 * </ul>
 *
 * <h2>Ce que le corpus ne separe pas</h2>
 *
 * <p>Une seule mutation survit : retirer la premiere mise au MOT de la hauteur. Elle est
 * equivalente, et la raison est arithmetique — la seconde mise au mot la subsume, puisque
 * tronquer puis ajouter puis tronquer donne le meme reste que d'ajouter puis tronquer. On la
 * garde parce qu'elle est l'instruction du jeu ({@code move.w} avant {@code add.w}), et que la
 * prochaine main qui lira cette ligne doit y voir ce que la machine faisait.
 */
public final class Weapon {

    /** OBJ_TYPE_OBJECT : ce que l'arme se declare etre, pour le rendu. */
    public static final int OBJECT = 1;

    /** SINE_SIZE : un demi-tour, en deplacement d'octets dans la table de sinus. */
    private static final int HALF_TURN = 4096;

    /** SINTAB_MASK_ADR : le masque qui ramene l'angle dans un tour. */
    private static final int ANGLE_MASK = 8190;

    /** Ce que l'arme se tient au-dessus de la main, avant le partage par cent vingt-huit. */
    private static final int RAISE = 10 * 128;

    /** Le type de dessin d'un modele en relief. */
    private static final int GFX_VECTOR = 1;

    /** Le rang de l'objet que l'arme choisie fait tenir, ou moins un si l'arme n'existe pas. */
    public int object = -1;

    /** ObjT_TypeID_b. */
    public int typeId;

    /** EntT_CurrentAngle_w. */
    public int angle;

    /** Le secteur ou se trouve l'arme ; moins un quand le joueur est mort et qu'on la libere. */
    public int zone = -1;

    /** Sa position dans le monde, en virgule fixe seize-seize : celle du joueur, telle quelle. */
    public int x;
    public int z;

    /** Le mot de hauteur de l'enregistrement de dessin. */
    public int height;

    /** ShotT_InUpperZone_b. */
    public boolean upper;

    /** EntT_WhichAnim_b : vrai des que l'arme est posee, et il ne redescend jamais. */
    public boolean active;

    /** EntT_Timer1_w : le pas ou l'animation en est, donc le PROCHAIN a montrer. */
    public int step;

    /** Le pas que l'image courante montre, ou null si l'arme n'est pas dessinee. */
    public Database.AnimStep shown;

    /** Le type de dessin de l'objet montre : sprite, modele, ou miroitement. */
    public int gfxType;

    /**
     * La queue de {@code Plr1_Use} : ou l'arme se tient, cette image-ci.
     *
     * @param p      le joueur, tel que l'image vient de l'arreter
     * @param glf    la base de liaison, pour l'objet que l'arme fait tenir
     * @param gun    l'arme choisie
     * @param health la sante : au-dessous de un, l'entite est LIBEREE et rien n'est dessine
     */
    public void place(PlayerSim p, Database glf, int gun, int health) {
        if (health <= 0) {
            zone = -1;                                 // FREE_OBJ_2 a0,ENT_NEXT_2
            shown = null;
            return;
        }
        // move.w EntT_CurrentAngle_w(a0),d0 ; add.w #SINE_SIZE,d0 ; AMOD_A d0
        angle = (p.angPos + HALF_TURN) & ANGLE_MASK;
        zone = p.zone;                                 // move.w (a1),ObjT_ZoneID_w+ENT_NEXT_2

        Database.Gun arme = glf == null ? null : glf.gun(gun);
        object = arme == null ? -1 : arme.gunObject(); // move.w (a1,d0.w*2),d0
        typeId = OBJECT;                               // move.b #OBJ_TYPE_OBJECT,...

        // Les deux longs du point du joueur sont recopies DANS le point de l'arme : elle est
        // exactement la ou il est, et c'est pourquoi sa profondeur vue est nulle.
        x = p.xOff;
        z = p.zOff;

        active = true;                                 // st EntT_WhichAnim_b+ENT_NEXT_2(a0)

        // La hauteur : l'oeil, plus le QUART de la taille, plus mille deux cent quatre-vingt,
        // le tout ramene de sept bits.
        int d0 = p.snapYOff;                           // move.l Plr1_TmpYOff_l,d0
        int d1 = p.height >> 2;                        // asr.l #2,d1
        d1 += RAISE;                                   // add.l #10*128,d1
        d0 += d1;
        d0 >>= 7;                                      // asr.l #7,d0
        height = (short) d0;                           // move.w d0,4+ENT_NEXT_2(a0)

        // Et le balancement du pas, une fois et DEMIE.
        int b = p.bobbleY >> 8;                        // move.l plr1_BobbleY_l,d1 ; asr.l #8,d1
        b += b >> 1;                                   // move.l d1,d0 ; asr.l #1,d0 ; add.l d0,d1
        height = (short) (height + b);                 // add.w d1,4+ENT_NEXT_2(a0)

        upper = p.stoodInTop;                          // move.b ShotT_InUpperZone_b(a0),...
    }

    /**
     * {@code ACTANIMOBJ} pour l'arme en main : l'apparence du pas courant, puis le suivant.
     *
     * <p>A appeler APRES {@link #place}, comme le jeu qui anime tous ses objets une fois les
     * enregistrements poses. L'angle et la hauteur s'AJOUTENT a ce que la pose vient d'ecrire.
     */
    public void animate(Database glf) {
        shown = null;
        if (zone < 0 || glf == null || object < 0) {
            return;
        }
        Database.ObjectDef def = glf.object(object);
        if (def == null) {
            return;
        }
        List<Database.AnimStep> script = def.activeAnim();
        if (script.isEmpty()) {
            return;
        }
        if (step < 0 || step >= script.size()) {
            step = 0;
        }
        Database.AnimStep pas = script.get(step);
        gfxType = def.gfxType();
        shown = pas;
        if (gfxType == GFX_VECTOR) {
            // move.w 2(a3,d0.w),d1 ; add.w d1,EntT_CurrentAngle_w(a0)
            angle = (angle + pas.word2()) & 0xFFFF;
        }
        height = (short) (height + 2 * pas.delta());   // ext.w d1 ; add.w d1,d1 ; add.w d1,4(a0)
        step = pas.next();                             // move.w d1,EntT_Timer1_w(a0)
    }

    /**
     * Le joueur vient de tirer : l'animation saute au pas UN.
     *
     * <p>{@code move.w #1,ENT_NEXT_2+EntT_Timer1_w(a2)} (newplayershoot.s:165), ecrit au moment
     * ou la balle part — pas quand la detente est pressee.
     */
    public void fire() {
        step = 1;
    }
}
