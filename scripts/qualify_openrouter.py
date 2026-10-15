"""Bounded, opt-in Gadget service qualification. No key, prompt or provider body is logged."""

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
MODEL = "qwen/qwen3-coder"
HOST = "openrouter.ai"
PATH = "/api/v1/chat/completions"
MAX_REQUESTS = 2
MAX_OUTPUT_TOKENS = 4096
CONNECT_TIMEOUT = 10
RESPONSE_TIMEOUT = 120
OVERALL_DEADLINE = 250
RETRY_DELAY = 2
MAX_RESPONSE_BYTES = 65536
MAX_SOURCE_BYTES = 24576
RELATIVE_SOURCE = Path("qualification/gadget/GadgetServiceImpl.java")
SANDBOX_IMAGE_ENV = "GSUIF_QUALIFICATION_IMAGE"
IMAGE_ID_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")
CHILD_ENV_KEYS = ("PATH", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP")
FIXTURES = {
    "src/test/resources/generation/gadget-contracts.yaml": "77479c04af06b93d4da386e9ea0c152f1e36e97fdad9d8fe3ea9c61ef94ec455",
    "src/test/resources/openapi/gadget-api.yaml": "48e132c7e8c74f647c9089ba878ccbce517a1e7611d40e1d4ceefe7e9541004f",
    "qualification/gadget/GadgetContract.java": "2d2b574bcf30f79ea0c0de991d1552cd5b9cd4d0b7bdeb5d0796a47abb764fa4",
}
BOUNDARY_REASONS = {
    "FORMAT_MARKDOWN_FENCE": "FORMATTING",
    "FORMAT_MISSING_PACKAGE_HEADER": "FORMATTING",
    "CONTRACT_PACKAGE_MISMATCH": "SOURCE_CONTRACT",
    "CONTRACT_FOREIGN_DOMAIN": "SOURCE_CONTRACT",
    "CONTRACT_IMPORT_NOT_ALLOWED": "SOURCE_CONTRACT",
    "CONTRACT_IMPLEMENTATION_CLASS": "SOURCE_CONTRACT",
    "CONTRACT_OPERATION_MISSING": "SOURCE_CONTRACT",
    "CONTRACT_CLASS_COUNT": "SOURCE_CONTRACT",
    "CONTRACT_EXTRA_TYPE": "SOURCE_CONTRACT",
    "SECURITY_SOURCE_CONTAINS_KEY": "SECURITY",
    "SECURITY_SOURCE_TOO_LARGE": "SECURITY",
    "SECURITY_ENV_ACCESS": "SECURITY",
    "SECURITY_PROCESS_LAUNCH": "SECURITY",
    "SECURITY_RUNTIME_ACCESS": "SECURITY",
    "SECURITY_JVM_TERMINATION": "SECURITY",
}


class QualificationFailure(Exception):
    def __init__(self, code, requests=None, boundary_reason=None, http_status=None):
        super().__init__(code)
        self.code = code
        self.requests = requests
        self.boundary_reason = boundary_reason
        self.http_status = http_status


class RetryableTransport(Exception):
    def __init__(self, http_status=None):
        self.http_status = http_status


def locked_fixture():
    parts = []
    for relative, expected in FIXTURES.items():
        data = (ROOT / relative).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise QualificationFailure("FIXTURE_CHANGED")
        parts.append(f"FILE {relative}\n{data.decode('utf-8')}")
    return "\n".join(parts)


def request_body():
    prompt = (
        "Implement exactly one Java source file: qualification/gadget/GadgetServiceImpl.java. "
        "Return raw Java source only, without Markdown, JSON, path text or other files. "
        "Use package qualification.gadget and implement GadgetService with a constructor taking GadgetRepository. "
        "Use only the declared fixture types and explicit CRUD operation mapping. "
        "CREATE generates a UUID; READ/UPDATE/DELETE throw GadgetNotFoundException if absent. "
        "UPDATE replaces all editable fields: title and status are non-null, note is nullable and may clear. "
        "DELETE calls deleteById only after confirming existence. No cascading or extra business rules. "
        "Requests are already validated for field presence by the API boundary. "
        "Do not invent security, persistence schema, dependencies, endpoints or configuration. "
        "Permitted imports are java.util.List, java.util.UUID, java.util.Objects, java.util.Optional only.\n\n"
        + locked_fixture()
    )
    return json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": MAX_OUTPUT_TOKENS,
        "provider": {"allow_fallbacks": False},
        "temperature": 0,
        "stream": False,
    }, separators=(",", ":")).encode("utf-8")


