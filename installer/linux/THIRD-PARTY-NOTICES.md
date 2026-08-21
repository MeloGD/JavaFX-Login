# Third-party notices

This product is redistributed as a `.deb` that carries other people's software inside it:
a Java runtime linked from OpenJDK, the OpenJFX toolkit the windows are drawn with, and
the libraries listed below. Their licences are not a formality — two of them are the GNU
General Public License, and the notice below is the condition on which this package may be
handed to anybody at all.

Shipping this file is therefore part of building the package rather than a courtesy. It
travels twice: as `/opt/javafx-login/share/doc/copyright`, which is where jpackage puts a
package's copyright file when the package installs under `/opt`, and as
`/opt/javafx-login/lib/doc/THIRD-PARTY-NOTICES.md`, beside the software it is about.

## The runtime image

**OpenJDK 21** — GNU General Public License, version 2, with the Classpath Exception
(GPLv2+CE), <https://openjdk.org/legal/gplv2+ce.html>. Copyright © Oracle and/or its
affiliates and other contributors.

The runtime inside this package is not a copy of a JDK. It is an image `jlink` assembled
from a subset of the modules of the OpenJDK build the package was made with, and it is
covered by the same licence as the whole. The Classpath Exception is what allows this
product, which is not GPL, to be linked against it and shipped as one thing.

Corresponding source: <https://github.com/openjdk/jdk21u>, at the tag of the build used —
`/opt/javafx-login/lib/runtime/release` names it.

## The toolkit

**OpenJFX 21.0.12** — GNU General Public License, version 2, with the Classpath Exception
(GPLv2+CE), <https://openjdk.org/legal/gplv2+ce.html>. Copyright © Oracle and/or its
affiliates and other contributors.

OpenJFX is a separate project from the JDK and is not part of the runtime image above; its
jars and its native libraries are in `/opt/javafx-login/lib/app`.

Corresponding source: <https://github.com/openjdk/jfx>, tag `21.0.12`.

## The libraries

| Component | Version | Licence |
| --- | --- | --- |
| `org.xerial:sqlite-jdbc` | 3.53.2.1 | Apache License 2.0. Carries SQLite itself, which its authors have placed in the public domain. |
| `com.password4j:password4j` | 1.8.2 | Apache License 2.0 |
| `com.fasterxml.jackson.core:jackson-core`, `jackson-databind`, `jackson-annotations` | 2.22.1, 2.22.1, 2.22 | Apache License 2.0 |
| `org.passay:passay` | 2.0.0 | Apache License 2.0 or GNU Lesser General Public License 3.0, at your option |
| `me.gosimple:nbvcxz` | 1.5.1 | MIT |
| `org.slf4j:slf4j-api` | 2.0.12 | MIT. Copyright © 2004–2022 QOS.ch Sarl |

The Apache License 2.0 is at <https://www.apache.org/licenses/LICENSE-2.0.txt>, the LGPL
3.0 at <https://www.gnu.org/licenses/lgpl-3.0.txt>, and each jar carries whatever notice
its authors put in it under `META-INF`.

## This product

Copyright the JavaFX Login authors, under the Apache License 2.0.
