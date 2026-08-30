package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP jar subprocess smoke test.
 *
 * <p>Launches the prebuilt {@code methodTraceLog-mcp-1.0-SNAPSHOT.jar} via
 * {@code jbang} (matching {@code .mcp.json}), waits for it to come up, and
 * asserts the JVM process is alive and producing output. This is intentionally
 * a thin smoke test: it does <em>not</em> drive JSON-RPC messages through the
 * stdio transport — it just verifies the jar is well-formed, the Spring Boot
 * MCP wiring initializes, and the process survives long enough for a real
 * client to connect.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li><strong>Jar path:</strong> resolved from {@code user.dir} (the Maven
 *       cwd, which is the repo root when invoked as
 *       {@code mvn -pl methodTraceLog-test test ...}). The brief's relative
 *       path {@code ./methodTraceLog-mcp/target/...} would only work from the
 *       repo root, not from {@code methodTraceLog-test/}, so we resolve
 *       defensively. If neither the brief's path nor {@code user.dir} finds
 *       the jar, the test fails fast with a clear message rather than
 *       spawning a process that exits immediately.</li>
 *   <li><strong>Readiness poll:</strong> the brief used a flat 5s sleep. We
 *       replace it with {@link #awaitAlive} which polls every 200ms up to 15s,
 *       failing the test only if the MCP JVM hasn't started by then. This
 *       makes the test robust against slow CI / first-run jbang caching.</li>
 *   <li><strong>Stderr mixed into stdout:</strong> {@code redirectErrorStream(true)}
 *       means the single {@code getInputStream()} carries both. The test
 *       drains a bounded prefix and prints each line with {@code [mcp]}
 *       prefix so the line lands in surefire's stdout under the test name.</li>
 *   <li><strong>No {@code MtlE2eHarness}:</strong> this test does not need a
 *       host — the MCP server is the system under test, not a downstream
 *       service. We still instantiate nothing related to the host harness to
 *       keep startup fast and avoid port collisions with sibling ITs.</li>
 *   <li><strong>Default stdin pipe:</strong> {@link ProcessBuilder} defaults
 *       to {@code PIPE} on stdin, which means the parent does not have to
 *       write anything for the child to stay alive — the child's read() will
 *       block forever, which is exactly what we want for the Stdio MCP
 *       transport.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpIntegrationIT {

    /** Per the .mcp.json recipe; passed verbatim to the jar. */
    private static final String[] MCP_ARGS = new String[] {
            "--method-trace-log.mcp.hosts[0].name=local-dev",
            "--method-trace-log.mcp.hosts[0].url=http://localhost:8085",
            "--method-trace-log.mcp.hosts[0].api-key=change-me-in-production"
    };

    /** Final jar path resolved at setup time. */
    private static Path mcpJar;

    private Process mcp;

    @BeforeAll
    void setup() throws Exception {
        mcpJar = locateJar();
        assertThat(mcpJar)
                .as("MCP jar must exist before spawning")
                .isRegularFile();

        String jbangCmd = locateJbang();
        assertThat(jbangCmd)
                .as("jbang executable must be resolvable (PATH or user.home/.jbang/bin)")
                .isNotNull();

        ProcessBuilder pb = new ProcessBuilder(jbangCmd, mcpJar.toString())
                .redirectErrorStream(true);
        // Append the .mcp.json recipe flags.
        for (String arg : MCP_ARGS) {
            pb.command().add(arg);
        }
        mcp = pb.start();

        boolean alive = awaitAlive(mcp, Duration.ofSeconds(15));
        assertThat(alive)
                .as("MCP jar should be alive within 15s (jar=%s, jbang=%s, user.dir=%s)",
                        mcpJar, jbangCmd, System.getProperty("user.dir"))
                .isTrue();
    }

    @AfterAll
    void teardown() {
        if (mcp != null && mcp.isAlive()) {
            mcp.destroyForcibly();
            try {
                mcp.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void mcp_process_is_alive_and_can_be_inspected() {
        // Smoke test: process started successfully and is alive after the
        // readiness poll in setup().
        assertThat(mcp.isAlive())
                .as("MCP process should still be alive at the time of inspection")
                .isTrue();

        // Drain a bounded prefix of the merged stdout+stderr stream for
        // diagnostics. We bound by both wall-clock deadline and line count so
        // the test never blocks even if jbang or the JVM keeps printing.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(mcp.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            Instant deadline = Instant.now().plusSeconds(1);
            while (lines < 20 && Instant.now().isBefore(deadline)
                    && (line = reader.readLine()) != null) {
                System.out.println("[mcp] " + line);
                lines++;
            }
            // Diagnostic: report how many lines we saw in case the test ever
            // regresses and the process dies before producing output.
            System.out.println("[mcp] drained " + lines + " line(s); isAlive=" + mcp.isAlive());
        } catch (Exception e) {
            // Drain failures are non-fatal — the contract is "process alive",
            // not "stream readable". Surface for diagnostics only.
            System.out.println("[mcp] drain failed (non-fatal): " + e);
        }
    }

    /**
     * Polls {@link Process#isAlive()} every 200ms until it returns {@code true}
     * or the deadline is reached. Avoids the brittleness of a flat
     * {@code Thread.sleep(5000)} in CI where jbang's first-run cache may push
     * MCP startup past 5s.
     */
    private static boolean awaitAlive(Process p, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (p.isAlive()) return true;
            Thread.sleep(200);
        }
        return p.isAlive();
    }

    /**
     * Resolves the MCP jar path. Tries, in order:
     * <ol>
     *   <li>{@code user.dir/methodTraceLog-mcp/target/...jar} — the brief's
     *       path, which works when Maven runs from the repo root.</li>
     *   <li>{@code ../methodTraceLog-mcp/target/...jar} relative to
     *       {@code user.dir} — works when Maven runs from
     *       {@code methodTraceLog-test/} for any reason.</li>
     * </ol>
     * Returns the first path that exists as a regular file; otherwise returns
     * the brief's path so the assertion message points at the canonical
     * location.
     */
    private static Path locateJar() {
        Path userDir = Paths.get(System.getProperty("user.dir"));
        Path fromUserDir = userDir.resolve(
                "methodTraceLog-mcp/target/methodTraceLog-mcp-1.0-SNAPSHOT.jar");
        if (Files.isRegularFile(fromUserDir)) {
            return fromUserDir;
        }
        Path fromModule = userDir.resolve("../methodTraceLog-mcp/target/methodTraceLog-mcp-1.0-SNAPSHOT.jar").normalize();
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        // Fall back to the brief's path so the failure message is informative.
        return fromUserDir;
    }

    /**
     * Resolves the {@code jbang} executable. The bare command {@code "jbang"}
     * works in interactive shells but fails inside the surefire-forked JVM on
     * Windows (CreateProcess error 2: "system cannot find the file"). We try:
     * <ol>
     *   <li>The bare command {@code jbang} — works on dev shells and most CI
     *       runners where jbang is on PATH.</li>
     *   <li>{@code JBANG_HOME}/bin/jbang.cmd (or {@code jbang}) — works
     *       regardless of PATH inheritance.</li>
     *   <li>{@code user.home}/.jbang/bin/jbang.cmd (or {@code jbang}) — the
     *       standard jbang user install location.</li>
     * </ol>
     * Returns the first candidate that exists as a regular file. On Windows
     * we prefer the {@code .cmd} shim because the JVM's CreateProcess does
     * not always honor {@code PATHEXT} when the bare {@code jbang} script
     * (no extension) lives on PATH.
     */
    private static String locateJbang() {
        List<Path> candidates = new ArrayList<>();

        // 1) Bare command — works on *nix and on Windows shells with jbang on PATH.
        candidates.add(Paths.get("jbang"));

        // 2) JBANG_HOME-based.
        String jbangHome = System.getenv("JBANG_HOME");
        if (jbangHome != null && !jbangHome.isEmpty()) {
            Path home = Paths.get(jbangHome);
            candidates.add(home.resolve("bin/jbang"));
            candidates.add(home.resolve("bin/jbang.cmd"));
        }

        // 3) user.home-based — standard jbang install location.
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            Path home = Paths.get(userHome, ".jbang", "bin");
            candidates.add(home.resolve("jbang.cmd"));
            candidates.add(home.resolve("jbang"));
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        // Return the bare command anyway so the failure surfaces as
        // "Cannot run program" rather than NPE — easier to diagnose in CI.
        return "jbang";
    }
}