def send_http(payload, key, deadline):
    request_started = time.monotonic()
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise QualificationFailure("OVERALL_DEADLINE")
    connection = http.client.HTTPSConnection(HOST, timeout=min(CONNECT_TIMEOUT, remaining))
    http_status = None
    try:
        connection.connect()
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise QualificationFailure("OVERALL_DEADLINE")
        response_deadline = min(deadline, request_started + RESPONSE_TIMEOUT)
        connection.sock.settimeout(min(RESPONSE_TIMEOUT, remaining))
        connection.request("POST", PATH, body=payload, headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
        })
        response = connection.getresponse()
        http_status = response.status
        chunks = []
        size = 0
        while True:
            remaining = response_deadline - time.monotonic()
            if remaining <= 0:
                raise RetryableTransport(http_status)
            connection.sock.settimeout(remaining)
            chunk = response.read(min(4096, MAX_RESPONSE_BYTES + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
            if size > MAX_RESPONSE_BYTES:
                raise QualificationFailure("RESPONSE_TOO_LARGE", http_status=http_status)
        return response.status, b"".join(chunks)
    except (OSError, socket.timeout, http.client.HTTPException):
        raise RetryableTransport(http_status) from None
    finally:
        connection.close()


def call_bounded(payload, key, transport=send_http, clock=time.monotonic, sleep=time.sleep,
                 on_attempt=lambda n: None, on_outcome=lambda event: None, deadline=None):
    if deadline is None:
        deadline = clock() + OVERALL_DEADLINE
    attempts = 0
    while attempts < MAX_REQUESTS:
        if clock() >= deadline:
            raise QualificationFailure("OVERALL_DEADLINE")
        attempts += 1
        on_attempt(attempts)
        try:
            status, body = transport(payload, key, deadline)
        except RetryableTransport as failure:
            on_outcome({"request": attempts, "httpStatus": failure.http_status}
                       if failure.http_status is not None else
                       {"request": attempts, "transportError": "NETWORK_OR_TIMEOUT"})
            if attempts == MAX_REQUESTS:
                raise QualificationFailure("TRANSPORT_FAILURE") from None
        except QualificationFailure as failure:
            if failure.http_status is not None:
                on_outcome({"request": attempts, "httpStatus": failure.http_status})
            raise
        else:
            on_outcome({"request": attempts, "httpStatus": status})
            if status == 200:
                if clock() >= deadline:
                    raise QualificationFailure("OVERALL_DEADLINE")
                return body, attempts
            if status not in (429,) and not 500 <= status <= 599:
                raise QualificationFailure("MODEL_UNAVAILABLE" if status in (400, 404) else "HTTP_REJECTED")
            if attempts == MAX_REQUESTS:
                raise QualificationFailure("RETRY_EXHAUSTED")
        if clock() + RETRY_DELAY >= deadline:
            raise QualificationFailure("OVERALL_DEADLINE")
        sleep(RETRY_DELAY)
    raise QualificationFailure("RETRY_EXHAUSTED")


def child_env(environ=None):
    """Only platform process-launch variables cross into trusted helper processes."""
    source = os.environ if environ is None else environ
    return {name: source[name] for name in CHILD_ENV_KEYS if name in source}


def http_worker_main():
    """A killable worker bounds DNS and socket stalls as well as ordinary retries."""
    requests = 0
    http_attempts = []
    def attempted(number):
        nonlocal requests
        requests = number
    try:
        request = json.loads(sys.stdin.buffer.read(131073))
        payload = base64.b64decode(request["payload"], validate=True)
        key = request["key"]
        deadline = float(request["deadline"])
        body, requests = call_bounded(payload, key, deadline=deadline,
                                      on_attempt=attempted, on_outcome=http_attempts.append)
        result = {"status": "ok", "body": base64.b64encode(body).decode("ascii"),
                  "requests": requests, "httpAttempts": http_attempts}
    except QualificationFailure as failure:
        result = {"status": "failure", "code": failure.code, "requests": requests,
                  "httpAttempts": http_attempts}
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        result = {"status": "failure", "code": "TRANSPORT_FAILURE", "requests": requests,
                  "httpAttempts": http_attempts}
    sys.stdout.write(json.dumps(result, separators=(",", ":")))
    return 0


def run_http_bounded(payload, key, deadline, runner=subprocess.run, clock=time.monotonic,
                     on_outcomes=lambda events: None):
    remaining = deadline - clock()
    if remaining <= 0:
        raise QualificationFailure("OVERALL_DEADLINE")
    request = json.dumps({"payload": base64.b64encode(payload).decode("ascii"),
                          "key": key, "deadline": deadline}, separators=(",", ":")).encode("utf-8")
    try:
        result = runner([sys.executable, str(Path(__file__).resolve()), "--http-worker"],
                        input=request, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                        env=child_env(), timeout=remaining, check=False)
    except subprocess.TimeoutExpired:
        raise QualificationFailure("OVERALL_DEADLINE") from None
    except OSError:
        raise QualificationFailure("TRANSPORT_FAILURE") from None
    if clock() >= deadline:
        raise QualificationFailure("OVERALL_DEADLINE")
    if result.returncode != 0 or len(result.stdout) > MAX_RESPONSE_BYTES * 2:
        raise QualificationFailure("TRANSPORT_FAILURE")
    try:
        outcome = json.loads(result.stdout)
        count = outcome["requests"]
        if not isinstance(count, int) or not 0 <= count <= MAX_REQUESTS:
            raise ValueError()
        events = outcome["httpAttempts"]
        if not isinstance(events, list) or len(events) > count:
            raise ValueError()
        for number, event in enumerate(events, start=1):
            if not isinstance(event, dict) or event.get("request") != number:
                raise ValueError()
            if set(event) == {"request", "httpStatus"}:
                status = event["httpStatus"]
                if type(status) is not int or not 100 <= status <= 599:
                    raise ValueError()
            elif set(event) == {"request", "transportError"}:
                if event["transportError"] != "NETWORK_OR_TIMEOUT":
                    raise ValueError()
            else:
                raise ValueError()
        on_outcomes(events)
        if outcome["status"] == "failure":
            code = outcome["code"]
            if code not in {"OVERALL_DEADLINE", "TRANSPORT_FAILURE", "MODEL_UNAVAILABLE",
                            "HTTP_REJECTED", "RETRY_EXHAUSTED", "RESPONSE_TOO_LARGE"}:
                raise ValueError()
            raise QualificationFailure(code, count)
        if outcome["status"] != "ok":
            raise ValueError()
        return base64.b64decode(outcome["body"], validate=True), count
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        raise QualificationFailure("TRANSPORT_FAILURE") from None


def normalize_source(source):
    if "```" not in source:
        return source, "RAW_JAVA"
    wrapped = re.fullmatch(r"```(?:java)?[ \t]*\r?\n(.*?)\r?\n```[ \t]*",
                           source.strip(), flags=re.DOTALL)
    if wrapped is None or "```" in wrapped.group(1):
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="FORMAT_MARKDOWN_FENCE")
    return wrapped.group(1).strip(), "OUTER_JAVA_FENCE"


def parse_source(body, key, on_normalization=lambda code: None):
    try:
        document = json.loads(body)
        if document["model"] != MODEL:
            raise QualificationFailure("MODEL_MISMATCH")
        choice = document["choices"][0]
        if choice.get("finish_reason") != "stop":
            raise QualificationFailure("INCOMPLETE_OUTPUT")
        source = choice["message"]["content"]
        if not isinstance(source, str):
            raise ValueError()
        used = document.get("usage", {}).get("completion_tokens")
        if isinstance(used, int) and used > MAX_OUTPUT_TOKENS:
            raise QualificationFailure("TOKEN_LIMIT_EXCEEDED")
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError):
        raise QualificationFailure("INVALID_RESPONSE") from None
    source, normalization = normalize_source(source)
    on_normalization(normalization)
    if key in source:
        raise QualificationFailure("UNSAFE_SOURCE", boundary_reason="SECURITY_SOURCE_CONTAINS_KEY")
    if len(source.encode("utf-8")) > MAX_SOURCE_BYTES:
        raise QualificationFailure("UNSAFE_SOURCE", boundary_reason="SECURITY_SOURCE_TOO_LARGE")
    if not source.startswith("package qualification.gadget;"):
        reason = ("CONTRACT_PACKAGE_MISMATCH" if source.startswith("package ")
                  else "FORMAT_MISSING_PACKAGE_HEADER")
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason=reason)
    if any(name in source for name in ("AssetTicket", "WorkOrder", "ServiceRequest")):
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="CONTRACT_FOREIGN_DOMAIN")
    if re.search(r"\b(?:java\s*\.\s*lang\s*\.\s*)?System\s*\.\s*exit\s*\(", source) or \
            re.search(r"\b(?:java\s*\.\s*lang\s*\.\s*)?Runtime\s*\.\s*"
                      r"(?:getRuntime\s*\(\s*\)\s*\.\s*)?(?:exit|halt)\s*\(", source):
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="SECURITY_JVM_TERMINATION")
    if "System.getenv" in source:
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="SECURITY_ENV_ACCESS")
    if "ProcessBuilder" in source:
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="SECURITY_PROCESS_LAUNCH")
    if "Runtime.getRuntime" in source:
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="SECURITY_RUNTIME_ACCESS")
    imports = re.findall(r"(?m)^\s*import\s+([^;]+);", source)
    if any(name not in {"java.util.List", "java.util.UUID", "java.util.Objects", "java.util.Optional"} for name in imports):
        raise QualificationFailure("UNAPPROVED_IMPORT", boundary_reason="CONTRACT_IMPORT_NOT_ALLOWED")
    if not re.search(r"\bclass\s+GadgetServiceImpl\s+implements\s+GadgetService\b", source):
        raise QualificationFailure("CONTRACT_MISMATCH", boundary_reason="CONTRACT_IMPLEMENTATION_CLASS")
    for operation in ("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget"):
        if not re.search(r"\b" + operation + r"\s*\(", source):
            raise QualificationFailure("CONTRACT_MISMATCH", boundary_reason="CONTRACT_OPERATION_MISSING")
    if len(re.findall(r"\bclass\s+", source)) != 1:
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="CONTRACT_CLASS_COUNT")
    if re.search(r"\b(package|interface|enum|record)\s+", source[len("package qualification.gadget;"):]):
        raise QualificationFailure("OUTPUT_BOUNDARY", boundary_reason="CONTRACT_EXTRA_TYPE")
    return source


