# Arquillian Container for Quarkus

Arquillian container adapter that runs Arquillian (ShrinkWrap) deployments on Quarkus.

Each deployment is built into a production Quarkus application, as a fast-jar or a native executable, and started in
its own process. The tests run as a client against it (`@Deployment(testable = false)`). The application and the tests
don't share classes, static state, system properties or the JUL LogManager.

## How it works

For every deployment, the container:

 * exports the archive and, for a WAR, rearranges it into the layout Quarkus reads:
   * `WEB-INF/classes` becomes the application root;
   * `WEB-INF/web.xml`, `web-fragment.xml` and `beans.xml` move to `META-INF/`;
   * the web root moves to `META-INF/resources`;
   * the `WEB-INF/lib` jars become dependencies of the application, indexed so their annotations are found, as a
     Servlet container scans `WEB-INF/lib` (jars with only `jakarta.*` or `java.*` classes are left out, as Quarkus
     provides those APIs). Libraries ShrinkWrap exported as directories are packaged as jars again. The jars are also
     kept in `WEB-INF/lib` of the application, so it can read them as resources (e.g. a taglib URI that names the jar
     with the TLD);
 * builds the application with the Quarkus bootstrap, using the configured extensions, with versions managed by the
   Quarkus BOM;
 * starts it on a free port (by default) and waits until it accepts connections;
 * stops it and removes its work directory when the deployment is undeployed.

The Quarkus bootstrap and its dependencies (Maven Resolver and so on) are embedded in the container jar and loaded in
an isolated class loader, so they don't end up on the test class path.

## Examples

 Quick example usage for the managed connector:

 Declare dependency to connector (container adapter):

```xml
 <dependency>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>arquillian-quarkus-managed</artifactId>
    <version>1.1.0-SNAPSHOT</version>
    <scope>test</scope>
 </dependency>
```

 Configure surefire or failsafe. Some examples are given below. Typically only `arquillian.quarkus.dependencies` is
 needed: the Quarkus extensions the application is built with.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${surefire.version}</version>
    <configuration>
        <systemPropertyVariables>
            <arquillian.quarkus.dependencies>
                io.quarkus:quarkus-rest
                io.quarkus:quarkus-elytron-security-properties-file
                org.apache.myfaces.core.extensions.quarkus:myfaces-quarkus:4.1.4
            </arquillian.quarkus.dependencies>

            <!-- Quarkus build time configuration -->
            <arquillian.quarkus.buildProperties>
                quarkus.security.users.embedded.enabled=true
            </arquillian.quarkus.buildProperties>

            <!-- System properties (and runtime configuration) for the application -->
            <arquillian.quarkus.systemProperties>
                quarkus.security.users.embedded.plain-text=true
                quarkus.security.users.embedded.users.user=password
            </arquillian.quarkus.systemProperties>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

 A test then deploys an archive and calls it via the injected URL:

```java
@ExtendWith(ArquillianExtension.class)
class HelloWorldTest {

    @ArquillianResource
    private URL base;

    @Deployment(testable = false)
    static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class)
                         .addClass(HelloWorldResource.class);
    }

    @Test
    void hello() throws Exception {
        HttpResponse<String> response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(base.toURI().resolve("hello")).build(),
                BodyHandlers.ofString());

        assertEquals("Hello, world", response.body());
    }
}
```

 See the [integration tests](integration-tests) for complete examples: REST, REST via Servlet in a WAR, and Jakarta
 Faces, each in JVM and native mode.

## Configuration

All options are system properties with the prefix `arquillian.quarkus.` (as above). Except for
`addFacesServletMappings`, they can also be set as properties of the container in `arquillian.xml`.

| Property | Default | Description |
|----------|---------|-------------|
| `dependencies` | `io.quarkus:quarkus-undertow` | Extensions and libraries the application is built with, as `groupId:artifactId[:version]`, separated by commas or whitespace. The version may be left out for artifacts managed by the Quarkus BOM. |
| `quarkusVersion` | the bootstrap's version | Version of the Quarkus BOM that manages the dependency versions. |
| `httpPort` | `0` | HTTP port of the application; `0` takes a free port per deployment, so test runs can run in parallel. |
| `startupTimeoutInSeconds` | `60` | Seconds to wait for the application to accept connections. |
| `javaVmArguments` | | Extra arguments for the application JVM (or native executable), separated by whitespace. |
| `systemProperties` | | System properties for the application, one `key=value` per line. |
| `buildProperties` | | Quarkus build time configuration, one `key=value` per line. |
| `contextRootFromArchiveName` | `true` | Deploy a WAR at `/<archive name>`, as Jakarta EE servers do, instead of at `/`. |
| `nativeImage` | `false` | Build and run each deployment as a native executable. |
| `useJdkLogManager` | `false` | Run a fast-jar with the JDK's LogManager instead of JBoss LogManager, for code that relies on JDK LogManager behavior. Not possible for native executables. |
| `addFacesServletMappings` | `false` | Map the `FacesServlet` to `/faces/*`, `*.jsf`, `*.faces` and `*.xhtml` in each WAR's `web.xml`, as Jakarta Faces requires when an application doesn't map it. MyFaces Quarkus only maps `*.xhtml`. |
| `keepWorkDirectory` | `false` | Keep each deployment's build and work directory, for troubleshooting. |

## Native

With `arquillian.quarkus.nativeImage=true`, every deployment is built into a native executable, which takes a minute or
more per deployment. This needs GraalVM or Mandrel: `GRAALVM_HOME`, GraalVM as the JDK that runs the tests, or
`quarkus.native.graalvm-home` in the build properties.

Quarkus deploys servlets while the native image is built, so servlet container initializers and context listeners run
in the image builder. The container therefore passes the `systemProperties` to the native image build as well.

## Building

```
mvn clean install
```

builds the container and runs the JVM integration tests. To also run the native integration tests:

```
mvn clean install -Dnative
```

## Known limitations

 * Tests run as a client only (`@Deployment(testable = false)`); there is no in-container protocol.
 * `quarkus-rest-servlet` (Quarkus 3.40) doesn't match resources when the application has a servlet context path, so
   set `contextRootFromArchiveName` to `false` for REST via Servlet in a WAR.
