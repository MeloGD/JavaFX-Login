# No JPMS; separation is enforced by Maven modules

The project runs on the classpath with no `module-info.java`. Strong runtime
encapsulation is not worth the cost here: several dependencies are only automatic
modules whose names can shift between releases, and FXML requires each controller
package to be opened explicitly, producing failures that surface late and read
poorly.

Separation between the UI and the authentication logic is instead enforced by the
build: `login-core` has no JavaFX on its classpath, so it cannot import JavaFX
even by accident, and its tests need no display.

## Consequences

- A trimmed runtime is still available. `jlink --add-modules` produces the image
  and `jpackage --runtime-image` consumes it, neither of which requires the
  application itself to be modular.
- Enforcement is at compile time rather than at runtime; reflection can still
  cross module boundaries, which is accepted.
