package com.javafxlogin.core.ipc;

import java.io.IOException;
import java.net.BindException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * The one exchange a client makes before it draws anything: is the AuthenticationService there, may
 * this account reach it, and does it speak this build's protocol.
 *
 * <p>It is a connection of its own, opened and closed here, rather than the one a Session will later
 * live on. The two want opposite things from a clock — this one has to give up quickly, so that a
 * person is told what is wrong instead of watching nothing happen, while a Session's connection
 * carries an Argon2id verification and a whole Backup and must not be given up on at all — so they
 * are not the same connection and this does not go through {@link TransportClient}.
 *
 * <p>Every wait here is against one deadline covering the whole exchange, so the answer arrives
 * within {@code patience} however the failure is arranged: a service wedged before its first read
 * costs the same as one that never started. The channel is non-blocking and driven by a selector
 * rather than by a second thread with a stopwatch, so nothing is left running behind an answer that
 * has already been given.
 *
 * <p>Blocks for up to {@code patience}. The caller keeps it off whichever thread draws its windows.
 */
public final class ServiceHandshake {

  /**
   * How long the whole exchange is given.
   *
   * <p>Generous against the measurement it has to cover: the Linux spike activated the service and
   * completed a first round trip in 179 ms including JVM start, so five seconds is some thirty times
   * the cost of the slowest case that is supposed to succeed. It is set where a machine under load
   * still gets in, because giving up early would tell somebody their service is not running when it
   * was merely starting.
   */
  public static final Duration PATIENCE = Duration.ofSeconds(5);

  private static final int READ_BUFFER_BYTES = 4 * 1024;

  private ServiceHandshake() {}

  /** As {@link #attemptedAt(Path, Duration)}, with the patience this product ships. */
  public static ServiceReachability attemptedAt(Path socketPath) {
    return attemptedAt(socketPath, PATIENCE);
  }

  /**
   * Connects to {@code socketPath}, asks which protocol is spoken there, and says what it found.
   *
   * @param patience the whole exchange's budget, the connection included
   * @return {@link Reachable}, or an {@link Unreachable} naming which of three things happened
   */
  public static ServiceReachability attemptedAt(Path socketPath, Duration patience) {
    long deadline = System.nanoTime() + patience.toNanos();
    try (Selector selector = Selector.open();
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.configureBlocking(false);
      SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
      connect(channel, key, socketPath, deadline);
      send(channel, key, deadline);
      return whatCameBack(receive(channel, key, deadline));
    } catch (MalformedFrameException | MalformedMessageException e) {
      // Something is on the socket and it is not framing, or not wording, the way ADR-0003 says to.
      // That is the two halves of this product disagreeing about the wire, which is the same fact
      // as disagreeing about the catalogue and is worth telling a person the same way.
      return new Unreachable(ServiceUnreachableReason.INCOMPATIBLE_VERSION);
    } catch (ClosedByInterruptException e) {
      // Somebody is stopping this thread and the channel closed under it. Nothing was learnt about
      // the service, so the flag goes back rather than being swallowed into a diagnosis: whoever
      // interrupted is owed the interrupt, not a sentence about a service that may be fine.
      Thread.currentThread().interrupt();
      return new Unreachable(ServiceUnreachableReason.NOT_RUNNING);
    } catch (BindException e) {
      // The JDK reports a refused AF_UNIX connect this way when the kernel said EACCES, which is
      // the group-membership case and the one of the three a person can usually fix themselves.
      // The type is what is matched on and never the message, which arrives in the language of
      // whichever machine this happens to be.
      return new Unreachable(ServiceUnreachableReason.SOCKET_NOT_ACCESSIBLE);
    } catch (IOException e) {
      // Everything else the operating system can say about this socket: no such path, nothing
      // listening on it, the far side gone or silent. All of them mean the service could not be
      // started, which is what NOT_RUNNING says.
      return new Unreachable(ServiceUnreachableReason.NOT_RUNNING);
    }
  }