def remaining_seconds(deadline, clock=time.monotonic):
    remaining = deadline - clock()
    if remaining <= 0:
        raise QualificationFailure("OVERALL_DEADLINE")
    return remaining


def sandbox_image(deadline, clean_env, source_env, runner, clock):
    image = source_env.get(SANDBOX_IMAGE_ENV, "")
    if not IMAGE_ID_PATTERN.fullmatch(image):
        raise QualificationFailure("SANDBOX_UNAVAILABLE")
    try:
        inspected = runner(["docker", "image", "inspect", "--format", "{{.Id}} {{.Os}}", image],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           env=clean_env, timeout=remaining_seconds(deadline, clock), check=False)
    except subprocess.TimeoutExpired:
        raise QualificationFailure("OVERALL_DEADLINE") from None
    except OSError:
        raise QualificationFailure("SANDBOX_UNAVAILABLE") from None
    if inspected.returncode != 0 or inspected.stdout.decode("utf-8", errors="replace").strip() != image + " linux":
        raise QualificationFailure("SANDBOX_UNAVAILABLE")
    return image


def preflight_sandbox(output, deadline, runner=subprocess.run, clock=time.monotonic, environ=None):
    """Fail before an HTTP request when local execution isolation is unavailable."""
    source_env = os.environ if environ is None else environ
    with tempfile.TemporaryDirectory(prefix="sandbox-preflight-", dir=output) as directory:
        config = Path(directory) / "docker-config"
        config.mkdir()
        clean_env = child_env(source_env)
        clean_env["DOCKER_CONFIG"] = str(config)
        return sandbox_image(deadline, clean_env, source_env, runner, clock)


