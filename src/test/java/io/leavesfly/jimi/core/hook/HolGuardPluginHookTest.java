package io.leavesfly.jimi.core.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HolGuardPluginHookTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void guardHookUsesFailClosedSynchronousExecution() throws IOException {
        Path hookPath = Path.of("integrations", "hol-guard", "hooks", "hol-guard-pre-tool.yaml");
        Map<String, Object> root = new Yaml().load(Files.readString(hookPath, StandardCharsets.UTF_8));
        Map<String, Object> execution = (Map<String, Object>) root.get("execution");
        ExecutionSpec spec = new ObjectMapper().convertValue(execution, ExecutionSpec.class);

        spec.validate();
        assertEquals("script", spec.getType());
        assertEquals(false, spec.isAsync());
        assertTrue(spec.isFailClosed(), "HOL Guard must block on hook errors and timeouts");
    }

    @Test
    void onlyExplicitBenignAllowPasses() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/bash")));
        String script = loadHookScript();
        Path binDir = createGuardFixture();

        assertEquals(0, runHook(script, binDir, "explicit-allow",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        String multilineCommand = new ObjectMapper().writeValueAsString(Map.of(
                "hook_event_name", "PRE_TOOL_USE", "tool_name", "BashTool",
                "tool_input", Map.of("command", "git status\n")));
        assertEquals(0, runHook(script, binDir, "preserve-newline", multilineCommand));
        // Bash command substitution drops NUL bytes. Reject before inspecting a
        // different command from the one supplied by the tool call.
        String nulCommand = new ObjectMapper().writeValueAsString(Map.of(
                "hook_event_name", "PRE_TOOL_USE", "tool_name", "BashTool",
                "tool_input", Map.of("command", "git status" + (char) 0 + " && rm -rf build")));
        assertEquals(2, runHook(script, binDir, "explicit-allow", nulCommand));
        // A literal raw NUL is invalid JSON; do not let Bash silently remove it
        // before the hook parser sees the original bytes.
        String rawNulInput = "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\","
                + "\"tool_input\":{\"command\":\"git status" + (char) 0 + " && rm -rf build\"}}";
        assertEquals(2, runHook(script, binDir, "explicit-allow", rawNulInput));
        String duplicateInputCommand = "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\","
                + "\"tool_input\":{\"command\":\"git status\",\"command\":\"rm -rf build\"}}";
        assertEquals(2, runHook(script, binDir, "explicit-allow", duplicateInputCommand));
        String duplicateInputEnvelope = "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\","
                + "\"tool_input\":{\"command\":\"git status\"},"
                + "\"tool_input\":{\"command\":\"rm -rf build\"}}";
        assertEquals(2, runHook(script, binDir, "explicit-allow", duplicateInputEnvelope));
        assertEquals(2, runHook(script, binDir, "implicit-allow",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "decision-deny",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "null-decision-envelope",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "policy-block",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "hook-deny",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "continue-false",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "continue-malformed",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(0, runHook(script, binDir, "inspection-not-run",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "wrong-hook-event",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "conflicting-verdicts",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "duplicate-top-level-decision", multilineCommand));
        assertEquals(2, runHook(script, binDir, "duplicate-nested-decision", multilineCommand));
        assertEquals(2, runHook(script, binDir, "diagnostic-single",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "null-policy-envelope",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "null-hook-envelope",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "review",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"rm -rf ./build\"}}"));
        assertEquals(2, runHook(script, binDir, "malformed",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "error",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "explicit-allow",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\"}"));
    }

    @SuppressWarnings("unchecked")
    private String loadHookScript() throws IOException {
        Path hookPath = Path.of("integrations", "hol-guard", "hooks", "hol-guard-pre-tool.yaml");
        assertTrue(Files.exists(hookPath));
        Map<String, Object> root = new Yaml().load(Files.readString(hookPath, StandardCharsets.UTF_8));
        Map<String, Object> execution = (Map<String, Object>) root.get("execution");
        return (String) execution.get("script");
    }

    private Path createGuardFixture() throws IOException {
        Path binDir = Files.createDirectory(tempDir.resolve("bin"));
        Path guard = binDir.resolve("hol-guard");
        Files.writeString(guard, """
                #!/bin/sh
                case \"$HOL_GUARD_FIXTURE\" in
                  explicit-allow)
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  preserve-newline)
                    EXPECTED=$(printf 'git status\\n.')
                    EXPECTED=${EXPECTED%.}
                    if [ "$5" != "$EXPECTED" ]; then
                      exit 1
                    fi
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  implicit-allow)
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":false}}'
                    exit 0
                    ;;
                  decision-deny)
                    printf '%s\\n' '{\"decision\":\"deny\",\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  null-decision-envelope)
                    printf '%s\\n' '{\"decision\":null,\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  policy-block)
                    printf '%s\\n' '{\"policy_action\":\"block\",\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  hook-deny)
                    printf '%s\\n' '{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\"},\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  continue-false)
                    printf '%s\\n' '{\"continue\":false,\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  continue-malformed)
                    printf '%s\\n' '{\"continue\":\"false\",\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  inspection-not-run)
                    printf '%s\\n' '{\"policy_action\":\"not_run\",\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  wrong-hook-event)
                    printf '%s\\n' '{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"permissionDecision\":\"allow\"},\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  conflicting-verdicts)
                    printf '%s\\n' '{\"minimum_action\":\"review\",\"classification\":{\"explicitly_benign\":false}}'
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  duplicate-top-level-decision)
                    printf '%s\\n' '{\"minimum_action\":\"review\",\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  duplicate-nested-decision)
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":false,\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  diagnostic-single)
                    printf '%s\\n' 'diagnostic message'
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  null-policy-envelope)
                    printf '%s\\n' '{\"policy_action\":null,\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  null-hook-envelope)
                    printf '%s\\n' '{\"hookSpecificOutput\":null,\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  review)
                    printf '%s\\n' '{\"minimum_action\":\"review\",\"classification\":{\"reason\":\"review\"}}'
                    exit 0
                    ;;
                  malformed)
                    printf '%s\\n' 'not-json'
                    exit 0
                    ;;
                  error)
                    printf '%s\\n' 'guard failed' >&2
                    exit 1
                    ;;
                  *)
                    exit 1
                    ;;
                esac
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(guard, PosixFilePermissions.fromString("rwx------"));
        return binDir;
    }

    private int runHook(String script, Path binDir, String fixture, String stdin) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", script);
        processBuilder.directory(tempDir.toFile());
        String existingPath = processBuilder.environment().getOrDefault("PATH", "");
        processBuilder.environment().put("PATH", binDir + ":" + existingPath);
        processBuilder.environment().put("HOL_GUARD_FIXTURE", fixture);

        Process process = processBuilder.start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        boolean finished = process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(finished, "hook fixture timed out");
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        return process.exitValue();
    }
}
