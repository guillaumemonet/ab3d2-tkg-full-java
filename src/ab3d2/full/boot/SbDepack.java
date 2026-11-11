package ab3d2.full.boot;

/**
 * Dépacking des fichiers d'origine, en-tête {@code =SB=}.
 *
 * <p>Le jeu charge ses fichiers avec {@code io_LoadFile} (modules/file_io.s) : si les quatre
 * premiers octets valent {@code '=SB='}, il branche sur {@code io_HandlePacked}, qui alloue la
 * taille dépackée puis appelle {@code jsr unLHA}. Et {@code unLHA} n'est rien d'autre qu'un
 * {@code incbin "decomp4.raw"} — 2508 octets de 68k, le même blob que celui embarqué dans
 * l'outil {@code SBDepack} de 1997 (« SB Depack by Jason Frecknall / Decrunch algorithm by
 * Team 17 »).
 *
 * <p><b>En-tête</b> :
 * <pre>
 *   +0  4  '=SB='  ($3D53423D)
 *   +4  4  taille DÉPACKÉE      (io_HandlePacked : move.l 4(a0),d0)
 *   +8  4  taille PACKÉE
 *   +12 …  flux compressé
 * </pre>
 *
 * <p><b>L'algorithme</b> est celui de LHA, malgré ce que laisse croire le nom « Team 17 ».
 * Le désassemblage de {@code decomp4.raw} le montre sans ambiguïté :
 * <pre>
 *   07d4: moveq #$13,d1     ; 19  = NT
 *   07d6: moveq #$5,d0      ;  5  = TBIT
 *   07d8: moveq #$3,d2      ;  3  = i_special
 *   07da: bsr  $344         ; read_pt_len(19, 5, 3)
 *   07de: bsr  $4f0         ; read_c_len()
 *   07e2: move.w np,d1 ; moveq #$4,d0 ; cmp.w #$10,d1 ; blt ; addq #1,d0
 *   07f2: bsr  $344         ; read_pt_len(np, pbit, -1)
 * </pre>
 * soit exactement la séquence de {@code huf.c}. Le blob porte DEUX points d'entrée, qui ne
 * diffèrent que par {@code np} :
 * <pre>
 *   01a0: move.w #$1fe,NC ; move.w #$e ,np   ; bsr $1c0   ; np=14 -> dicbit 13 (-lh5-)
 *   01b0: move.w #$1fe,NC ; move.w #$10,np   ; bsr $1c0   ; np=16 -> dicbit 15 (-lh6-)
 * </pre>
 * et le wrapper appelle le SECOND ({@code bsr $1b0}) : c'est donc du <b>-lh6-</b>, fenêtre de
 * 32 Ko. C'est ce détail qui fait toute la différence — décodé en {@code -lh5-} le flux part
 * en vrille dès le premier bloc.
 *
 * <p>Validé sur les cinq disquettes : 313 fichiers packés, 313 décodés sans erreur, et
 * identiques au bit près aux fichiers dépackés de référence partout où la même variante
 * (2 Mo / 4 Mo) existe des deux côtés.
 */
public final class SbDepack {

    /** {@code cmp.l #'=SB=',d0} (file_io.s:265). */
    public static final int MAGIC = 0x3D53423D;

    // huf.c — les constantes du format.
    private static final int NC = 510;          // 256 + MAXMATCH - THRESHOLD + 1
    private static final int NT = 19;
    private static final int CBIT = 9;
    private static final int TBIT = 5;
    private static final int THRESHOLD = 3;
    /** decomp4.raw entre en $1b0 : np = 16, donc dicbit = 15 (fenêtre de 32 Ko). */
    private static final int DICBIT = 15;

    private SbDepack() {
    }

    public static boolean isPacked(byte[] d) {
        return d != null && d.length >= 12
                && ((d[0] & 0xFF) << 24 | (d[1] & 0xFF) << 16
                    | (d[2] & 0xFF) << 8 | (d[3] & 0xFF)) == MAGIC;
    }

