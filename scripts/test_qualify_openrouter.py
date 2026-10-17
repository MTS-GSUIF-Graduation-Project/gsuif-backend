"""Mock-only tests. Never loads the local encrypted key or makes a network request."""

import json
import io
from contextlib import redirect_stdout
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qualify_openrouter as q


SOURCE = """package qualification.gadget;
import java.util.List;
import java.util.UUID;
final class GadgetServiceImpl implements GadgetService {
  private final GadgetRepository repository;
  GadgetServiceImpl(GadgetRepository repository) { this.repository = repository; }
  public List<GadgetDto> fetchGadgets() { return repository.findAll(); }
  public GadgetDto submitGadget(CreateGadgetRequest request) {
    return repository.save(new GadgetDto(UUID.randomUUID(), request.title(), request.status(), request.note()));
  }
  public GadgetDto findGadget(UUID id) { return repository.findById(id).orElseThrow(GadgetNotFoundException::new); }
  public GadgetDto replaceGadget(UUID id, UpdateGadgetRequest request) {
    findGadget(id);
    return repository.save(new GadgetDto(id, request.title(), request.status(), request.note()));
  }
  public void removeGadget(UUID id) { findGadget(id); repository.deleteById(id); }
}
"""
IMAGE = "sha256:" + "a" * 64


def response(source=SOURCE, model=q.MODEL, finish="stop"):
    return json.dumps({"model": model, "choices": [{"finish_reason": finish,
                       "message": {"content": source}}],
                       "usage": {"completion_tokens": 300}}).encode()


def single_attempt_report(root):
    reports = list((Path(root) / "target/openrouter-qualification/gadget/attempts").glob("*/result.json"))
    if len(reports) != 1:
        raise AssertionError("Expected exactly one isolated qualification report")
    return reports[0]


