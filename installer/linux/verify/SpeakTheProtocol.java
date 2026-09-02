import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.AskWhichProtocolIsSpoken;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.ProtocolSpoken;
import com.javafxlogin.core.ipc.ProtocolVersion;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;

/**
 * A client of the AuthenticationService with no window, for {@code verify-on-a-machine.sh}.
 *
 * <p>Several steps of {@code docs/manual-checks/linux-packaging.md} assert that a deployment
 * survives — the Accounts, the configuration and the SecretVault, byte for byte, across a
 * reinstall and across a removal. That needs a deployment with something in it, and by ADR-0017
 * the package never creates one: only the FirstRunWizard does, and driving a wizard on a machine
 * with no screen is exactly the fragile thing that script is written not to do.
 *
 * <p>So this speaks the protocol instead. It is the same socket the packaged application connects
 * to, the same message catalogue, and the same peer credentials — the kernel names this process the
 * way it names any other, and root is a MachineAdministrator, which is the whole of what ADR-0008
 * asks of whoever runs the bootstrap. What the word "preserved" is worth in that script's report is
 * therefore an Administrator that was really created and really logs in again afterwards, rather
 * than a set of files that happened to still be there.
 *
 * <p>It is compiled by the JDK that built the package and run on the runtime the package ships,
 * over the jars the package ships. Nothing here is part of the product and nothing here is
 * installed: the class is built into a temporary directory and thrown away with the run.
 *
 * <p>Everything it prints goes to standard output, one fact to a line, for a shell to read. An
 * answer that is not a message of the protocol never reaches this far — {@code MessageCodec}
 * refuses it — which is what makes asking anything at all worth doing on a machine whose service
 * unit could be writing its diagnostics into the connection.
 */
public final class SpeakTheProtocol {

  private static final String USAGE =
      "Usage: SpeakTheProtocol <socket> protocol"
          + " | <socket> bootstrap <name> <password>"
          + " | <socket> sessions <name> <password> <count>";

  private SpeakTheProtocol() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException(USAGE);
    }
    Path socket = Path.of(args[0]);
    switch (args[1]) {
      case "protocol" -> whichProtocolIsSpoken(socket);
      case "bootstrap" -> bootstrap(socket, args[2], args[3].toCharArray());
      case "sessions" -> sessions(socket, args[2], args[3].toCharArray(), count(args[4]));
      default -> throw new IllegalArgumentException(USAGE);
    }
  }

  /**
   * Connects, and asks the two questions a client may ask before it holds an Account: which
   * protocol is spoken, and whether the Administrator is still to be created.
   *
   * <p>This is what the script connects with when all it wants is the service started, because it
   * says both answers and leaves nothing behind on the machine.
   */
  private static void whichProtocolIsSpoken(Path socket) throws Exception {
    try (ServiceClient client = ServiceClient.connect(socket)) {
      Response spoken = client.send(new AskWhichProtocolIsSpoken());
      if (!(spoken instanceof ProtocolSpoken(int version))) {
        throw refusal("the handshake with " + spoken);
      }
      System.out.println("protocol " + version);
      System.out.println("this build speaks " + ProtocolVersion.CURRENT);
      System.out.println("bootstrap-needed " + bootstrapNeeded(client));
    }
  }

  /** Creates the single Administrator, which is the only thing that makes a deployment. */
  private static void bootstrap(Path socket, String name, char[] password) throws Exception {
    try (ServiceClient client = ServiceClient.connect(socket)) {
      if (!bootstrapNeeded(client)) {
        throw new IllegalStateException("there is already an Administrator on this machine");
      }
      Response made = client.send(new Bootstrap(name, password));
      System.out.println("bootstrap " + insistOn(made));
    }
  }

  /**
   * Logs in and out again, {@code count} times over one connection, which is what asks whether one
   * process serves every Session in turn rather than one JVM being started per connection.
   */
  private static void sessions(Path socket, String name, char[] password, int count)
      throws Exception {
    try (ServiceClient client = ServiceClient.connect(socket)) {
      for (int session = 1; session <= count; session++) {
        Response admitted = client.send(new Authenticate(name, password, Role.ADMINISTRATOR));
        System.out.println("session " + session + " " + insistOn(admitted));
        SessionToken token = ((Granted) admitted).token();
        Response ended = client.send(new Logout(token));
        System.out.println("session " + session + " ended " + insistOn(ended));
      }
    }
  }

  /** Whether the Administrator is still to be created, refusing any answer that is not that. */
  private static boolean bootstrapNeeded(ServiceClient client) throws Exception {
    Response answer = client.send(new AskIfBootstrapNeeded());
    if (!(answer instanceof BootstrapNeeded(boolean needed))) {
      throw refusal("AskIfBootstrapNeeded with " + answer);
    }
    return needed;
  }

  /**
   * One word for an answer that went through, and an exception for one that did not.
   *
   * <p>A refusal read as a success is a step passing on a machine that is wrong, and every request
   * this class sends is one the machine being verified has to answer with a yes.
   */
  private static String insistOn(Response response) {
    return switch (response) {
      case Granted ignored -> "granted";
      case Ok ignored -> "ok";
      case Denied denied -> throw refusal("denied " + denied.reason());
      case ErrorResponse error -> throw refusal("error " + error.code());
      case PolicyRefused refused -> throw refusal("policy " + refused.violations());
      default -> throw refusal("something nothing here asked for: " + response);
    };
  }

  private static int count(String argument) {
    int count = Integer.parseInt(argument);
    if (count < 1) {
      throw new IllegalArgumentException("a run of " + count + " Sessions asks nothing");
    }
    return count;
  }

  private static IllegalStateException refusal(String what) {
    return new IllegalStateException("the service answered " + what);
  }
}
