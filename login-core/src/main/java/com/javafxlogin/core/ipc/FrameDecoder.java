package com.javafxlogin.core.ipc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/**
 * Turns the byte stream of a connection back into frames.
 *
 * <p>A stream gives no guarantee about where reads fall: one frame may arrive in
 * several reads, and several frames may arrive in one. Bytes are therefore handed
 * to {@link #append} as they turn up and frames are drained with {@link #next}
 * until it comes back empty.
 *
 * <p>Memory held is driven by bytes that actually arrived, never by the length a
 * peer declared. An over-cap declaration is refused the moment its prefix is
 * complete, so nothing is ever read <em>in order to</em> reach the body behind it —
 * only whatever happened to arrive in the same read, bounded by the caller's read
 * buffer, is held, and it is discarded with the connection.
 *
 * <p>Not thread-safe: one decoder belongs to one connection, read by one thread.
 */
public final class FrameDecoder {

  private static final int INITIAL_CAPACITY = 8 * 1024;
  private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

  private final int maxFrameBytes;

  private byte[] buffer = new byte[INITIAL_CAPACITY];
  private int start;
  private int end;

  public FrameDecoder() {
    this(FrameCodec.MAX_FRAME_BYTES);
  }

  FrameDecoder(int maxFrameBytes) {
    this.maxFrameBytes = maxFrameBytes;
  }

  /**
   * Adds bytes just read from the connection.
   *
   * <p>Drain with {@link #next} after appending. What is held stays bounded by one
   * frame plus one read only if the frames that completed are taken out.
   */
  public void append(byte[] bytes) {
    append(bytes, 0, bytes.length);
  }

  /** Adds {@code length} bytes just read from the connection. */
  public void append(byte[] bytes, int offset, int length) {
    if (length == 0) {
      return;
    }
    makeRoomFor(length);
    System.arraycopy(bytes, offset, buffer, end, length);
    end += length;
  }

  /** Adds the buffer's remaining bytes, consuming them. */
  public void append(ByteBuffer bytes) {
    int length = bytes.remaining();
    if (length == 0) {
      return;
    }
    makeRoomFor(length);
    bytes.get(buffer, end, length);
    end += length;
  }

  /**
   * The next complete frame's payload, or empty when more bytes are needed.
   *
   * @throws FrameTooLargeException if the declared length is above the cap, raised
   *     as soon as the prefix is complete and before any body is read
   * @throws MalformedFrameException if the declared length cannot be a frame at all
   */
  public Optional<byte[]> next() throws MalformedFrameException {
    int held = end - start;
    if (held < FrameCodec.LENGTH_PREFIX_BYTES) {
      return Optional.empty();
    }

    int declaredLength = declaredLength();
    if (declaredLength <= 0) {
      throw new MalformedFrameException(
          "Frame declares a length of " + declaredLength + ", which is not a frame");
    }
    if (declaredLength > maxFrameBytes) {
      throw new FrameTooLargeException(declaredLength, maxFrameBytes);
    }
    if (held - FrameCodec.LENGTH_PREFIX_BYTES < declaredLength) {
      return Optional.empty();
    }

    int payloadStart = start + FrameCodec.LENGTH_PREFIX_BYTES;
    byte[] payload = Arrays.copyOfRange(buffer, payloadStart, payloadStart + declaredLength);
    start = payloadStart + declaredLength;
    if (start == end) {
      start = 0;
      end = 0;
    }
    return Optional.of(payload);
  }

  /**
   * Bytes currently allocated.
   *
   * <p>Exists so a test can pin the memory a peer's declaration is allowed to cost.
   * That is a resource promise rather than a detail of how decoding is arranged.
   */
  int bufferCapacity() {
    return buffer.length;
  }

  private int declaredLength() {
    return ((buffer[start] & 0xFF) << 24)
        | ((buffer[start + 1] & 0xFF) << 16)
        | ((buffer[start + 2] & 0xFF) << 8)
        | (buffer[start + 3] & 0xFF);
  }

  private void makeRoomFor(int incoming) {
    if ((long) end + incoming <= buffer.length) {
      return;
    }
    int held = end - start;
    long needed = (long) held + incoming;
    if (needed <= buffer.length) {
      System.arraycopy(buffer, start, buffer, 0, held);
    } else {
      // Long arithmetic throughout: the caller is holding bytes a peer chose the
      // size of, and a doubling that silently wrapped would be a defect worth more
      // than the casts it costs to avoid. The copy is written out rather than done
      // with copyOfRange, whose second index would be start + capacity — the one
      // sum that could still overflow back into a negative index.
      long capacity = buffer.length;
      while (capacity < needed) {
        capacity *= 2;
      }
      if (capacity > MAX_CAPACITY) {
        throw new IllegalStateException(
            "Refusing to buffer " + needed + " bytes; drain frames before appending more");
      }
      byte[] grown = new byte[(int) capacity];
      System.arraycopy(buffer, start, grown, 0, held);
      buffer = grown;
    }
    start = 0;
    end = held;
  }
}
