package damjay.control.ghosthand.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny typed key/value bag used for every non-video payload (HELLO, WELCOME,
 * GEOMETRY, STATS, TOUCH...).
 *
 * <p>Why not JSON? {@code org.json} exists on the device, but on a plain JVM unit
 * test it resolves to a stub that throws, which makes the protocol untestable
 * without Robolectric. This encoder is ~80 lines, allocates nothing fancy, and can
 * be round-tripped in a JUnit test on a laptop and in CI.
 *
 * <pre>
 *   record := ( keyLen:u8 key:utf8 type:u8 valueLen:u32 value:bytes )*
 *   type   := 0 int64 (8 bytes, big endian)
 *             1 utf8 string
 *             2 raw bytes   (H.264 SPS/PPS, opaque blobs)
 *             3 nested record
 * </pre>
 *
 * Insertion order is preserved (LinkedHashMap) so the bytes on the wire are stable,
 * which makes two builds byte-comparable when debugging.
 */
public final class Record {

    public static final byte VAL_INT = 0;
    public static final byte VAL_STRING = 1;
    public static final byte VAL_BYTES = 2;
    public static final byte VAL_RECORD = 3;

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();

    public static Record create() {
        return new Record();
    }

    // ------------------------------- writers --------------------------------

    public Record putInt(String key, long value) {
        return put(key, VAL_INT, Long.valueOf(value));
    }

    public Record putString(String key, String value) {
        return put(key, VAL_STRING, value == null ? "" : value);
    }

    public Record putBytes(String key, byte[] value) {
        return put(key, VAL_BYTES, value == null ? new byte[0] : value);
    }

    public Record putRecord(String key, Record value) {
        return put(key, VAL_RECORD, value == null ? Record.create() : value);
    }

    private Record put(String key, byte type, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("null key");
        }
        if (!values.containsKey(key)) {
            order.add(key);
        }
        values.put(key, value);
        // remember the type alongside the value by tagging ints/strings/bytes/records
        types.put(key, Byte.valueOf(type));
        return this;
    }

    private final Map<String, Byte> types = new LinkedHashMap<>();

    // ------------------------------- readers --------------------------------

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public long getInt(String key, long fallback) {
        Object v = values.get(key);
        return (v instanceof Long) ? ((Long) v).longValue() : fallback;
    }

    public String getString(String key, String fallback) {
        Object v = values.get(key);
        return (v instanceof String) ? (String) v : fallback;
    }

    public byte[] getBytes(String key) {
        Object v = values.get(key);
        return (v instanceof byte[]) ? (byte[]) v : new byte[0];
    }

    public Record getRecord(String key) {
        Object v = values.get(key);
        return (v instanceof Record) ? (Record) v : Record.create();
    }

    public int size() {
        return order.size();
    }

    // --------------------------- (de)serialisation ---------------------------

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        for (String key : order) {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length > 255) {
                throw new IllegalArgumentException("key too long: " + key);
            }
            byte type = types.get(key).byteValue();
            byte[] value;
            Object v = values.get(key);
            switch (type) {
                case VAL_INT:
                    value = new byte[8];
                    long n = ((Long) v).longValue();
                    for (int i = 7; i >= 0; i--) {
                        value[i] = (byte) (n & 0xFF);
                        n >>>= 8;
                    }
                    break;
                case VAL_STRING:
                    value = ((String) v).getBytes(StandardCharsets.UTF_8);
                    break;
                case VAL_BYTES:
                    value = (byte[]) v;
                    break;
                case VAL_RECORD:
                    value = ((Record) v).toBytes();
                    break;
                default:
                    throw new IllegalStateException("unknown type " + type);
            }
            out.write(keyBytes.length);
            out.write(keyBytes, 0, keyBytes.length);
            out.write(type);
            writeInt(out, value.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    /**
     * Parses a record. Malformed input never throws out of here - it stops at the
     * first bad field and returns what was decoded so far, because a truncated frame
     * on a flaky WiFi link must not crash the guest.
     */
    public static Record fromBytes(byte[] data) {
        Record r = Record.create();
        if (data == null) {
            return r;
        }
        int i = 0;
        while (i < data.length) {
            int keyLen = data[i] & 0xFF;
            i += 1;
            if (keyLen == 0 || i + keyLen > data.length) {
                break;
            }
            String key = new String(data, i, keyLen, StandardCharsets.UTF_8);
            i += keyLen;
            if (i + 5 > data.length) {
                break;
            }
            byte type = data[i];
            i += 1;
            int valueLen = readInt(data, i);
            i += 4;
            if (valueLen < 0 || i + valueLen > data.length) {
                break;
            }
            switch (type) {
                case VAL_INT:
                    if (valueLen == 8) {
                        r.putInt(key, readLong(data, i));
                    }
                    break;
                case VAL_STRING:
                    r.putString(key, new String(data, i, valueLen, StandardCharsets.UTF_8));
                    break;
                case VAL_BYTES: {
                    byte[] copy = new byte[valueLen];
                    System.arraycopy(data, i, copy, 0, valueLen);
                    r.putBytes(key, copy);
                    break;
                }
                case VAL_RECORD: {
                    byte[] nested = new byte[valueLen];
                    System.arraycopy(data, i, nested, 0, valueLen);
                    r.putRecord(key, Record.fromBytes(nested));
                    break;
                }
                default:
                    // Unknown type from a newer peer: skip the value and keep going.
                    break;
            }
            i += valueLen;
        }
        return r;
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readInt(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24)
                | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8)
                | (d[off + 3] & 0xFF);
    }

    private static long readLong(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[off + i] & 0xFFL);
        }
        return v;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : order) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            Object v = values.get(key);
            sb.append(key).append('=');
            if (v instanceof byte[]) {
                sb.append("bytes[").append(((byte[]) v).length).append(']');
            } else {
                sb.append(v);
            }
        }
        return sb.append('}').toString();
    }
}
