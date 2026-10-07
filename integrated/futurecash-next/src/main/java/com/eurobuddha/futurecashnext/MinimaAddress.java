package com.eurobuddha.futurecashnext;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;

/**
 * Minima public key -> default address (0x hex and Mx encoding).
 *
 * <p>Port of {@code mds/keyuses/phrase/derive.js:23-48,179-215}, which is itself a validated port
 * of minima-core ({@code Address}, {@code MMR}, {@code MiniData}/{@code MiniNumber} serialization,
 * {@code BaseConverter}). Server-side the same derivation is
 * {@code new Address("RETURN SIGNEDBY(" + publickey + ")")} — see
 * {@code mds/keyuses/backend/KeyUsesServer.java:178} and {@code scanner/CoinScanner.java:77}.
 *
 * <p>FUND-ADJACENT: a one-bit deviation gives a wrong address. Because a default-address script
 * hashes any string, a malformed key silently derives a bogus address, which the audit backend
 * reports as "0 uses" — reading as SAFE. That is why {@link #isValidPublicKey} exists and why the
 * caller cross-checks against the server's own echo. Do not "optimise" the hashing or serialization
 * without re-running {@code MinimaAddressTest}.
 *
 * <p>The app derives locally (rather than trusting the server) for two reasons: {@code keys
 * action:list} does not return addresses (KeyRow.toJSON emits only size/depth/uses/maxuses/
 * modifier/publickey), and having them up front lets the /keyaudit and /reuse calls run in parallel.
 */
public final class MinimaAddress {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Base-32 alphabet used by minima-core's BaseConverter (BigInteger.toString(32)). */
    private static final String B32 = "0123456789abcdefghijklmnopqrstuv";

    private MinimaAddress() {}

    /** A Minima public key: "0x" + exactly 64 hex chars. */
    public static boolean isValidPublicKey(String pk) {
        if (pk == null || pk.length() != 66) return false;
        if (pk.charAt(0) != '0' || (pk.charAt(1) != 'x' && pk.charAt(1) != 'X')) return false;
        for (int i = 2; i < 66; i++) {
            final char c = pk.charAt(i);
            final boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** The 0x/Mx pair for a default address. */
    public static final class Result {
        public final String hex;      // "0x" + 64 upper hex
        public final String mx;       // "Mx…"
        public final String script;   // the RETURN SIGNEDBY(...) script that was hashed

        Result(String hex, String mx, String script) {
            this.hex = hex;
            this.mx = mx;
            this.script = script;
        }
    }

    /**
     * Derive the default address for a public key.
     *
     * @param publicKey "0x"-prefixed 64-hex public key as returned by {@code keys action:list}
     * @return the derived address, or null if the key is not a well-formed public key
     */
    public static Result fromPublicKey(String publicKey) {
        if (!isValidPublicKey(publicKey)) return null;

        // minima-core builds the script from the 0x-prefixed UPPERCASE hex form.
        final String script = "RETURN SIGNEDBY(0x" + publicKey.substring(2).toUpperCase() + ")";

        // Address = single-leaf MMR of the script MiniString:
        //   SHA3( ser(MiniNumber 0) || ser(MiniData script) || ser(MiniNumber 0) )
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        write(buf, serMiniNumberZero());
        write(buf, serMiniData(script.getBytes(UTF8)));
        write(buf, serMiniNumberZero());
        final byte[] addr = Sha3.hash(buf.toByteArray());

        return new Result("0x" + hexUpper(addr), miniAddress(addr), script);
    }

    /**
     * Mx encoding: encode32( 0x01 || uint16BE(len) || addr || SHA3(addr)[0..3] ).
     *
     * <p>{@code BigInteger(1, data).toString(32)} is exactly minima-core's BaseConverter.encode32,
     * including the leading-zero drop that derive.js emulates by hand. The i/l/o remap exists so
     * the alphabet has no characters that read as 1/0.
     */
    static String miniAddress(byte[] addr) {
        final byte[] checksum = Sha3.hash(addr);

        final byte[] data = new byte[3 + addr.length + 4];
        data[0] = 0x01;
        data[1] = (byte) ((addr.length >> 8) & 0xFF);
        data[2] = (byte) (addr.length & 0xFF);
        System.arraycopy(addr, 0, data, 3, addr.length);
        System.arraycopy(checksum, 0, data, 3 + addr.length, 4);

        String digits = new BigInteger(1, data).toString(32);   // 0-9a-v
        // Sanity: toString(32) must only emit the B32 alphabet. Cheap guard against a JDK surprise.
        for (int i = 0; i < digits.length(); i++) {
            if (B32.indexOf(digits.charAt(i)) < 0) {
                throw new IllegalStateException("unexpected base-32 digit: " + digits.charAt(i));
            }
        }
        digits = digits.replace('i', 'w').replace('l', 'y').replace('o', 'z');
        return "Mx" + digits.toUpperCase();
    }

    /* ---------- Minima Streamable serialization (java.io.DataOutputStream semantics) ---------- */

    /** MiniData: writeInt(len) [4-byte big-endian] then the raw bytes. */
    static byte[] serMiniData(byte[] b) {
        final byte[] out = new byte[4 + b.length];
        final int n = b.length;
        out[0] = (byte) ((n >>> 24) & 0xFF);
        out[1] = (byte) ((n >>> 16) & 0xFF);
        out[2] = (byte) ((n >>> 8) & 0xFF);
        out[3] = (byte) (n & 0xFF);
        System.arraycopy(b, 0, out, 4, n);
        return out;
    }

    /**
     * MiniNumber(0): writeByte(scale=0), writeByte(unscaledLen), unscaled bytes.
     * BigInteger.ZERO.toByteArray() is a single 0x00 byte, so this is always {0, 1, 0}.
     */
    static byte[] serMiniNumberZero() {
        return new byte[]{0x00, 0x01, 0x00};
    }

    /* ---------- helpers ---------- */

    static String hexUpper(byte[] b) {
        final char[] D = "0123456789ABCDEF".toCharArray();
        final char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2]     = D[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = D[b[i] & 0xF];
        }
        return new String(out);
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
