package com.eurobuddha.futurecashnext;

/**
 * SHA3-256 (FIPS-202) — self-contained, no platform dependency.
 *
 * <p>FUND-ADJACENT. This feeds {@link MinimaAddress}; a one-bit deviation gives a wrong address,
 * which would silently mis-audit a key. Do not "optimise" it without re-running {@code Sha3Test}.
 *
 * <p>Why not {@code MessageDigest.getInstance("SHA3-256")}: SHA3 only reached the Android platform
 * providers in API 29, and this app's minSdk is 28. Bundling it also makes the digest bit-identical
 * on every device and every OEM provider, which is what a derivation this sensitive needs.
 *
 * <p>Note this is SHA3 (FIPS-202, domain padding {@code 0x06}), NOT the pre-standard Keccak
 * padding {@code 0x01} used by Ethereum. Minima uses SHA3.
 */
public final class Sha3 {

    private static final int RATE_BYTES = 136;   // 1600 - 2*256 bits, in bytes
    private static final int OUT_BYTES = 32;
    private static final int ROUNDS = 24;

    /** Round constants for the iota step. */
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    /** Lane visit order for the combined rho+pi step. */
    private static final int[] PI = {
            10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
            15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    };

    /** Rotation offsets matching {@link #PI}. */
    private static final int[] RHO = {
            1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
            27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    };

    private Sha3() {}

    /** SHA3-256 of the whole input. */
    public static byte[] hash(byte[] in) {
        final long[] st = new long[25];

        int off = 0;
        final int full = in.length / RATE_BYTES;
        for (int b = 0; b < full; b++) {
            absorb(st, in, off);
            off += RATE_BYTES;
        }

        // pad10*1 with the SHA3 domain separator 0x06
        final byte[] last = new byte[RATE_BYTES];
        final int rem = in.length - off;
        System.arraycopy(in, off, last, 0, rem);
        last[rem] = 0x06;
        last[RATE_BYTES - 1] |= (byte) 0x80;
        absorb(st, last, 0);

        // squeeze: 32 bytes < rate, so one pass, little-endian lanes
        final byte[] out = new byte[OUT_BYTES];
        for (int i = 0; i < OUT_BYTES; i++) {
            out[i] = (byte) (st[i >>> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    /** XOR one rate-sized block (little-endian lanes) into the state, then permute. */
    private static void absorb(long[] st, byte[] src, int off) {
        for (int i = 0; i < RATE_BYTES / 8; i++) {
            long lane = 0;
            for (int j = 7; j >= 0; j--) {
                lane = (lane << 8) | (src[off + i * 8 + j] & 0xFFL);
            }
            st[i] ^= lane;
        }
        keccakf(st);
    }

    /** Keccak-f[1600]. */
    private static void keccakf(long[] a) {
        final long[] c = new long[5];

        for (int round = 0; round < ROUNDS; round++) {

            // theta
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                final long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) {
                    a[x + y] ^= d;
                }
            }

            // rho + pi
            long t = a[1];
            for (int i = 0; i < 24; i++) {
                final int j = PI[i];
                final long tmp = a[j];
                a[j] = Long.rotateLeft(t, RHO[i]);
                t = tmp;
            }

            // chi
            for (int y = 0; y < 25; y += 5) {
                final long a0 = a[y], a1 = a[y + 1], a2 = a[y + 2], a3 = a[y + 3], a4 = a[y + 4];
                a[y]     = a0 ^ (~a1 & a2);
                a[y + 1] = a1 ^ (~a2 & a3);
                a[y + 2] = a2 ^ (~a3 & a4);
                a[y + 3] = a3 ^ (~a4 & a0);
                a[y + 4] = a4 ^ (~a0 & a1);
            }

            // iota
            a[0] ^= RC[round];
        }
    }

    /**
     * Known-answer self-test on the two canonical FIPS-202 vectors.
     *
     * <p>Called once at startup: if this implementation were ever miscompiled or mangled, every
     * address the app derives would be wrong, and a wrong address reads as "0 uses" — i.e. it would
     * report SAFE. Failing loudly is the only acceptable behaviour.
     *
     * @return true if both vectors match
     */
    public static boolean selfTest() {
        final String empty = "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a";
        final String abc   = "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532";
        return empty.equals(hex(hash(new byte[0])))
                && abc.equals(hex(hash(new byte[]{'a', 'b', 'c'})));
    }

    /** Lowercase hex, no prefix. */
    static String hex(byte[] b) {
        final char[] D = "0123456789abcdef".toCharArray();
        final char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2]     = D[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = D[b[i] & 0xF];
        }
        return new String(out);
    }
}