    /** Taille dépackée annoncée par l'en-tête, ou -1 si le fichier n'est pas packé. */
    public static int unpackedLength(byte[] d) {
        return isPacked(d) ? be32(d, 4) : -1;
    }

    /**
     * Dépacke un fichier {@code =SB=}. Un fichier non packé est renvoyé tel quel — c'est le
     * comportement de {@code io_LoadFile}, qui ne branche sur le dépacker que sur le magic.
     */
    public static byte[] unpack(byte[] file) {
        if (!isPacked(file)) {
            return file;
        }
        return decode(file, 12, be32(file, 4), DICBIT);
    }

    private static int be32(byte[] d, int o) {
        return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16
             | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF);
    }

    // ------------------------------------------------------------------ flux de bits

    /** Lecteur de bits, bit de POIDS FORT d'abord (getbits/peekbits de huf.c). */
    private static final class Bits {
        private final byte[] d;
        private int pos;
        private long buf;
        private int n;

        Bits(byte[] d, int off) {
            this.d = d;
            this.pos = off;
        }

        private void fill(int k) {
            while (n < k) {
                int b = pos < d.length ? d[pos] & 0xFF : 0;   // au-delà : des zéros
                pos++;
                buf = (buf << 8) | b;
                n += 8;
            }
        }

        int peek(int k) {
            if (k == 0) {
                return 0;
            }
            fill(k);
            return (int) ((buf >>> (n - k)) & ((1L << k) - 1));
        }

        void skip(int k) {
            fill(k);
            n -= k;
            buf &= (1L << n) - 1;
        }

        int get(int k) {
            int v = peek(k);
            skip(k);
            return v;
        }
    }

    // ------------------------------------------------------------------ arbre de Huffman

    /**
     * Arbre canonique reconstruit depuis les LONGUEURS de code, et décodé bit à bit.
     *
     * <p>C'est tout ce que le flux transporte : la table des longueurs. Les codes s'en déduisent
     * dans l'ordre canonique (longueurs croissantes, et à longueur égale, index de symbole
     * croissant), exactement comme {@code make_table} de huf.c — qui, lui, en fabrique une table
     * de lookup ; ici on parcourt, c'est plus court et assez rapide pour des fichiers de cette
     * taille.
     */
    private static final class Tree {
        private final int[] firstCode;    // premier code de chaque longueur
        private final int[] firstIndex;   // rang, dans symbols, du premier symbole de la longueur
        private final int[] count;        // nombre de symboles par longueur
        private final int[] symbols;      // symboles, triés par (longueur, index)
        private final int maxLen;
        /** Table à un seul symbole (cas {@code n == 0}) : aucun bit consommé. */
        private final int single;

        /** Arbre dégénéré : un seul symbole, aucun bit lu. */
        Tree(int onlySymbol) {
            this.single = onlySymbol;
            this.firstCode = this.firstIndex = this.count = this.symbols = null;
            this.maxLen = 0;
        }

        Tree(int[] lens) {
            this.single = -1;
            int max = 0;
            for (int l : lens) {
                max = Math.max(max, l);
            }
            this.maxLen = max;
            this.count = new int[max + 1];
            for (int l : lens) {
                if (l > 0) {
                    count[l]++;
                }
            }
            this.firstCode = new int[max + 2];
            this.firstIndex = new int[max + 2];
            int code = 0;
            int idx = 0;
            for (int l = 1; l <= max; l++) {
                firstCode[l] = code;
                firstIndex[l] = idx;
                code = (code + count[l]) << 1;
                idx += count[l];
            }
            this.symbols = new int[idx];
            int[] next = new int[max + 2];
            System.arraycopy(firstIndex, 0, next, 0, max + 2);
            for (int s = 0; s < lens.length; s++) {
                int l = lens[s];
                if (l > 0) {
                    symbols[next[l]++] = s;
                }
            }
        }

        int decode(Bits br) {
            if (single >= 0) {
                return single;
            }
            int code = 0;
            for (int l = 1; l <= maxLen; l++) {
                code = (code << 1) | br.get(1);
                if (count[l] > 0 && code - firstCode[l] < count[l]) {
                    return symbols[firstIndex[l] + code - firstCode[l]];
                }
            }
            throw new IllegalStateException("code Huffman invalide");
        }
    }

    // ------------------------------------------------------------------ tables du bloc

    /**
     * {@code read_pt_len} (huf.c) — lit les longueurs de l'arbre « pt ». Renvoie l'arbre ;
     * {@code n == 0} donne un arbre à symbole unique.
     */
    private static Tree readPtLen(Bits br, int nn, int nbit, int iSpecial) {
        int n = br.get(nbit);
        if (n == 0) {
            return new Tree(br.get(nbit));
        }
        int[] lens = new int[nn];
        int i = 0;
        while (i < n) {
            int c = br.peek(3);
            if (c != 7) {
                br.skip(3);
            } else {
                // c == 7 : la longueur se prolonge en unaire, 0 final consommé.
                br.skip(3);
                while (br.get(1) != 0) {
                    c++;
                }
            }
            lens[i++] = c;
            if (i == iSpecial) {
                int z = br.get(2);
                while (z-- > 0 && i < nn) {
                    lens[i++] = 0;
                }
            }
        }
        return new Tree(lens);
    }

    /** {@code read_c_len} (huf.c) — les longueurs de l'arbre des littéraux/longueurs. */
    private static Tree readCLen(Bits br, Tree pt) {
        int n = br.get(CBIT);
        if (n == 0) {
            return new Tree(br.get(CBIT));
        }
        int[] lens = new int[NC];
        int i = 0;
        while (i < n) {
            int c = pt.decode(br);
            if (c <= 2) {                       // 0,1,2 : des séries de longueurs nulles
                if (c == 0) {
                    c = 1;
                } else if (c == 1) {
                    c = br.get(4) + 3;
                } else {
                    c = br.get(CBIT) + 20;
                }
                while (c-- > 0 && i < NC) {
                    lens[i++] = 0;
                }
            } else {
                lens[i++] = c - 2;
            }
        }
        return new Tree(lens);
    }

    // ------------------------------------------------------------------ boucle principale

    /**
     * La boucle de {@code decode} (slide.c) : un tampon CIRCULAIRE de 2^dicbit, pré-rempli
     * d'espaces, dans lequel littéraux et recopies s'écrivent au même endroit. Les recopies
     * peuvent donc pointer « avant le début » du fichier — d'où le pré-remplissage, sans lequel
     * les premiers octets partent en index négatif.
     */
    private static byte[] decode(byte[] src, int off, int outLen, int dicbit) {
        final int dicsiz = 1 << dicbit;
        final int np = dicbit + 1;
        final int pbit = np < 16 ? 4 : 5;       // cmp.w #$10,d1 ; blt ; addq #1,d0

        byte[] text = new byte[dicsiz];
        java.util.Arrays.fill(text, (byte) ' ');
        byte[] out = new byte[outLen];

        Bits br = new Bits(src, off);
        int blocksize = 0;
        Tree ctab = null;
        Tree ptab = null;
        int r = 0;                              // position courante dans le tampon circulaire
        int written = 0;

        while (written < outLen) {
            if (blocksize == 0) {
                blocksize = br.get(16);
                Tree pt = readPtLen(br, NT, TBIT, 3);
                ctab = readCLen(br, pt);
                ptab = readPtLen(br, np, pbit, -1);
            }
            blocksize--;
            int c = ctab.decode(br);
            if (c < 256) {
                text[r] = (byte) c;
                r = (r + 1) & (dicsiz - 1);
                out[written++] = (byte) c;
            } else {
                int len = c - 256 + THRESHOLD;
                int p = ptab.decode(br);
                int dist = p > 1 ? (1 << (p - 1)) + br.get(p - 1) : p;
                int i = (r - dist - 1) & (dicsiz - 1);
                while (len-- > 0 && written < outLen) {
                    byte b = text[i];
                    text[r] = b;
                    r = (r + 1) & (dicsiz - 1);
                    i = (i + 1) & (dicsiz - 1);
                    out[written++] = b;
                }
            }
        }
        return out;
    }
}
