package damjay.control.ghosthand.net;

/**
 * One parsed protocol message: header fields plus its payload bytes.
 *
 * <p>Instances are immutable and are handed across threads (socket reader ->
 * decoder thread, encoder thread -> per-client writer threads), which is why every
 * field is {@code final}: safe publication without any locking.
 *
 * <p>The payload byte array itself is <em>not</em> copied - it is treated as
 * read-only by every consumer. Copying 30 payloads a second for no reason would be
 * wasteful on a phone.
 */
public final class Frame {

    public final byte type;
    public final byte flags;
    /** Presentation timestamp in microseconds, as produced by MediaCodec. */
    public final long ptsUs;
    public final byte[] payload;

    public Frame(byte type, byte flags, long ptsUs, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.ptsUs = ptsUs;
        this.payload = payload != null ? payload : new byte[0];
    }

    public boolean isKeyframe() {
        return (flags & GhostProtocol.FLAG_KEYFRAME) != 0;
    }

    public int length() {
        return payload.length;
    }

    /** Payload decoded as a {@link Record} key/value bag (HELLO, WELCOME, STATS...). */
    public Record asRecord() {
        return Record.fromBytes(payload);
    }

    /** Payload decoded as UTF-8 text (BYE reasons). */
    public String asText() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return GhostProtocol.typeName(type) + " len=" + payload.length
                + (isKeyframe() ? " KEY" : "")
                + " ptsUs=" + ptsUs;
    }
}