def compile_and_check(source, output, deadline, runner=subprocess.run, clock=time.monotonic,
                      environ=None):
    """Compile and execute only inside a local, pinned Linux container with no host/network access."""
    source_path = output / RELATIVE_SOURCE
    source_path.parent.mkdir(parents=True, exist_ok=True)
    source_path.write_text(source, encoding="utf-8")
    source_env = os.environ if environ is None else environ
    with tempfile.TemporaryDirectory(prefix="sandbox-input-", dir=output) as input_dir:
        input_path = Path(input_dir)
        docker_config = input_path / "docker-config"
        docker_config.mkdir()
        clean_env = child_env(source_env)
        clean_env["DOCKER_CONFIG"] = str(docker_config)
        image = sandbox_image(deadline, clean_env, source_env, runner, clock)

        fixture = ROOT / "qualification/gadget"
        for name in ("GadgetContract.java", "GadgetBehaviorCheck.java"):
            (input_path / name).write_bytes((fixture / name).read_bytes())
        (input_path / "GadgetServiceImpl.java").write_text(source, encoding="utf-8")
        container_name = "gsuif-qual-" + uuid.uuid4().hex
        completion_marker = "GSUIF_GADGET_CHECK_COMPLETE:" + uuid.uuid4().hex
        script = ("mkdir -p /work/classes || exit 20; "
                  "javac -proc:none -encoding UTF-8 -d /work/classes "
                  "/input/GadgetContract.java /input/GadgetServiceImpl.java "
                  "/input/GadgetBehaviorCheck.java >/dev/null 2>&1 || exit 20; "
                  "java -cp /work/classes qualification.gadget.GadgetBehaviorCheck "
                  + completion_marker.split(":", 1)[1] + " >/work/behavior.out 2>/dev/null || exit 21; "
                  "grep -Fxq '" + completion_marker + "' /work/behavior.out || exit 21")
        command = ["docker", "run", "--pull=never", "--name", container_name,
                   "--network=none", "--read-only", "--ipc=none", "--cap-drop=ALL",
                   "--security-opt=no-new-privileges", "--pids-limit=64", "--memory=256m",
                   "--cpus=1", "--user=65532:65532", "--log-driver=none",
                   "--tmpfs=/work:rw,exec,nosuid,size=64m,mode=1777",
                   "--tmpfs=/tmp:rw,noexec,nosuid,size=16m,mode=1777",
                   "--mount=type=bind,src=" + str(input_path) + ",dst=/input,readonly",
                   "--workdir=/work", "--env=HOME=/tmp", "--entrypoint=/bin/sh", image, "-c", script]
        try:
            result = runner(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            env=clean_env, timeout=remaining_seconds(deadline, clock), check=False)
        except subprocess.TimeoutExpired:
            raise QualificationFailure("OVERALL_DEADLINE") from None
        except OSError:
            raise QualificationFailure("SANDBOX_UNAVAILABLE") from None
        finally:
            # Killing the Docker client on timeout does not guarantee the container stopped.
            try:
                cleanup = runner(["docker", "rm", "-f", container_name], stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, env=clean_env, timeout=5, check=False)
            except (OSError, subprocess.TimeoutExpired):
                raise QualificationFailure("SANDBOX_TERMINATION_UNCONFIRMED") from None
            if cleanup.returncode != 0:
                raise QualificationFailure("SANDBOX_TERMINATION_UNCONFIRMED")
        if clock() >= deadline:
            raise QualificationFailure("OVERALL_DEADLINE")
        if result.returncode == 20:
            raise QualificationFailure("COMPILE_FAILED")
        if result.returncode == 21:
            return "FAILED"
        if result.returncode != 0:
            raise QualificationFailure("SANDBOX_UNAVAILABLE")
        return "PASSED"