class QualificationTests(unittest.TestCase):
    def test_locked_fixtures_normalize_line_endings_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in q.FIXTURES:
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((q.ROOT / relative).read_bytes().replace(b"\r\n", b"\n"))
            with patch.object(q, "ROOT", root):
                locked_lf = q.locked_fixture()
                self.assertIn("FILE src/test/resources/openapi/gadget-api.yaml", locked_lf)
                gadget = root / "src/test/resources/openapi/gadget-api.yaml"
                original = gadget.read_bytes()
                gadget.write_bytes(original.replace(b"\n", b"\r\n"))
                self.assertEqual(q.locked_fixture(), locked_lf)
                gadget.write_bytes(original.replace(b"\n", b"\r\n", 1))
                self.assertEqual(q.locked_fixture(), locked_lf)
                for changed in (original + b"# changed\n", original.replace(b"\n", b"\r", 1)):
                    gadget.write_bytes(changed)
                    with self.assertRaises(q.QualificationFailure) as failure:
                        q.locked_fixture()
                    self.assertEqual(failure.exception.code, "FIXTURE_CHANGED")

    def test_request_is_exact_and_bounded(self):
        body = json.loads(q.request_body())
        self.assertEqual(body["model"], "qwen/qwen3-coder")
        self.assertEqual(body["max_tokens"], 4096)
        self.assertEqual(body["provider"], {"allow_fallbacks": False})
        self.assertEqual(q.MAX_REQUESTS, 2)
        self.assertEqual((q.CONNECT_TIMEOUT, q.RESPONSE_TIMEOUT, q.OVERALL_DEADLINE, q.RETRY_DELAY), (10, 120, 250, 2))
        self.assertIn("replaceGadget", body["messages"][0]["content"])
        self.assertNotIn("OPENROUTER_API_KEY", body["messages"][0]["content"])

    def test_retry_only_retriable_failures(self):
        for first in (429, 500, 503):
            calls, sleeps, outcomes = [], [], []
            def transport(payload, key, deadline):
                calls.append(deadline)
                return (first, b"" ) if len(calls) == 1 else (200, response())
            body, count = q.call_bounded(b"{}", "fake", transport=transport,
                                         sleep=sleeps.append, on_outcome=outcomes.append)
            self.assertEqual((count, len(calls), sleeps, body), (2, 2, [2], response()))
            self.assertEqual(outcomes, [{"request": 1, "httpStatus": first},
                                        {"request": 2, "httpStatus": 200}])
        calls, outcomes = [], []
        def flaky(payload, key, deadline):
            calls.append(1)
            if len(calls) == 1:
                raise q.RetryableTransport()
            return 200, response()
        self.assertEqual(q.call_bounded(b"{}", "fake", transport=flaky,
                                        sleep=lambda _: None, on_outcome=outcomes.append)[1], 2)
        self.assertEqual(outcomes, [{"request": 1, "transportError": "NETWORK_OR_TIMEOUT"},
                                    {"request": 2, "httpStatus": 200}])

    def test_non_retryable_and_exhaustion(self):
        for status in (400, 401, 403, 404, 422):
            calls = []
            def transport(payload, key, deadline):
                calls.append(1)
                return status, b"ignored"
            with self.assertRaises(q.QualificationFailure):
                q.call_bounded(b"{}", "fake", transport=transport, sleep=lambda _: None)
            self.assertEqual(len(calls), 1)
        calls, outcomes = [], []
        def transport(payload, key, deadline):
            calls.append(1)
            return 503, b"ignored"
        with self.assertRaises(q.QualificationFailure) as failure:
            q.call_bounded(b"{}", "fake", transport=transport, sleep=lambda _: None,
                           on_outcome=outcomes.append)
        self.assertEqual(failure.exception.code, "RETRY_EXHAUSTED")
        self.assertEqual(len(calls), 2)
        self.assertEqual(outcomes, [{"request": 1, "httpStatus": 503},
                                    {"request": 2, "httpStatus": 503}])

    def test_transport_exhaustion_records_only_fixed_categories(self):
        outcomes = []
        def transport(payload, key, deadline):
            raise q.RetryableTransport()
        with self.assertRaises(q.QualificationFailure) as failure:
            q.call_bounded(b"{}", "disposable-fake-key", transport=transport,
                           sleep=lambda _: None, on_outcome=outcomes.append)
        self.assertEqual(failure.exception.code, "TRANSPORT_FAILURE")
        self.assertEqual(outcomes, [{"request": 1, "transportError": "NETWORK_OR_TIMEOUT"},
                                    {"request": 2, "transportError": "NETWORK_OR_TIMEOUT"}])

    def test_received_status_is_recorded_when_transport_then_fails(self):
        outcomes = []
        calls = []
        def transport(payload, key, deadline):
            calls.append(1)
            if len(calls) == 1:
                raise q.RetryableTransport(502)
            return 200, response()
        self.assertEqual(q.call_bounded(b"{}", "disposable-fake-key", transport=transport,
                                        sleep=lambda _: None, on_outcome=outcomes.append)[1], 2)
        self.assertEqual(outcomes, [{"request": 1, "httpStatus": 502},
                                    {"request": 2, "httpStatus": 200}])

    def test_deadline_prevents_retry(self):
        ticks = iter((0, 0, 249))
        calls = []
        def transport(payload, key, deadline):
            calls.append(1)
            return 503, b""
        with self.assertRaises(q.QualificationFailure) as failure:
            q.call_bounded(b"{}", "fake", transport=transport, clock=lambda: next(ticks), sleep=lambda _: None)
        self.assertEqual(failure.exception.code, "OVERALL_DEADLINE")
        self.assertEqual(len(calls), 1)

    def test_output_boundary_and_model(self):
        self.assertEqual(q.parse_source(response(), "fake"), SOURCE)
        for bad, code in ((response(model="other/model"), "MODEL_MISMATCH"),
                          (response(finish="length"), "INCOMPLETE_OUTPUT"),
                          (response(source="```java\n" + SOURCE), "OUTPUT_BOUNDARY"),
                          (response(source=SOURCE + "\nclass AssetTicket {}"), "OUTPUT_BOUNDARY"),
                          (response(source=SOURCE.replace("import java.util.List;", "import java.net.URL;")), "UNAPPROVED_IMPORT"),
                          (response(source=SOURCE.replace("fake", "fake-secret")), None)):
            if code is None:
                continue
            with self.assertRaises(q.QualificationFailure) as failure:
                q.parse_source(bad, "fake")
            self.assertEqual(failure.exception.code, code)
        with self.assertRaises(q.QualificationFailure):
            q.parse_source(response(source=SOURCE + "\n// fake-secret"), "fake-secret")

    def test_single_outer_java_fence_is_normalized(self):
        for opening in ("```java", "```"):
            with self.subTest(opening=opening):
                codes = []
                wrapped = " \n" + opening + "\n" + SOURCE + "```\n \t"
                self.assertEqual(q.parse_source(response(source=wrapped), "disposable-fake-key",
                                                on_normalization=codes.append), SOURCE.strip())
                self.assertEqual(codes, ["OUTER_JAVA_FENCE"])
        codes = []
        self.assertEqual(q.parse_source(response(), "disposable-fake-key",
                                        on_normalization=codes.append), SOURCE)
        self.assertEqual(codes, ["RAW_JAVA"])

    def test_fence_normalization_rejects_prose_multiple_nested_and_malformed_blocks(self):
        valid = "```java\n" + SOURCE + "```"
        malformed = (
            "Here is the source:\n" + valid,
            valid + "\nDone.",
            valid + "\n" + valid,
            "```java\n" + SOURCE + "```\n```java\n" + SOURCE + "```",
            "```java\n" + SOURCE + "// ```nested```\n```",
            "```python\n" + SOURCE + "```",
            "````java\n" + SOURCE + "````",
            "```java\n" + SOURCE,
        )
        for source in malformed:
            with self.subTest(source_length=len(source)), self.assertRaises(q.QualificationFailure) as failure:
                q.parse_source(response(source=source), "disposable-fake-key")
            self.assertEqual((failure.exception.code, failure.exception.boundary_reason),
                             ("OUTPUT_BOUNDARY", "FORMAT_MARKDOWN_FENCE"))

    def test_unwrapped_source_still_passes_contract_and_security_checks(self):
        cases = ((SOURCE.replace("package qualification.gadget;", "package qualification.other;"),
                  "OUTPUT_BOUNDARY", "CONTRACT_PACKAGE_MISMATCH"),
                 (SOURCE.replace("import java.util.List;", "import java.net.URL;"),
                  "UNAPPROVED_IMPORT", "CONTRACT_IMPORT_NOT_ALLOWED"),
                 (SOURCE + "// System.exit(0)\n", "OUTPUT_BOUNDARY", "SECURITY_JVM_TERMINATION"),
                 (SOURCE + "// disposable-fake-key\n", "UNSAFE_SOURCE", "SECURITY_SOURCE_CONTAINS_KEY"))
        for inner, code, reason in cases:
            with self.subTest(reason=reason), self.assertRaises(q.QualificationFailure) as failure:
                q.parse_source(response(source="```java\n" + inner + "```"), "disposable-fake-key")
            self.assertEqual((failure.exception.code, failure.exception.boundary_reason), (code, reason))

    def test_report_records_fixed_normalization_and_runs_existing_local_checks(self):
        wrapped = "```java\n" + SOURCE + "```"
        observed = []
        def http(payload, key, deadline, on_outcomes):
            on_outcomes([{"request": 1, "httpStatus": 200}])
            return response(source=wrapped), 1
        def local(source, output, deadline):
            observed.append(source)
            return "PASSED"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(q.os, "environ", {"OPENROUTER_API_KEY": "disposable-fake-key",
                                               "OPENROUTER_MODEL": q.MODEL}), \
                patch.object(q, "ROOT", Path(directory)), \
                patch.object(q.sys, "argv", ["qualify_openrouter.py", "--live"]), \
                patch.object(q, "preflight_sandbox", return_value=IMAGE), \
                patch.object(q, "request_body", return_value=b"{}"), \
                patch.object(q, "run_http_bounded", side_effect=http), \
                patch.object(q, "compile_and_check", side_effect=local):
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 0)
            report = json.loads(single_attempt_report(directory).read_text())
        self.assertEqual(observed, [SOURCE.strip()])
        self.assertEqual(report["normalization"], "OUTER_JAVA_FENCE")
        self.assertEqual((report["compilation"], report["behavior"], report["status"]),
                         ("PASSED", "PASSED", "PASSED"))

    def test_boundary_reasons_distinguish_format_contract_and_security(self):
        cases = (
            ("```java\n" + SOURCE, "OUTPUT_BOUNDARY", "FORMAT_MARKDOWN_FENCE", "FORMATTING"),
            ("Here is the source:\n" + SOURCE, "OUTPUT_BOUNDARY", "FORMAT_MISSING_PACKAGE_HEADER", "FORMATTING"),
            (SOURCE.replace("package qualification.gadget;", "package qualification.other;"),
             "OUTPUT_BOUNDARY", "CONTRACT_PACKAGE_MISMATCH", "SOURCE_CONTRACT"),
            (SOURCE + "\n// AssetTicket", "OUTPUT_BOUNDARY", "CONTRACT_FOREIGN_DOMAIN", "SOURCE_CONTRACT"),
            (SOURCE.replace("import java.util.List;", "import java.net.URL;"),
             "UNAPPROVED_IMPORT", "CONTRACT_IMPORT_NOT_ALLOWED", "SOURCE_CONTRACT"),
            (SOURCE.replace("implements GadgetService", "implements Runnable"),
             "CONTRACT_MISMATCH", "CONTRACT_IMPLEMENTATION_CLASS", "SOURCE_CONTRACT"),
            (SOURCE.replace("fetchGadgets", "listGadgets"),
             "CONTRACT_MISMATCH", "CONTRACT_OPERATION_MISSING", "SOURCE_CONTRACT"),
            (SOURCE + "\nclass Other {}", "OUTPUT_BOUNDARY", "CONTRACT_CLASS_COUNT", "SOURCE_CONTRACT"),
            (SOURCE + "\nrecord Other() {}", "OUTPUT_BOUNDARY", "CONTRACT_EXTRA_TYPE", "SOURCE_CONTRACT"),
            (SOURCE + "\n// System.getenv", "OUTPUT_BOUNDARY", "SECURITY_ENV_ACCESS", "SECURITY"),
            (SOURCE + "\n// ProcessBuilder", "OUTPUT_BOUNDARY", "SECURITY_PROCESS_LAUNCH", "SECURITY"),
            (SOURCE + "\n// Runtime.getRuntime", "OUTPUT_BOUNDARY", "SECURITY_RUNTIME_ACCESS", "SECURITY"),
            (SOURCE + "\n// System.exit(0)", "OUTPUT_BOUNDARY", "SECURITY_JVM_TERMINATION", "SECURITY"),
            (SOURCE + "\n// disposable-fake-key", "UNSAFE_SOURCE", "SECURITY_SOURCE_CONTAINS_KEY", "SECURITY"),
            (SOURCE + "\n" + "x" * q.MAX_SOURCE_BYTES,
             "UNSAFE_SOURCE", "SECURITY_SOURCE_TOO_LARGE", "SECURITY"),
        )
        for source, code, reason, category in cases:
            with self.subTest(reason=reason), self.assertRaises(q.QualificationFailure) as failure:
                q.parse_source(response(source=source), "disposable-fake-key")
            self.assertEqual((failure.exception.code, failure.exception.boundary_reason), (code, reason))
            self.assertEqual(q.BOUNDARY_REASONS[reason], category)

    def test_synthetic_failure_report_contains_only_bounded_reason(self):
        synthetic = "```java\n" + SOURCE + "\n// provider-disposable-marker"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(q.os, "environ", {"OPENROUTER_API_KEY": "disposable-fake-key",
                                               "OPENROUTER_MODEL": q.MODEL}), \
                patch.object(q, "ROOT", Path(directory)), \
                patch.object(q.sys, "argv", ["qualify_openrouter.py", "--live"]), \
                patch.object(q, "preflight_sandbox", return_value=IMAGE), \
                patch.object(q, "request_body", return_value=b"{}"), \
                patch.object(q, "run_http_bounded", return_value=(response(source=synthetic), 1)), \
                patch.object(q, "compile_and_check") as compile_check:
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 1)
            path = single_attempt_report(directory)
            persisted = path.read_text(encoding="utf-8")
            report = json.loads(persisted)
            self.assertEqual((report["status"], report["boundaryCategory"], report["boundaryReason"]),
                             ("OUTPUT_BOUNDARY", "FORMATTING", "FORMAT_MARKDOWN_FENCE"))
            self.assertEqual((report["requests"], report["source"], report["compilation"], report["behavior"]),
                             (1, None, "NOT_RUN", "NOT_RUN"))
            self.assertNotIn("provider-disposable-marker", persisted)
            self.assertNotIn("disposable-fake-key", persisted)
            compile_check.assert_not_called()

    def test_generated_code_only_reaches_locked_down_container_without_credentials(self):
        calls = []
        def runner(command, **kwargs):
            calls.append((command, kwargs))
            if command[1:3] == ["image", "inspect"]:
                return subprocess.CompletedProcess(command, 0, stdout=(IMAGE + " linux\n").encode())
            return subprocess.CompletedProcess(command, 0)
        environment = {"PATH": os.environ.get("PATH", ""), q.SANDBOX_IMAGE_ENV: IMAGE,
                       "OPENROUTER_API_KEY": "disposable-fake-key", "OPENROUTER_MODEL": q.MODEL,
                       "JAVA_TOOL_OPTIONS": "-Dsecret=disposable", "OTHER_SECRET": "disposable"}
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(q.compile_and_check(SOURCE, Path(directory), time.monotonic() + 250,
                                                 runner=runner, environ=environment), "PASSED")
            self.assertEqual((Path(directory) / q.RELATIVE_SOURCE).read_text(), SOURCE)
        self.assertEqual(len(calls), 3)
        run_command, run_options = calls[1]
        self.assertEqual(run_command[:2], ["docker", "run"])
        for flag in ("--pull=never", "--network=none", "--read-only", "--ipc=none",
                     "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=64",
                     "--memory=256m", "--cpus=1", "--user=65532:65532", "--log-driver=none"):
            self.assertIn(flag, run_command)
        self.assertEqual(sum(arg.startswith("--mount=type=bind,src=") for arg in run_command), 1)
        self.assertTrue(any(arg.endswith(",dst=/input,readonly") for arg in run_command))
        self.assertIn("-proc:none", run_command[-1])
        self.assertIn("javac", run_command[-1])
        self.assertIn("java -cp /work/classes", run_command[-1])
        self.assertIn("GSUIF_GADGET_CHECK_COMPLETE:", run_command[-1])
        self.assertIn("grep -Fxq", run_command[-1])
        self.assertEqual(run_command[-3], IMAGE)
        self.assertIn("--entrypoint=/bin/sh", run_command)
        self.assertEqual(calls[2][0][:3], ["docker", "rm", "-f"])
        self.assertNotIn("OPENROUTER_API_KEY", run_options["env"])
        self.assertNotIn("OPENROUTER_MODEL", run_options["env"])
        self.assertNotIn("JAVA_TOOL_OPTIONS", run_options["env"])
        self.assertNotIn("OTHER_SECRET", run_options["env"])
        self.assertNotIn("disposable-fake-key", str(run_command))

    def test_premature_successful_jvm_exit_is_rejected(self):
        for termination in ("System.exit(0)", "java.lang.System . exit (0)",
                            "Runtime.exit(0)", "Runtime.halt(0)",
                            "Runtime.getRuntime().halt(0)",
                            "java.lang.Runtime . getRuntime ( ) . exit (0)"):
            with self.subTest(termination=termination):
                source = SOURCE.replace("return repository.findAll();", termination + "; return repository.findAll();")
                with self.assertRaises(q.QualificationFailure) as failure:
                    q.parse_source(response(source=source), "disposable-fake-key")
                self.assertEqual(failure.exception.code, "OUTPUT_BOUNDARY")

    def test_sandbox_fails_closed_and_distinguishes_compile_from_behavior(self):
        def runner_for(exit_code):
            def runner(command, **kwargs):
                if command[1:3] == ["image", "inspect"]:
                    return subprocess.CompletedProcess(command, 0, stdout=(IMAGE + " linux\n").encode())
                return subprocess.CompletedProcess(command, exit_code if command[1] == "run" else 0)
            return runner
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaises(q.QualificationFailure) as missing:
                q.compile_and_check(SOURCE, output, time.monotonic() + 250,
                                    runner=runner_for(0), environ={})
            self.assertEqual(missing.exception.code, "SANDBOX_UNAVAILABLE")
            with self.assertRaises(q.QualificationFailure) as compile_failure:
                q.compile_and_check(SOURCE, output, time.monotonic() + 250,
                                    runner=runner_for(20), environ={q.SANDBOX_IMAGE_ENV: IMAGE})
            self.assertEqual(compile_failure.exception.code, "COMPILE_FAILED")
            self.assertEqual(q.compile_and_check(SOURCE, output, time.monotonic() + 250,
                                                runner=runner_for(21),
                                                environ={q.SANDBOX_IMAGE_ENV: IMAGE}), "FAILED")

    def test_missing_sandbox_blocks_before_any_http_request(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(q.os, "environ", {"OPENROUTER_API_KEY": "disposable-fake-key",
                                               "OPENROUTER_MODEL": q.MODEL}), \
                patch.object(q, "ROOT", Path(directory)), \
                patch.object(q.sys, "argv", ["qualify_openrouter.py", "--live"]), \
                patch.object(q, "preflight_sandbox", side_effect=q.QualificationFailure("SANDBOX_UNAVAILABLE")), \
                patch.object(q, "run_http_bounded") as http:
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 1)
            http.assert_not_called()
            report = json.loads(single_attempt_report(directory).read_text())
            self.assertEqual((report["status"], report["requests"]), ("SANDBOX_UNAVAILABLE", 0))

    def test_main_shares_one_deadline_across_http_and_local_checks(self):
        deadlines = []
        def preflight(output, deadline):
            deadlines.append(deadline)
            return IMAGE
        def http(payload, key, deadline, on_outcomes):
            deadlines.append(deadline)
            on_outcomes([{"request": 1, "httpStatus": 200}])
            return response(), 1
        def local(source, output, deadline):
            deadlines.append(deadline)
            return "PASSED"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(q.os, "environ", {"OPENROUTER_API_KEY": "disposable-fake-key",
                                               "OPENROUTER_MODEL": q.MODEL}), \
                patch.object(q, "ROOT", Path(directory)), \
                patch.object(q.sys, "argv", ["qualify_openrouter.py", "--live"]), \
                patch.object(q, "request_body", return_value=b"{}"), \
                patch.object(q, "parse_source", return_value=SOURCE), \
                patch.object(q, "preflight_sandbox", side_effect=preflight), \
                patch.object(q, "run_http_bounded", side_effect=http), \
                patch.object(q, "compile_and_check", side_effect=local):
            started = time.monotonic()
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 0)
            self.assertEqual(len(deadlines), 3)
            self.assertEqual(deadlines[0], deadlines[1])
            self.assertEqual(deadlines[1], deadlines[2])
            self.assertLessEqual(deadlines[0] - started, q.OVERALL_DEADLINE + 1)

    def test_retry_exhaustion_report_contains_only_bounded_http_outcomes(self):
        def exhausted(payload, key, deadline, on_outcomes):
            on_outcomes([{"request": 1, "httpStatus": 429},
                         {"request": 2, "httpStatus": 503}])
            raise q.QualificationFailure("RETRY_EXHAUSTED", 2)
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(q.os, "environ", {"OPENROUTER_API_KEY": "disposable-fake-key",
                                               "OPENROUTER_MODEL": q.MODEL}), \
                patch.object(q, "ROOT", Path(directory)), \
                patch.object(q.sys, "argv", ["qualify_openrouter.py", "--live"]), \
                patch.object(q, "preflight_sandbox", return_value=IMAGE), \
                patch.object(q, "request_body", return_value=b"{}"), \
                patch.object(q, "run_http_bounded", side_effect=exhausted), \
                patch.object(q, "compile_and_check") as compile_check:
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 1)
            persisted = single_attempt_report(directory).read_text()
            report = json.loads(persisted)
            self.assertEqual((report["status"], report["requests"]), ("RETRY_EXHAUSTED", 2))
            self.assertEqual(report["httpAttempts"], [{"request": 1, "httpStatus": 429},
                                                       {"request": 2, "httpStatus": 503}])
            self.assertNotIn("disposable-fake-key", persisted)
            self.assertNotIn("response body", persisted)
            compile_check.assert_not_called()

    def test_each_attempt_preserves_earlier_report_directory(self):
        calls = []
        def rejected(payload, key, deadline, on_outcomes):
            calls.append(1)
            on_outcomes([{"request": 1, "httpStatus": 429 if len(calls) == 1 else 503}])
            raise q.QualificationFailure("HTTP_REJECTED", 1)
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(q.os, "environ", {"OPENROUTER_API_KEY": "disposable-fake-key",
                                               "OPENROUTER_MODEL": q.MODEL}), \
                patch.object(q, "ROOT", Path(directory)), \
                patch.object(q.sys, "argv", ["qualify_openrouter.py", "--live"]), \
                patch.object(q, "preflight_sandbox", return_value=IMAGE), \
                patch.object(q, "request_body", return_value=b"{}"), \
                patch.object(q, "run_http_bounded", side_effect=rejected):
            legacy = Path(directory) / "target/openrouter-qualification/gadget/result.json"
            legacy.parent.mkdir(parents=True)
            legacy.write_text("prior bounded report", encoding="utf-8")
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 1)
            root = Path(directory) / "target/openrouter-qualification/gadget/attempts"
            first = next(root.glob("*/result.json"))
            first_content = first.read_bytes()
            with redirect_stdout(io.StringIO()):
                self.assertEqual(q.main(), 1)
            reports = list(root.glob("*/result.json"))
            self.assertEqual(len(reports), 2)
            self.assertIn(first, reports)
            self.assertEqual(first.read_bytes(), first_content)
            self.assertEqual(legacy.read_text(encoding="utf-8"), "prior bounded report")
            self.assertNotEqual(first.parent, next(path.parent for path in reports if path != first))

    def test_deadline_kills_container_and_never_starts_late_checks(self):
        calls = []
        def runner(command, **kwargs):
            calls.append(command)
            if command[1:3] == ["image", "inspect"]:
                return subprocess.CompletedProcess(command, 0, stdout=(IMAGE + " linux\n").encode())
            if command[1] == "run":
                self.assertLessEqual(kwargs["timeout"], 250)
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            return subprocess.CompletedProcess(command, 0)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(q.QualificationFailure) as expired:
                q.compile_and_check(SOURCE, Path(directory), time.monotonic() + 250,
                                    runner=runner, environ={q.SANDBOX_IMAGE_ENV: IMAGE})
            self.assertEqual(expired.exception.code, "OVERALL_DEADLINE")
            self.assertEqual(calls[-1][:3], ["docker", "rm", "-f"])
            calls.clear()
            with self.assertRaises(q.QualificationFailure) as late:
                q.compile_and_check(SOURCE, Path(directory), 1, runner=runner,
                                    environ={q.SANDBOX_IMAGE_ENV: IMAGE}, clock=lambda: 2)
            self.assertEqual(late.exception.code, "OVERALL_DEADLINE")
            self.assertEqual(calls, [])

    def test_container_removal_failure_is_reported_without_claiming_isolation(self):
        def runner(command, **kwargs):
            if command[1:3] == ["image", "inspect"]:
                return subprocess.CompletedProcess(command, 0, stdout=(IMAGE + " linux\n").encode())
            return subprocess.CompletedProcess(command, 1 if command[1] == "rm" else 0)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(q.QualificationFailure) as failure:
                q.compile_and_check(SOURCE, Path(directory), time.monotonic() + 250,
                                    runner=runner, environ={q.SANDBOX_IMAGE_ENV: IMAGE})
        self.assertEqual(failure.exception.code, "SANDBOX_TERMINATION_UNCONFIRMED")

    def test_disposable_credential_is_absent_from_real_trusted_child_environment(self):
        environment = {"PATH": os.environ.get("PATH", ""), "OPENROUTER_API_KEY": "disposable-fake-key",
                       "OPENROUTER_MODEL": q.MODEL}
        child = subprocess.run([sys.executable, "-c",
                                "import os,sys; sys.exit(1 if 'OPENROUTER_API_KEY' in os.environ else 0)"],
                               env=q.child_env(environment), stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=5, check=False)
        self.assertEqual(child.returncode, 0)

    def test_http_worker_has_absolute_deadline_and_no_key_in_environment(self):
        calls = []
        outcome = json.dumps({"status": "ok", "body": q.base64.b64encode(response()).decode(),
                              "requests": 2, "httpAttempts": [{"request": 1, "httpStatus": 429},
                                                              {"request": 2, "httpStatus": 200}]}).encode()
        def runner(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, stdout=outcome)
        deadline = time.monotonic() + 250
        observed = []
        body, requests = q.run_http_bounded(b"{}", "disposable-fake-key", deadline,
                                            runner=runner, on_outcomes=observed.extend)
        self.assertEqual((body, requests), (response(), 2))
        self.assertEqual(observed, [{"request": 1, "httpStatus": 429},
                                    {"request": 2, "httpStatus": 200}])
        self.assertEqual(calls[0][0][-1], "--http-worker")
        self.assertNotIn("OPENROUTER_API_KEY", calls[0][1]["env"])
        self.assertIn(b"disposable-fake-key", calls[0][1]["input"])
        self.assertLessEqual(calls[0][1]["timeout"], 250)
        def timeout_runner(command, **kwargs):
            raise subprocess.TimeoutExpired(command, kwargs["timeout"])
        with self.assertRaises(q.QualificationFailure) as expired:
            q.run_http_bounded(b"{}", "disposable-fake-key", deadline, runner=timeout_runner)
        self.assertEqual(expired.exception.code, "OVERALL_DEADLINE")
        with self.assertRaises(q.QualificationFailure) as late:
            q.run_http_bounded(b"{}", "disposable-fake-key", 1, runner=runner, clock=lambda: 2)
        self.assertEqual(late.exception.code, "OVERALL_DEADLINE")

    def test_worker_failure_outcomes_reject_extra_or_unbounded_fields(self):
        observed = []
        outcome = {"status": "failure", "code": "RETRY_EXHAUSTED", "requests": 2,
                   "httpAttempts": [{"request": 1, "httpStatus": 429},
                                    {"request": 2, "httpStatus": 503}],
                   "providerBody": "provider-disposable-marker"}
        def runner(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, stdout=json.dumps(outcome).encode())
        with self.assertRaises(q.QualificationFailure) as failure:
            q.run_http_bounded(b"{}", "disposable-fake-key", time.monotonic() + 250,
                               runner=runner, on_outcomes=observed.extend)
        self.assertEqual((failure.exception.code, failure.exception.requests), ("RETRY_EXHAUSTED", 2))
        self.assertEqual(observed, outcome["httpAttempts"])
        outcome["httpAttempts"][0]["exception"] = "provider-disposable-marker"
        observed.clear()
        with self.assertRaises(q.QualificationFailure) as invalid:
            q.run_http_bounded(b"{}", "disposable-fake-key", time.monotonic() + 250,
                               runner=runner, on_outcomes=observed.extend)
        self.assertEqual(invalid.exception.code, "TRANSPORT_FAILURE")
        self.assertEqual(observed, [])

    def test_transport_exception_keeps_status_only_after_response_arrives(self):
        class FakeSocket:
            def settimeout(self, value): pass
        class FakeResponse:
            status = 503
            def read(self, limit): raise q.socket.timeout()
        class FakeConnection:
            def __init__(self, host, timeout): self.sock = FakeSocket()
            def connect(self): pass
            def request(self, method, path, body, headers): pass
            def getresponse(self): return FakeResponse()
            def close(self): pass
        with patch.object(q.http.client, "HTTPSConnection", FakeConnection):
            with self.assertRaises(q.RetryableTransport) as failure:
                q.send_http(b"{}", "disposable-fake-key", time.monotonic() + 30)
        self.assertEqual(failure.exception.http_status, 503)

    def test_worker_reports_request_count_from_retry_attempts(self):
        payload = json.dumps({"payload": q.base64.b64encode(b"{}").decode(),
                              "key": "disposable-fake-key", "deadline": time.monotonic() + 250}).encode()
        input_stream = io.TextIOWrapper(io.BytesIO(payload), encoding="utf-8")
        output_stream = io.StringIO()
        def fake_call(body, key, **kwargs):
            self.assertEqual(key, "disposable-fake-key")
            kwargs["on_attempt"](1)
            kwargs["on_attempt"](2)
            kwargs["on_outcome"]({"request": 1, "httpStatus": 429})
            kwargs["on_outcome"]({"request": 2, "httpStatus": 200})
            return response(), 2
        with patch.object(q.sys, "stdin", input_stream), patch.object(q.sys, "stdout", output_stream), \
                patch.object(q, "call_bounded", fake_call):
            self.assertEqual(q.http_worker_main(), 0)
        outcome = json.loads(output_stream.getvalue())
        self.assertEqual(outcome["requests"], 2)
        self.assertEqual(outcome["httpAttempts"], [{"request": 1, "httpStatus": 429},
                                                    {"request": 2, "httpStatus": 200}])

    def test_http_transport_uses_declared_timeouts_and_never_sends_key_in_body(self):
        class FakeSocket:
            def __init__(self): self.timeouts = []
            def settimeout(self, value): self.timeouts.append(value)
        class FakeResponse:
            status = 200
            def __init__(self): self.chunks = [response(), b""]
            def read(self, limit): return self.chunks.pop(0)
        class FakeConnection:
            def __init__(self, host, timeout):
                self.host, self.timeout, self.sock = host, timeout, FakeSocket()
            def connect(self): pass
            def request(self, method, path, body, headers):
                self.method, self.path, self.body, self.headers = method, path, body, headers
            def getresponse(self): return FakeResponse()
            def close(self): pass
        connections = []
        def factory(host, timeout):
            conn = FakeConnection(host, timeout)
            connections.append(conn)
            return conn
        with patch.object(q.http.client, "HTTPSConnection", factory):
            status, body = q.send_http(b'{"model":"qwen/qwen3-coder"}', "disposable-fake", q.time.monotonic() + 250)
        self.assertEqual((status, body), (200, response()))
        connection = connections[0]
        self.assertEqual((connection.host, connection.method, connection.path), ("openrouter.ai", "POST", "/api/v1/chat/completions"))
        self.assertLessEqual(connection.timeout, 10)
        self.assertTrue(all(0 < value <= 120 for value in connection.sock.timeouts))
        self.assertNotIn(b"disposable-fake", connection.body)


if __name__ == "__main__":
    unittest.main()
