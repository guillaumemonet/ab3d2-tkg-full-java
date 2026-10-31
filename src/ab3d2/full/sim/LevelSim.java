package ab3d2.full.sim;

import ab3d2.full.level.Level;

import java.util.List;

/**
 * Etat RUNTIME d'un niveau pour la simulation (collision, portes).
 *
 * <p>Tableaux plats indexes comme dans le port : les aretes par index global
 * (Lvl_ZoneEdgePtr), les zones par id (Lvl_ZonePtrsPtr). {@link #edgeFlags} est le
 * EdgeT_Flags_w runtime : {@link Move} y pose le bit du joueur au contact, {@link Doors}
 * le lit puis le desarme (handshake d'origine).
 */
public final class LevelSim {

    /** Aretes globales (EdgeT). */
    public final int[] edgeX, edgeZ, edgeDx, edgeDz, edgeJoin, edgeW5, edgeB12, edgeB13;
    /** EdgeT_Flags_w runtime, partage entre Move et Doors. */
    public final int[] edgeFlags;
    /** Zones indexees par id (trous possibles = null). */
    public final Zone[] zones;

    public static final class Zone {
        public int floorH, roofH, upperFloorH, upperRoofH;
        public int water;                     // ZoneT_Water_l
        /** ZoneT_FloorNoise_w / UpperFloorNoise_w : type de sol (bruit de pas + degats). */
        public int floorNoise, upperFloorNoise;
        /** ZoneT_BackSFXMask_w : bruits d'ambiance possibles dans la zone (bas / haut). */
        public int backSfxMask, upperBackSfxMask;
        public int telZone, telX, telZ;       // ZoneT_TelZone_w / TelX / TelZ
        public int[] edgeList;                // liste brute : index d'aretes, -1 fin de groupe, -2 fin
    }

    private LevelSim(int n, Zone[] zones) {
        edgeX = new int[n];
        edgeZ = new int[n];
        edgeDx = new int[n];
        edgeDz = new int[n];
        edgeJoin = new int[n];
        edgeW5 = new int[n];
        edgeB12 = new int[n];
        edgeB13 = new int[n];
        edgeFlags = new int[n];
        this.zones = zones;
    }

    public static LevelSim of(Level lvl) {
        int maxId = 0;
        for (Level.Zone z : lvl.zones()) {
            maxId = Math.max(maxId, z.id());
        }
        Zone[] zones = new Zone[maxId + 1];
        for (Level.Zone src : lvl.zones()) {
            Zone z = new Zone();
            z.floorH = src.floor();
            z.roofH = src.ceiling();
            z.upperFloorH = src.upperFloor();
            z.upperRoofH = src.upperCeiling();
            z.water = src.water();
            z.floorNoise = src.floorSound();
            z.upperFloorNoise = src.upperFloorSound();
            z.backSfxMask = src.ambientSoundMask();
            z.upperBackSfxMask = src.upperAmbientSoundMask();
            z.telZone = src.teleportZone();
            z.telX = src.teleportX();
            z.telZ = src.teleportZ();
            List<Integer> raw = src.edgeList();
            z.edgeList = new int[raw.size()];
            for (int i = 0; i < z.edgeList.length; i++) {
                z.edgeList[i] = raw.get(i);
            }
            zones[src.id()] = z;
        }

        List<Level.Edge> edges = lvl.edges();
        LevelSim sim = new LevelSim(edges.size(), zones);
        for (int i = 0; i < edges.size(); i++) {
            Level.Edge e = edges.get(i);
            sim.edgeX[i] = e.x();
            sim.edgeZ[i] = e.z();
            sim.edgeDx[i] = e.dx();
            sim.edgeDz[i] = e.dz();
            sim.edgeJoin[i] = e.joinZone();
            sim.edgeW5[i] = e.divider();
            sim.edgeB12[i] = e.normalX();
            sim.edgeB13[i] = e.normalZ();
        }
        return sim;
    }
}
