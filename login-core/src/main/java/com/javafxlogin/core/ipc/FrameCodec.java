package com.javafxlogin.core.ipc;

/**
 * The wire format shared by both platforms: a four-byte big-endian length prefix
 * followed by that many bytes of payload, with a hard cap per ADR-0003.
 *
 * <p>The cap is what stops a peer from making the privileged
 * {@code AuthenticationService} buffer an arbitrary amount of memory on its say-so.
 * A declaration above it is refused before any body is read.
 */
public final class FrameCodec {

  /** Bytes of big-endian length that precede every payload. */
  public static final int LENGTH_PREFIX_BYTES = 4;

  /** The hard cap of ADR-0003: no frame carries more than 1 MiB of payload. */
  public static final int MAX_FRAME_BYTES = 1024 * 1024;

  private FrameCodec() {}

  /**
   * Wraps a payload in its length prefix.
   *
   * @throws IllegalArgumentException if the payload is empty or above {@link #MAX_FRAME_BYTES};
   *     a local caller sending an impossible frame is a defect here, not a wire event
   */
  public static byte[] encode(byte[] payload) {
    if (payload.length == 0) {
      throw new IllegalArgumentException("A frame carries at least one byte of payload");
    }
    if (payload.length > MAX_FRAME_BYTES) {
      throw new IllegalArgumentException(
          "Payload of " + payload.length + " bytes exceeds the " + MAX_FRAME_BYTES + " byte cap");
    }
    byte[] frame = new byte[LENGTH_PREFIX_BYTES + payload.length];
    writeLengthPrefix(frame, payload.length);
    System.arraycopy(payload, 0, frame, LENGTH_PREFIX_BYTES, payload.length);
    return frame;
  }

  private static void writeLengthPrefix(byte[] target, int length) {
    target[0] = (byte) (length >>> 24);
    target[1] = (byte) (length >>> 16);
    target[2] = (byte) (length >>> 8);
    target[3] = (byte) length;
  }
}
