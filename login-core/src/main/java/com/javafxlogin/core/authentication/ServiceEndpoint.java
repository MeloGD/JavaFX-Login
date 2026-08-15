package com.javafxlogin.core.authentication;

import com.javafxlogin.core.ipc.ConnectionHandle;
import com.javafxlogin.core.ipc.MalformedMessageException;
import com.javafxlogin.core.ipc.MessageCodec;
import com.javafxlogin.core.ipc.RequestHandler;
import java.util.Objects;

/**
 * The AuthenticationService as the transport sees it: frames in, frames out.
 *
 * <p>It exists so that neither half has to know the other's shape. The transport keeps promising
 * whole, capped, ordered payloads and nothing about what they mean; the service keeps answering
 * request objects and nothing about how they arrived, which is what lets Seam 1 test every rule it
 * enforces without a socket.
 *
 * <p>A payload that is not a message this build reads is not answered at all: the {@link
 * MalformedMessageException} reaches the transport, which drops the connection. That is ADR-0003's
 * rule one layer up — a frame is never guessed at, and the peer of a privileged process does not
 * get the benefit of the doubt.
 */
public final class ServiceEndpoint implements RequestHandler {

  private final AuthenticationService service;

  public ServiceEndpoint(AuthenticationService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * The connection is handed on whole rather than read here. The service asks it who the operating
   * system says the peer is, which is what the first-run wizard's second guard rests on, and will
   * later bind a Session to it and end that Session when it closes.
   */
  @Override
  public byte[] handle(byte[] request, ConnectionHandle connection) {
    return MessageCodec.encode(service.handle(MessageCodec.decodeRequest(request), connection));
  }
}
