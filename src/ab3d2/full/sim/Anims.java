package ab3d2.full.sim;

import ab3d2.full.M68k;
import ab3d2.full.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Les PORTES, les ASCENSEURS et les EAUX : {@code DoorRoutine} (newanims.s:1237),
 * {@code LiftRoutine} (newanims.s:869) et {@code DoWaterAnims} (newanims.s:815), appeles une
 * fois par image depuis {@code objmoveanim}.
 *
 * <p>Ce sont les trois seules machines du jeu qui deplacent le DECOR. Une porte est une zone
 * dont le TOIT descend ({@code ZoneT_Roof = (position>>2)*256}) ; un ascenseur une zone dont le
 * SOL monte ({@code ZoneT_Floor = (position>>2)<<8}). La collision ({@link Move}) lit ces memes
 * hauteurs : une porte qui s'ouvre ne coute rien a la physique, elle lui retire un plafond.
 *
 * <p>Le declenchement est un HANDSHAKE d'aretes : {@link Move} pose le bit du joueur
 * ({@code $100}) dans {@code edgeFlags} au contact ; ici on lit ce drapeau, on le DESARME
 * ({@code $8000}) et on applique la vitesse si {@code drapeau & masque} est non nul. Le masque
 * vient de {@link #doorOpenMask}, {@link #doorCloseMask}, {@link #liftRaiseMask} et
 * {@link #liftLowerMask}.
 *
 * <h2>Deux choses qui se lisent de travers</h2>
 *
 * <ul>
 *   <li>une porte dont le SECOND octet de condition est arme ne se referme JAMAIS d'elle-meme
 *       — voir {@link #doorCloseMask} ;</li>
 *   <li>{@code Anim_DoorAndLiftLocks_l} est un seul long dont les portes lisent le mot FORT
 *       ({@link #doorLocks}) et les ascenseurs le mot FAIBLE ({@link #liftLocks}).</li>
 * </ul>
 *
 * <p>Non porte : les bruitages passent par {@link SfxQueue} et non par
 * {@code MakeSomeNoise} ; le joueur DEUX n'existe pas ; et les enregistrements GRAPHIQUES que
 * les trois routines deforment (le decalage vertical de la texture d'un panneau, le pli du
 * plan d'eau) sont recalcules au dessin.
 *
 * <p>Eprouve au mot pres par {@link AnimsCheck}.
 */
public final class Anims {

    private final LevelSim lvl;
    private final List<Level.Liftable> doors;
    private final List<Level.Liftable> lifts;

    // etat runtime par porte / ascenseur (champs "position"/"speed" du record d'origine)
    private final int[] doorPos, doorSpd, doorTimer;
    private final int[] liftPos, liftSpd;
    /** Les EAUX : bornes, niveau, vitesse et secteurs pousses, tous mutables (le harnais pose). */
    private final int[] waterTop, waterBottom, waterPos, waterSpd;
    private final List<int[]> waterZones = new ArrayList<>();

    /** Ratio d'ouverture [0..1] par porte (pour l'animation du panneau). */
    public final float[] doorOpenRatio;
    /** Hauteur courante du sol de l'ascenseur, en unites de zone (pour le visuel). */
    public final int[] liftFloorH;
    /**
     * Hauteur courante du PLAFOND de la zone-porte, en unites de zone. C'est le DESSOUS du
     * battant : le port ecrit la position de la porte dans le flat de plafond de sa zone
     * ({@code move.w d3,2(a1)} dans DoorRoutine, Newanims.java:1146) en plus des murs qu'elle
     * deforme — sans cela la porte n'est pas un volume, il n'y a que ses cotes qui montent.
     */
    public final int[] doorRoofH;

    /**
     * LE BAS que ce mecanisme impose aux murs qu'il emporte.
     *
     * <p>{@code asr.w #2 ; muls #256} : la position courante, ramenee de deux bits puis portee
     * a l'echelle des hauteurs de zone. Le jeu l'ecrit en long a l'offset 24 de chaque pan
     * deforme ({@code move.l d3,24(a1)}).
     */
    public static int deformBottom(int position) {
        return M68k.muls(M68k.asrw(position, 2), 256);
    }

    /**
     * CE QUE ce mecanisme retranche au decalage de texture des murs qu'il emporte.
     *
     * <p>{@code neg.w ; and.w #255} de la position ramenee de deux bits. Le jeu l'AJOUTE a la
     * base propre au pan et ecrit le tout en mot a l'offset 12 ({@code move.w a2,12(a1)}).
     * C'est ce terme qui fait monter le dessin de la porte AVEC elle, au lieu de le laisser
     * glisser sur un battant qui bouge.
     */
    public static int deformTexture(int position) {
        return (-M68k.asrw(position, 2)) & 255;
    }

    /** Position brute d'une porte (le mot que le jeu ecrit dans les murs qu'elle deforme). */
    public int doorPosition(int i) {
        return i >= 0 && i < doorPos.length ? doorPos[i] : 0;
    }

    /** Position brute d'un ascenseur. */
    public int liftPosition(int i) {
        return i >= 0 && i < liftPos.length ? liftPos[i] : 0;
    }

    /** Vitesse courante d'une porte (le mot 24 de son enregistrement). */
    public int doorSpeedOf(int i) {
        return i >= 0 && i < doorSpd.length ? doorSpd[i] : 0;
    }

    /** Vitesse courante d'un ascenseur. */
    public int liftSpeedOf(int i) {
        return i >= 0 && i < liftSpd.length ? liftSpd[i] : 0;
    }

    /** anim_DoorOpenTimers_vw[i] : depuis combien d'images la porte est grande ouverte. */
    public int doorTimerOf(int i) {
        return i >= 0 && i < doorTimer.length ? doorTimer[i] : 0;
    }

    /** Niveau courant d'une animation d'eau. */
    public int waterPositionOf(int i) {
        return i >= 0 && i < waterPos.length ? waterPos[i] : 0;
    }

    /** Vitesse courante d'une animation d'eau — elle change de SIGNE a chaque butee. */
    public int waterSpeedOf(int i) {
        return i >= 0 && i < waterSpd.length ? waterSpd[i] : 0;
    }

    /**
     * Harnais : pose une animation d'eau VIVANTE a la place d'une des vingt et une inertes.
     *
     * <p>Les seize niveaux du jeu les portent toutes inertes, de sorte qu'un oracle qui se
     * contenterait de les relever n'eprouverait rien du tout. Celui-ci en plante une.
     */
    void planterEau(int i, int top, int bottom, int position, int speed, int[] zones) {
        waterTop[i] = top;
        waterBottom[i] = bottom;
        waterPos[i] = position;
        waterSpd[i] = speed;
        waterZones.set(i, zones);
    }

    // ---- entrees par frame ----
    /** Anim_TempFrames_w : nombre de frames ecoulees (1 a cadence nominale). */
    public int tempFrames = 1;
    /** Zone du joueur 1 (Plr1_Zone_w). */
    public int playerZone = -1;
    /** Plr1_TmpSpcTap_b : touche action tapee cette frame. */
    public boolean playerSpaceTap;
    /** Anim_DoorAndLiftLocks_l : bit i = porte i verrouillee (cles). */
    public int doorLocks;
    /** anim_LiftOnlyLocks_w : bit i = ascenseur i verrouille. */
    public int liftLocks;

    // ---- sorties vers le joueur ----
    /**
     * {@code plr1_StoodOnLift_b}.
     *
     * <p>LE JOUEUR DEUX N'EST PAS MODELISE, et ce n'est pas une approximation : c'est prouve.
     * Le jeu tient un second drapeau, {@code plr2_StoodOnLift_b}, pose par le meme
     * {@code seq} (newanims.s:1015) et relu quatre fois. Deux de ces lectures sont gardees par
     * {@code tst.b Plr2_TmpSpcTap_b}, mais les deux autres — {@code lift1} (newanims.s:1203) et
     * {@code rlift1} (1217) — ne le sont PAS : un ascenseur y part vers sa butee des que l'un
     * OU l'autre des joueurs se tient dessus, sans qu'aucun test de mode n'intervienne.
     *
     * <p>Il est pourtant toujours faux en solo, et par un seul mot. Le prologue solo pointe
     * {@code Plr2_ZonePtr_l} sur {@code BollocksRoom} (hires.s:1186), un secteur leurre dont le
     * premier mot vaut <b>moins un</b> (hires.s:2257) ; la comparaison {@code cmp.w (a3),d5}
     * l'oppose a l'identifiant du secteur de l'ascenseur. Mesure sur les seize niveaux :
     * <b>2385 secteurs, aucun d'identifiant negatif, le plus petit vaut zero</b>. C'est la meme
     * convention que {@code FREE_ENT}, qui marque un emplacement libre par un secteur negatif :
     * moins un est le « nulle part » du jeu, et aucun ascenseur ne peut s'y trouver.
     */
    public boolean playerStoodOnLift;
    /**
     * {@code Plr1_FloorSpd_w} : vitesse du sol mobile sous le joueur.
     *
     * <p>PIEGE DE METHODE. Cherche par son nom absolu, ce mot semble ECRIT DEUX FOIS ET LU
     * NULLE PART — il n'apparait qu'en newanims.s:798 et 1010. Il est pourtant bien relu, deux
     * fois, sous sa forme RELATIVE AU BLOC JOUEUR : {@code move.w PlrT_FloorSpd_w(a0),d2}
     * (modules/player.s:824 et 937), ou {@code a0} porte la base du joueur. Un global de ce jeu
     * ne peut donc pas etre declare mort sur le seul grep de son nom : il faut aussi chercher
     * la forme {@code XxxT_nom(aN)}. (Verifie apres coup : {@code Aud_ChannelPick_b} n'a, lui,
     * aucun acces relatif — il est bien mort.)
     */
    public int playerFloorSpd;
    /**
     * Zone_CurrentDoorState_w : un bit par porte, arme tant qu'elle n'est pas FERMEE.
     *
     * <p>C'est ce que le rendu lit pour savoir si ce qu'une chaine de portes cache est
     * visible. Une porte entrouverte compte comme ouverte : le jeu ne retient que le moment
     * ou elle touche sa butee basse.
     */
    public int doorState;

    // scratch partage par les sous-cas (d7 du 68k)
    private int liftSpeed;
    private int actionSoundFx;      // anim_ActionSoundFX_w : bruit du declenchement
    /** File de bruitages (posee par le jeu ; null = muet). */
    public SfxQueue sfx;
    /** Generateur du jeu, pour les bruits d'ambiance (null = pas d'ambiance). */
    public Nav nav;
    /** GLFT_AmbientSFX_l : 16 bruits d'ambiance possibles. */
    public int[] ambientSfx;
    private int timeToNoise;         // anim_TimeToNoise_w
    private int oddEven;             // anim_OddEven_w : alterne etage bas / haut
    // anim_OpeningSoundFX_w / anim_ClosingSoundFX_w de la porte (ou ascenseur) en cours.
    private int openingSfx, closingSfx;

    public Anims(LevelSim lvl, Level data) {
        this.lvl = lvl;
        this.doors = data.doors() == null ? List.of() : data.doors();
        this.lifts = data.lifts() == null ? List.of() : data.lifts();

        doorPos = new int[doors.size()];
        doorSpd = new int[doors.size()];
        doorTimer = new int[doors.size()];
        doorOpenRatio = new float[doors.size()];
        for (int i = 0; i < doors.size(); i++) {
            doorPos[i] = doors.get(i).position();
            doorSpd[i] = doors.get(i).speed();
        }
        List<Level.Water> eaux = data.waters() == null ? List.of() : data.waters();
        waterTop = new int[eaux.size()];
        waterBottom = new int[eaux.size()];
        waterPos = new int[eaux.size()];
        waterSpd = new int[eaux.size()];
        for (int i = 0; i < eaux.size(); i++) {
            Level.Water w = eaux.get(i);
            waterTop[i] = w.top();
            waterBottom[i] = w.bottom();
            waterPos[i] = w.position();
            waterSpd[i] = w.speed();
            int[] zs = new int[w.zones().size()];
            for (int k = 0; k < zs.length; k++) {
                zs[k] = w.zones().get(k);
            }
            waterZones.add(zs);
        }
        liftPos = new int[lifts.size()];
        liftSpd = new int[lifts.size()];
        liftFloorH = new int[lifts.size()];
        doorRoofH = new int[doors.size()];
        for (int i = 0; i < lifts.size(); i++) {
            liftPos[i] = lifts.get(i).position();
            liftSpd[i] = lifts.get(i).speed();
        }
    }

    /**
     * objmoveanim (newanims.s:764) : BACKSFX, puis les PORTES, puis les ASCENSEURS.
     *
     * <p>L'ordre n'est pas indifferent et il va dans ce sens-la : {@code Plr1_FloorSpd_w} est
     * remis a zero ENTRE les deux (newanims.s:798), et les deux routines se partagent le
     * tableau des drapeaux d'aretes, que chacune desarme en passant.
     *
     * <p>{@link #playerStoodOnLift} n'est pas remis a zero ici : l'original le POSE ou le LEVE
     * a chaque ascenseur ({@code seq plr1_StoodOnLift_b}), de sorte qu'apres la boucle il ne
     * dit pas « le joueur est sur un ascenseur » mais « le joueur est sur le DERNIER ». Un
     * niveau sans ascenseur ne le touche donc jamais.
     */
    public void run() {
        backSfx();
        doorRoutine();
        playerFloorSpd = 0;                            // move.w #0,Plr1_FloorSpd_w
        liftRoutine();
    }

    /**
     * BACKSFX (Newanims.java:525) : toutes les {@code 100 + hasard} frames, un bruit d'ambiance
     * tire au hasard parmi ceux que la ZONE autorise ({@code ZoneT_BackSFXMask_w}, un bit par
     * bruit) — souffle de ventilation, grondements, gouttes. Sans position : c'est l'atmosphere.
     */
    private void backSfx() {
        if (sfx == null || nav == null || ambientSfx == null) {
            return;
        }
        timeToNoise = M68k.s16(timeToNoise - tempFrames); // sub.w Anim_TempFrames_w
        if ((short) timeToNoise > 0) {
            return;
        }
        timeToNoise = (((nav.rand() >>> 3) & 127) + 100); // and.w #127 ; add.w #100
        LevelSim.Zone z = zoneOf(playerZone);
        if (z == null) {
            return;
        }
        int mask = oddEven == 0 ? z.backSfxMask : z.upperBackSfxMask;
        oddEven = 2 - oddEven;                         // move.w #2,d0 ; sub.w anim_OddEven_w,d0
        if ((short) mask == 0) {
            return;
        }
        int d0 = (nav.rand() >>> 3) & 0xFFFF;
        for (int guard = 0; guard < 64; guard++) {     // .notfound : premier bit arme rencontre
            d0 = (d0 + 1) & 15;
            if ((mask & (1 << d0)) != 0) {
                break;
            }
        }
        if (d0 < ambientSfx.length) {
            // newanims.s:758 : le VOLUME est le rang du bruit d'ambiance lui-meme, et le
            // drapeau « pas si ca joue » est ARME — un bruit d'ambiance ne se superpose pas.
            sfx.id = 0xFFF0;                           // move.w #$fff0,IDNUM
            sfx.notIfPlaying = true;                   // st.b notifplaying
            sfx.here(ambientSfx[d0], d0, 0, 0);        // move.l #0,Aud_NoiseX_w
        }
    }

    // --------------------------------------------------------------- portes

    /** DoorRoutine (Newanims.java:1039). */
    private void doorRoutine() {
        for (int i = 0; i < doors.size(); i++) {
            Level.Liftable dr = doors.get(i);
            int d0 = dr.bottom();                        // bottom of movement
            int d1 = dr.top();                           // top of movement
            int openingSpeed = -dr.openSpeed();       // neg.w anim_OpeningSpeed_w
            int closingSpeed = dr.closeSpeed();
            // subq.w #1 AU CHARGEMENT (newanims.s:896) : un bruitage a 0 devient -1, et le
            // test plus bas ne joue alors RIEN. C'est ce -1 qui manquait — la porte rouge du
            // niveau A (closingSFX = 0) reclamait l'echantillon -1 a chaque frame.
            openingSfx = dr.openingSound() - 1;
            closingSfx = dr.closingSound() - 1;
            int openDuration = dr.openDuration();

            int d3 = doorPos[i];                       // position courante
            int d2 = doorSpd[i];                       // vitesse courante
            d3 = M68k.setw(d3, d3 + M68k.muls(d2, tempFrames)); // muls Anim_TempFrames_w,d2 ; add.w d2,d3
            d2 = doorSpd[i];                           // re-lecture (move.w 2(a0),d2)

            boolean doorClosed = (short) d0 <= (short) d3;   // sle anim_DoorClosed_b
            if (!((short) d0 > (short) d3)) {
                if (d2 != 0 && sfx != null) {          // tst.w d2 ; beq .nonoise (elle CLAQUE)
                    sfx.id = 0xFFFD;               // move.w #$fffd,IDNUM
                    sfx.notIfPlaying = false;      // clr.b notifplaying
                    sfx.door(dr.closedSound() - 1, 50, dr.x(), dr.z());
                }
                d2 = 0;
                d0 = M68k.setw(d0, d3);                // move.w d3,d0 (quirk d'origine : pas de clamp)
            }
            // NOTMOVING (Newanims.java:1129) : l'etat de visibilite de cette porte.
            // move.w Zone_CurrentDoorState_w,d6 ... move.w d6,Zone_CurrentDoorState_w : le
            // registre porte trente-deux bits, mais ce qui est RELU n'en fait que seize. Une
            // porte de rang seize ou plus n'aurait donc jamais d'etat ; aucun niveau n'en a
            // autant, mais le mot se garde.
            if (doorClosed) {
                doorState &= ~(1 << (i & 31));         // bclr d7,d6
            } else {
                doorState |= 1 << (i & 31);            // bset d7,d6
            }
            doorState &= 0xFFFF;

            boolean doorOpen = (short) d1 >= (short) d3;     // sge anim_DoorOpen_b
            if (!((short) d1 < (short) d3)) {
                if (d2 != 0) {                         // elle vient d'arriver en butee haute
                    doorTimer[i] = 0;                  // move.w #0,(a6) : le compte a rebours
                    if (sfx != null) {                 // de refermeture repart de zero
                        sfx.id = 0xFFFD;           // move.w #$fffd,IDNUM
                        sfx.notIfPlaying = false;  // clr.b notifplaying
                        sfx.door(dr.openedSound() - 1, 50, dr.x(), dr.z());
                    }
                }
                d3 = M68k.setw(d3, d1);                // clamp a "ouvert"
                d2 = 0;
            }

            doorPos[i] = (short) d3;
            doorSpd[i] = (short) d2;
            int d7 = d2;                               // vitesse a appliquer si declenche

            // toit de la zone-porte : ZoneT_Roof_l = (position>>2)*256
            int roof = M68k.muls(M68k.asrw(d3, 2), 256);
            LevelSim.Zone zone = zoneOf(dr.zone());
            if (zone != null) {
                zone.roofH = roof;
            }
            doorRoofH[i] = roof;
            int denom = dr.bottom() - dr.top();
            // Math.clamp n'existe qu'a partir de Java 21, et ce code doit aussi tourner sur
            // un telephone, ou la bibliotheque s'arrete plus tot. Le resultat est le meme,
            // valeurs non numeriques comprises.
            doorOpenRatio[i] = denom == 0 ? 0f
                    : Math.max(0f, Math.min(1f, (dr.bottom() - (float) (short) d3) / denom));

            // masque de declenchement
            int mask;
            boolean simple = false;
            if (dr.zone() == playerZone && !doorOpen && (short) d2 >= 0) {
                // .gobackup : le joueur est DANS la porte -> elle remonte
                d7 = -16;
                mask = 0x8000;
            } else if ((doorLocks & (1 << (i & 31))) != 0) {
                mask = 0;                              // verrouillee (cle) -> DOOR_SIMPLE
                simple = true;
            } else if (doorOpen) {
                liftSpeed = d7;
                mask = doorCloseMask(i, openDuration, closingSpeed, dr.closeBits());
                d7 = liftSpeed;
            } else if (doorClosed) {
                liftSpeed = d7;
                mask = doorOpenMask(dr.openBits(), openingSpeed);
                d7 = liftSpeed;
            } else {
                mask = 0;
            }

            applyToWalls(dr.walls(), mask, simple, i, d7, true);
        }
    }

    /** tstdoortoopen (Newanims.java:1259). */
    private int doorOpenMask(int openBits, int openingSpeed) {
        actionSoundFx = openingSfx;                    // anim_ActionSoundFX = anim_OpeningSoundFX
        if (openBits < 1) {                            // door0 : manuelle (touche action)
            int d1 = 0;
            if (playerSpaceTap) {
                d1 = 0b100000000;                      // bit joueur 1
            }
            liftSpeed = openingSpeed;
            return d1;
        } else if (openBits == 1) {                    // door1
            liftSpeed = openingSpeed;
            return 0b100100000000;
        } else if (openBits < 3) {                     // door2
            liftSpeed = openingSpeed;
            return 0b10000000000;
        } else if (openBits == 3) {                    // door3
            liftSpeed = openingSpeed;
            return 0b1000000000;
        } else if (openBits < 5) {                     // door4
            liftSpeed = openingSpeed;
            return 0x8000;
        }
        return 0;                                      // door5 : ne s'ouvre pas
    }

    /**
     * tstdoortoclose (newanims.s:1560) : le compte a rebours de refermeture.
     *
     * <p>{@code d4} n'est PAS remis a zero en entrant : {@code satisfied} vient d'y mettre le
     * SECOND octet de condition de la porte (offset 35), et la routine se contente d'y poser
     * UN quand la duree n'est pas ecoulee. Une porte dont cet octet est arme tombe donc
     * toujours dans {@code dclose1} : <b>elle ne se referme jamais d'elle-meme</b>, quelle que
     * soit sa duree d'ouverture et quelle que soit sa vitesse de fermeture.
     *
     * <p>Ce n'est pas une curiosite de coin de carte : sur les seize niveaux, SOIXANTE-DIX-NEUF
     * portes ont cet octet arme, et quatorze d'entre elles ont par ailleurs une vraie vitesse
     * de fermeture — elles restaient grandes ouvertes dans le jeu d'origine et se refermaient
     * dans le portage.
     */
    private int doorCloseMask(int i, int openDuration, int closingSpeed, int d4) {
        int d1 = M68k.setw(0, doorTimer[i] + tempFrames);
        doorTimer[i] = (short) d1;
        boolean stayOpen = (short) d1 < (short) openDuration || (d4 & 0xFFFF) != 0;
        // move.w anim_ClosingSoundFX_w,anim_ActionSoundFX_w : le port le fait AVANT de brancher,
        // donc une porte OUVERTE reclame le bruit de FERMETURE, pas celui d'ouverture. Sans ca,
        // une porte qui reste ouverte (grande porte rouge du niveau A : closingSpeed 0,
        // openDuration 0) rejoue son bruit d'ouverture a chaque frame.
        actionSoundFx = closingSfx;
        if (!stayOpen) {                               // dclose0 : la duree est ecoulee
            liftSpeed = closingSpeed;
            return 0x8000;
        }
        return 0;                                      // dclose1 : reste ouverte
    }

    // ----------------------------------------------------------- ascenseurs

    /** LiftRoutine (Newanims.java:734). */
    private void liftRoutine() {
        for (int i = 0; i < lifts.size(); i++) {
            Level.Liftable lf = lifts.get(i);
            int d0 = lf.bottom();
            int d1 = lf.top();
            int openingSpeed = -lf.openSpeed();       // neg.w
            int closingSpeed = lf.closeSpeed();
            openingSfx = lf.openingSound() - 1;            // subq.w #1 (idem portes)
            closingSfx = lf.closingSound() - 1;

            int d3 = liftPos[i];
            int d2 = liftSpd[i];
            int floorMoveSpeed = d2;                   // anim_FloorMoveSpeed_w
            d3 = M68k.setw(d3, d3 + M68k.muls(d2, tempFrames));

            boolean atBottom = (short) d0 <= (short) d3;
            if (!((short) d0 > (short) d3)) {
                // TODO audio
                d2 = 0;
                d3 = M68k.setw(d3, d0);                // clamp en bas
            }
            boolean atTop = (short) d1 >= (short) d3;
            if (!((short) d1 < (short) d3)) {
                // TODO audio
                d2 = 0;
                d3 = M68k.setw(d3, d1);                // clamp en haut
            }

            liftPos[i] = (short) d3;
            liftSpd[i] = (short) d2;
            int d7 = d2;

            // sol de la zone-ascenseur : ZoneT_Floor_l = (position>>2)<<8
            int floor = ((short) M68k.asrw(d3, 2)) << 8;
            LevelSim.Zone zone = zoneOf(lf.zone());
            if (zone != null) {
                zone.floorH = floor;
            }
            liftFloorH[i] = floor;

            // le joueur est-il sur l'ascenseur ? (plr1_StoodOnLift_b / Plr1_FloorSpd_w)
            // cmp.w (a3),d5 ; seq plr1_StoodOnLift_b : POSE OU LEVE a chaque ascenseur. La
            // vitesse du sol, elle, ne se pose que sur egalite — c'est donc le dernier
            // ascenseur sous le joueur qui l'emporte.
            playerStoodOnLift = lf.zone() == playerZone;
            if (playerStoodOnLift) {
                playerFloorSpd = floorMoveSpeed;       // move.w anim_FloorMoveSpeed_w,Plr1_FloorSpd_w
            }

            int mask;
            boolean simple = false;
            if ((liftLocks & (1 << (i & 31))) != 0) {
                mask = 0;                              // verrouille -> LIFT_SIMPLE
                simple = true;
            } else if (atTop) {
                liftSpeed = d7;
                mask = liftLowerMask(lf.closeBits(), closingSpeed, lf.zone() == playerZone);
                d7 = liftSpeed;
            } else if (atBottom) {
                liftSpeed = d7;
                mask = liftRaiseMask(lf.openBits(), openingSpeed, lf.zone() == playerZone);
                d7 = liftSpeed;
            } else {
                mask = 0;
            }

            applyToWalls(lf.walls(), mask, simple, i, d7, false);
        }
        waterAnims();                                  // bsr DoWaterAnims, a la fin des lifts
    }

    /**
     * DoWaterAnims (newanims.s:815) : les eaux montent et descendent.
     *
     * <p>Un niveau d'eau est un LONG qui va et vient entre deux bornes, et qui rebondit en
     * changeant le SIGNE de sa vitesse. Chaque animation pousse sa valeur dans
     * {@code ZoneT_Water_l} d'autant de secteurs qu'elle en porte — c'est ce long que le joueur
     * compare a sa propre hauteur pour savoir s'il nage.
     *
     * <p>La routine en lit toujours VINGT ET UNE : {@code move.w #20,d0} puis {@code dbra}, et
     * pas l'ombre d'une sentinelle. Les seize niveaux du jeu les portent toutes INERTES —
     * bornes a zero, aucun secteur — de sorte que la machine tourne a vide d'un bout a l'autre
     * de la campagne. Elle est portee quand meme, et {@link AnimsCheck} en plante une vivante
     * pour l'eprouver.
     *
     * <p>Les butees ne sont pas symetriques : en bas la position est RAMENEE sur la borne, en
     * haut elle l'est aussi — mais le test du haut est un {@code bgt} et celui du bas un
     * {@code blt}, de sorte qu'une position pile sur la borne BASSE rebondit et qu'une position
     * pile sur la borne HAUTE rebondit elle aussi. Les deux cotes collent.
     */
    private void waterAnims() {
        for (int i = 0; i < waterPos.length; i++) {    // move.w #20,d0 ; dbra d0,wateranimlop
            int d1 = waterTop[i];                      // move.l (a0)+,d1
            int d2 = waterBottom[i];                   // move.l (a0)+,d2
            int d3 = waterPos[i];                      // move.l (a0),d3
            int d4 = waterSpd[i];                      // move.w 4(a0),d4
            int d5 = M68k.muls(M68k.s16(d4), tempFrames); // move.w d4,d5 ; muls Anim_TempFrames_w,d5
            d3 = d3 + d5;                              // add.l d5,d3
            if (d3 > d1) {                             // cmp.l d1,d3 ; bgt.s waternotattop
                if (d3 >= d2) {                        // cmp.l d2,d3 ; blt.s waterdone
                    d3 = d2;                           // move.l d2,d3
                    d4 = M68k.setw(d4, -M68k.s16(d4)); // neg.w d4
                }
            } else {
                d3 = d1;                               // move.l d1,d3
                d4 = M68k.setw(d4, -M68k.s16(d4));     // neg.w d4
            }
            // waterdone
            waterPos[i] = d3;                          // move.l d3,(a0)+
            waterSpd[i] = M68k.s16(d4);                // move.w d4,(a0)+
            for (int z : waterZones.get(i)) {          // morezones
                LevelSim.Zone zone = zoneOf(z);
                if (zone != null) {
                    zone.water = d3;                   // move.l d1,ZoneT_Water_l(a1)
                }
            }
        }
    }

    /** tstliftlower (Newanims.java:947). */
    private int liftLowerMask(int lowerBits, int closingSpeed, boolean stoodOn) {
        actionSoundFx = closingSfx;                    // anim_ActionSoundFX = anim_ClosingSoundFX
        if ((byte) lowerBits < 1) {                    // lift0
            int d1 = 0;
            if (playerSpaceTap) {
                d1 = 0b100000000;
                liftSpeed = closingSpeed;
                if (stoodOn) {
                    return 0x8000;
                }
            }
            return d1;
        } else if (lowerBits == 1) {                   // lift1
            // tst.b plr1_StoodOnLift_b ; bne lift1b ; tst.b plr2_StoodOnLift_b ; bne lift1b :
            // le jeu accepte l'UN OU L'AUTRE des joueurs, sans test de mode. Le second terme
            // est inerte en solo — voir {@link #playerStoodOnLift} pour la preuve.
            liftSpeed = closingSpeed;
            return stoodOn ? 0x8000 : 0b100100000000;
        } else if (lowerBits < 3) {                    // lift2
            liftSpeed = closingSpeed;
            return 0x8000;
        }
        return 0;                                      // lift3
    }

    /** tstliftraise (Newanims.java:1000). */
    private int liftRaiseMask(int raiseBits, int openingSpeed, boolean stoodOn) {
        actionSoundFx = openingSfx;                    // anim_ActionSoundFX = anim_OpeningSoundFX
        if ((byte) raiseBits < 1) {                    // rlift0
            int d1 = 0;
            if (playerSpaceTap) {
                d1 = 0b100000000;
                liftSpeed = openingSpeed;
                if (stoodOn) {
                    return 0x8000;
                }
            }
            return d1;
        } else if (raiseBits == 1) {                   // rlift1
            // Meme chose qu'a lift1 : le second joueur y entre sans garde, et reste inerte.
            liftSpeed = openingSpeed;
            return stoodOn ? 0x8000 : 0b100100000000;
        } else if (raiseBits < 3) {                    // rlift2
            liftSpeed = openingSpeed;
            return 0x8000;
        }
        return 0;                                      // rlift3
    }

    // ------------------------------------------------------------- commun

    /**
     * backfromtst / dothesimplething : parcourt les aretes du panneau, lit puis DESARME leur
     * flag, et applique la vitesse si le masque correspond.
     */
    private void applyToWalls(List<Level.Liftable.Wall> walls, int mask, boolean simple,
                              int index, int speed, boolean door) {
        for (Level.Liftable.Wall w : walls) {
            int wi = w.edge();
            if (wi < 0 || wi >= lvl.edgeFlags.length) {
                continue;
            }
            if (simple) {
                lvl.edgeFlags[wi] = 0;                 // porte/lift verrouille : flag efface
                continue;
            }
            int flags = lvl.edgeFlags[wi];
            lvl.edgeFlags[wi] = 0x8000;                // desarme (valeur d'origine)
            if ((flags & mask) != 0) {
                if (door) {
                    doorSpd[index] = (short) speed;
                } else {
                    liftSpd[index] = (short) speed;
                }
                // move.w anim_ActionSoundFX_w,Aud_SampleNum_w ; blt.s nothinghit — un bruitage
                // NEGATIF ne joue rien. Le masque de fermeture vaut 0x8000 et les drapeaux
                // d'arete sont rearmes a 0x8000 chaque frame : sans cette garde, une porte sans
                // bruit de fermeture rejouait un son a chaque frame, indefiniment.
                if (sfx != null && actionSoundFx >= 0) {
                    Level.Liftable src = door ? doors.get(index) : lifts.get(index);
                    sfx.id = 0xFFFD;               // move.w #$fffd,IDNUM
                    sfx.notIfPlaying = false;      // clr.b notifplaying
                    sfx.door(actionSoundFx, 50, src.x(), src.z());
                }
            }
        }
    }

    /** Hauteur de dessin approchee d'une porte/ascenseur, pour situer son bruit. */
    private int doorHeight(Level.Liftable l) {
        LevelSim.Zone z = zoneOf(l.zone());
        return z == null ? 0 : (short) (z.floorH >> 7);
    }

    private LevelSim.Zone zoneOf(int id) {
        return id >= 0 && id < lvl.zones.length ? lvl.zones[id] : null;
    }
}
