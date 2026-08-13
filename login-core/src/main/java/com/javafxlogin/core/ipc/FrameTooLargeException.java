package com.javafxlogin.core.ipc;

/**
 * A frame declared more payload than {@link FrameCodec#MAX_FRAME_BYTES} allows.
 *
 * <p>Distinct from its parent because of when it is raised: the declaration alone
 * is enough to refuse the frame, so the body is never read and never buffered.
 */
public final class FrameTooLargeException extends MalformedFrameException {

  private static final long serialVersionUID = 1L;

  private final int declaredLength;

  public FrameTooLargeException(int declaredLength) {
    super("Frame declares " + declaredLength + " bytes, above the "
        + FrameCodec.MAX_FRAME_BYTES + " byte cap; its body was not read");
    this.declaredLength = declaredLength;
  }

  /** The length the peer declared, for the audit trail. The body behind it was never read. */
  public int declaredLength() {
    return declaredLength;
  }
}
