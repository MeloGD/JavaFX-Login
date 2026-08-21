package com.javafxlogin.core.ipc;

/**
 * Which version of the message catalogue a build speaks.
 *
 * <p>The catalogue in {@link MessageCodec} is a closed set that grows one ticket at a time, and a
 * client built against one version of it talking to a service built against another is not a thing
 * either of them can guess its way through. ADR-0003 already refuses to guess at a frame; this is
 * the same rule applied to the whole conversation, and it is why the number exists rather than
 * being inferred from whichever message first failed to parse.
 *
 * <p><b>Two things here are frozen and must stay frozen.</b> {@link AskWhichProtocolIsSpoken} and
 * {@link ProtocolSpoken} are the only messages every version of this product is required to read and
 * write, in exactly the shape they have now. A version that changed either of them would take away
 * the one exchange that can tell two disagreeing builds apart, and the disagreement would arrive as
 * a parse failure again — which is the failure this ticket exists to stop.
 *
 * <p>Raise {@link #CURRENT} in the same commit that changes what any other message means on the
 * wire. Adding a message nobody sends yet does not change what an older build reads, so it does not
 * need a raise; changing a field's name, its type or its meaning does.
 */
public final class ProtocolVersion {

  /**
   * What this build speaks. One: the catalogue has grown by addition only since the wire was
   * settled, so nothing shipped has ever had to read a different number.
   */
  public static final int CURRENT = 1;

  private ProtocolVersion() {}
}