def main():
    if sys.argv[1:] == ["--http-worker"]:
        return http_worker_main()
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", required=True)
    args = parser.parse_args()
    key = os.environ.get("OPENROUTER_API_KEY", "")
    if not key:
        print("Qualification blocked: key not available in this child process")
        return 2
    if os.environ.get("OPENROUTER_MODEL") != MODEL:
        print("Qualification blocked: OPENROUTER_MODEL must be qwen/qwen3-coder")
        return 2
    output = (ROOT / "target/openrouter-qualification/gadget/attempts" /
              (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex))
    output.mkdir(parents=True, exist_ok=False)
    report = {"model": MODEL, "contractVersion": "2.0.0", "requests": 0,
              "source": None, "compilation": "NOT_RUN", "behavior": "NOT_RUN", "status": "FAILED",
              "httpAttempts": []}
    deadline = time.monotonic() + OVERALL_DEADLINE
    try:
        preflight_sandbox(output, deadline)
        payload = request_body()
        body, attempts = run_http_bounded(payload, key, deadline,
                                          on_outcomes=lambda events: report.update(httpAttempts=events))
        report["requests"] = attempts
        source = parse_source(body, key,
                              on_normalization=lambda code: report.update(normalization=code))
        remaining_seconds(deadline)
        report["source"] = str((output / RELATIVE_SOURCE).resolve())
        report["behavior"] = compile_and_check(source, output, deadline)
        report["compilation"] = "PASSED"
        report["status"] = "PASSED" if report["behavior"] == "PASSED" else "BEHAVIOR_FAILED"
    except QualificationFailure as failure:
        report["status"] = failure.code
        if failure.boundary_reason in BOUNDARY_REASONS:
            report["boundaryCategory"] = BOUNDARY_REASONS[failure.boundary_reason]
            report["boundaryReason"] = failure.boundary_reason
        if failure.requests is not None:
            report["requests"] = failure.requests
        elif failure.code == "OVERALL_DEADLINE" and report["requests"] == 0:
            report["requests"] = None
        if failure.code == "COMPILE_FAILED":
            report["compilation"] = "FAILED"
    finally:
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("Qualification " + report["status"] + "; report: " + str(output / "result.json"))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