  /**
   * Establishes the connection, or gives up on the deadline.
   *
   * <p>An {@code AF_UNIX} connect completes at once — the kernel puts it in the listening socket's
   * backlog whether or not anything has come up behind it yet, which is the whole of why socket
   * activation has no cold-start race. The wait below is therefore never taken on Linux, and is
   * here so that a platform whose connect is asynchronous is not silently waited on forever.
   */
  private static void connect(
      SocketChannel channel, SelectionKey key, Path socketPath, long deadline) throws IOException {
    if (channel.connect(UnixDomainSocketAddress.of(socketPath))) {
      return;
    }
    while (!channel.finishConnect()) {
      if (!stillHasTimeAfterWaiting(key, deadline)) {
        throw new IOException("The AuthenticationService did not accept the connection in time");
      }
    }
  }

  /**
   * Sends the frozen question, or gives up on the deadline mid-write.
   *
   * <p>The question is a few dozen bytes and a socket that has just been accepted takes it in one
   * write. It is written in a loop against the deadline anyway, because "takes it in one write" is
   * a fact about a healthy peer and this method exists for the other kind.
   */
  private static void send(SocketChannel channel, SelectionKey key, long deadline)
      throws IOException {
    ByteBuffer question =
        ByteBuffer.wrap(FrameCodec.encode(MessageCodec.encode(new AskWhichProtocolIsSpoken())));
    key.interestOps(SelectionKey.OP_WRITE);
    while (question.hasRemaining()) {
      if (channel.write(question) == 0 && !stillHasTimeAfterWaiting(key, deadline)) {
        throw new IOException("The AuthenticationService did not take the question in time");
      }
    }
  }

  /**
   * Reads until one whole frame has arrived, the far side gives up, or the deadline runs out.
   *
   * <p>A deadline that runs out is an {@link IOException} rather than an outcome of its own: under
   * socket activation the connection is accepted whether or not anything ever comes up behind it,
   * so silence is precisely how a service that failed to start presents itself from out here, and
   * it is told as such.
   */
  private static byte[] receive(SocketChannel channel, SelectionKey key, long deadline)
      throws IOException {
    FrameDecoder frames = new FrameDecoder();
    ByteBuffer read = ByteBuffer.allocate(READ_BUFFER_BYTES);
    key.interestOps(SelectionKey.OP_READ);
    while (true) {
      Optional<byte[]> answer = frames.next();
      if (answer.isPresent()) {
        return answer.get();
      }
      read.clear();
      if (channel.read(read) == -1) {
        throw new IOException("The AuthenticationService closed the connection without answering");
      }
      read.flip();
      if (read.hasRemaining()) {
        frames.append(read);
      } else if (!stillHasTimeAfterWaiting(key, deadline)) {
        throw new IOException("The AuthenticationService did not answer in time");
      }
    }
  }

  /**
   * Waits for the channel to become ready for whatever it is currently interested in, and answers
   * the only question every caller is actually asking: is there any of the deadline left to try
   * again with.
   *
   * <p>It deliberately does not report <em>why</em> the wait ended. A spurious wakeup costs one more
   * poll of a non-blocking channel, which comes back with nothing and lands here again; treating
   * readiness as the answer would mean deciding, in this method, what each of three different
   * callers should do about not having been woken for their own reason.
   *
   * <p>The selected keys are drained each time round, because a selector that is not drained reports
   * the readiness it already reported and turns the wait into a spin. That has to stay true of every
   * path into {@code select} below, not merely of the one written first.
   */
  private static boolean stillHasTimeAfterWaiting(SelectionKey key, long deadline)
      throws IOException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      return false;
    }
    Selector selector = key.selector();
    selector.selectedKeys().clear();
    // At least a millisecond, because select(0) means "wait until something happens" and would
    // outlive the deadline it was handed the last sliver of.
    selector.select(Math.max(1, Duration.ofNanos(remaining).toMillis()));
    return System.nanoTime() < deadline;
  }

  /**
   * What the far side sent, read as this build's catalogue.
   *
   * <p>Anything that is not the frozen answer, naming this build's own number, means the two halves
   * do not agree about what the messages are. A readable response of some other type counts: that
   * is what a later catalogue which reused this exchange for something else would look like from
   * here, and it is no more understood for having parsed.
   */
  private static ServiceReachability whatCameBack(byte[] answer) {
    if (MessageCodec.decodeResponse(answer) instanceof ProtocolSpoken spoken
        && spoken.version() == ProtocolVersion.CURRENT) {
      return new Reachable();
    }
    return new Unreachable(ServiceUnreachableReason.INCOMPATIBLE_VERSION);
  }
}
