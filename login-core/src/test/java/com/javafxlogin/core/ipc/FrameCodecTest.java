package com.javafxlogin.core.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The framing rules of ADR-0003, exercised without a socket: a four-byte length
 * prefix, a hard 1 MiB cap, and a decoder that must survive the arbitrary way a
 * stream chops the bytes up.
 */
class FrameCodecTest {

  private static byte[] payload(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void encodesTheLengthAsAFourByteBigEndianPrefix() {
    byte[] frame = FrameCodec.encode(payload("{}"));

    assertEquals(6, frame.length);
    assertArrayEquals(new byte[] {0, 0, 0, 2, '{', '}'}, frame);
  }

  @Test
  void decodesAFrameItEncoded() throws Exception {
    byte[] request = payload("{\"type\":\"Authenticate\"}");

    FrameDecoder decoder = new FrameDecoder();
    decoder.append(FrameCodec.encode(request));

    assertArrayEquals(request, decoder.next().orElseThrow());
    assertEquals(Optional.empty(), decoder.next());
  }

  @Test
  void reassemblesAFrameSplitAcrossSeveralReads() throws Exception {
    byte[] request = payload("{\"type\":\"Touch\"}");
    byte[] frame = FrameCodec.encode(request);

    FrameDecoder decoder = new FrameDecoder();
    for (int i = 0; i < frame.length - 1; i++) {
      decoder.append(new byte[] {frame[i]});
      assertEquals(Optional.empty(), decoder.next(), "frame completed early at byte " + i);
    }
    decoder.append(new byte[] {frame[frame.length - 1]});

    assertArrayEquals(request, decoder.next().orElseThrow());
  }

  @Test
  void separatesSeveralFramesArrivingInASingleRead() throws Exception {
    byte[] first = payload("one");
    byte[] second = payload("two");
    byte[] third = payload("three");

    FrameDecoder decoder = new FrameDecoder();
    decoder.append(concat(FrameCodec.encode(first), FrameCodec.encode(second),
        FrameCodec.encode(third)));

    assertArrayEquals(first, decoder.next().orElseThrow());
    assertArrayEquals(second, decoder.next().orElseThrow());
    assertArrayEquals(third, decoder.next().orElseThrow());
    assertEquals(Optional.empty(), decoder.next());
  }

  @Test
  void separatesFramesWhenAReadStraddlesTheBoundaryBetweenThem() throws Exception {
    byte[] first = payload("first");
    byte[] second = payload("second");
    byte[] wire = concat(FrameCodec.encode(first), FrameCodec.encode(second));
    int straddle = FrameCodec.LENGTH_PREFIX_BYTES + first.length + 2;

    FrameDecoder decoder = new FrameDecoder();
    decoder.append(wire, 0, straddle);

    assertArrayEquals(first, decoder.next().orElseThrow());
    assertEquals(Optional.empty(), decoder.next());

    decoder.append(wire, straddle, wire.length - straddle);

    assertArrayEquals(second, decoder.next().orElseThrow());
  }

  @Test
  void rejectsADeclaredLengthAboveTheCapAsSoonAsThePrefixArrives() {
    FrameDecoder decoder = new FrameDecoder();
    decoder.append(lengthPrefix(FrameCodec.MAX_FRAME_BYTES + 1));

    assertThrows(FrameTooLargeException.class, decoder::next);
  }

  @Test
  void acceptsAFrameExactlyAtTheCap() throws Exception {
    byte[] request = new byte[FrameCodec.MAX_FRAME_BYTES];

    FrameDecoder decoder = new FrameDecoder();
    decoder.append(FrameCodec.encode(request));

    assertEquals(FrameCodec.MAX_FRAME_BYTES, decoder.next().orElseThrow().length);
  }

  @Test
  void doesNotAllocateForADeclaredLengthUntilThatManyBytesActuallyArrive() throws Exception {
    FrameDecoder decoder = new FrameDecoder();
    decoder.append(lengthPrefix(FrameCodec.MAX_FRAME_BYTES));

    assertEquals(Optional.empty(), decoder.next());
    assertTrue(decoder.bufferCapacity() < FrameCodec.MAX_FRAME_BYTES,
        "a declared length must not drive an allocation before its body arrives");
  }

  @Test
  void rejectsADeclaredLengthOfZero() {
    FrameDecoder decoder = new FrameDecoder();
    decoder.append(lengthPrefix(0));

    assertThrows(MalformedFrameException.class, decoder::next);
  }

  @Test
  void rejectsADeclaredLengthWhoseHighBitIsSet() {
    FrameDecoder decoder = new FrameDecoder();
    decoder.append(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

    assertThrows(MalformedFrameException.class, decoder::next);
  }

  @Test
  void reportsAPartialFrameSoATruncatedStreamIsDetectableAtEndOfInput() throws Exception {
    byte[] frame = FrameCodec.encode(payload("truncated"));

    FrameDecoder decoder = new FrameDecoder();
    assertFalse(decoder.hasPartialFrame());

    decoder.append(frame, 0, frame.length - 1);
    assertEquals(Optional.empty(), decoder.next());
    assertTrue(decoder.hasPartialFrame(), "a body that stopped short must be visible as partial");

    decoder.append(frame, frame.length - 1, 1);
    decoder.next().orElseThrow();
    assertFalse(decoder.hasPartialFrame());
  }

  @Test
  void reportsAPartialFrameWhenEvenThePrefixIsIncomplete() throws Exception {
    FrameDecoder decoder = new FrameDecoder();
    decoder.append(new byte[] {0, 0});

    assertEquals(Optional.empty(), decoder.next());
    assertTrue(decoder.hasPartialFrame());
  }

  @Test
  void refusesToEncodeAPayloadAboveTheCap() {
    byte[] oversized = new byte[FrameCodec.MAX_FRAME_BYTES + 1];

    assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(oversized));
  }

  @Test
  void refusesToEncodeAnEmptyPayload() {
    assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(new byte[0]));
  }

  private static byte[] lengthPrefix(int declaredLength) {
    return new byte[] {
      (byte) (declaredLength >>> 24),
      (byte) (declaredLength >>> 16),
      (byte) (declaredLength >>> 8),
      (byte) declaredLength
    };
  }

  private static byte[] concat(byte[]... parts) {
    int total = 0;
    for (byte[] part : parts) {
      total += part.length;
    }
    byte[] joined = new byte[total];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, joined, offset, part.length);
      offset += part.length;
    }
    return joined;
  }
}
