#!/usr/bin/env python3
"""Measures whether Claude picks query_research_corpus correctly, against a
hand-labeled set of prompts (JUA-55).

Discovers the tool's live schema from a running MCP gateway (initialize ->
notifications/initialized -> tools/list over Streamable HTTP) rather than a
hardcoded copy, so the eval always exercises the description actually being
served. Then calls the Anthropic Messages API with that tool attached for
each labeled prompt and records whether Claude chose to call it.

Same methodology as ai-research-assistant's eval_golden.py and
ai-agent-module's eval_classification.py: per-question pass/fail, a
per-trap-class breakdown, and a JSON artifact. Uses only the Python standard
library, matching this repo's existing quality/*.py scripts.

Requires a running gateway (see repo README's demo stack / `docker compose
up`) and an ANTHROPIC_API_KEY, either in the environment or in a .env file
(--env-file; defaults to ../ai-research-assistant/.env, a sibling checkout).

Usage:
    python3 eval_tool_selection.py
    python3 eval_tool_selection.py --gateway-url http://localhost:8080/mcp
    python3 eval_tool_selection.py --ids t01,t17
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_DATASET = Path(__file__).parent / "tool_selection_set.json"
DEFAULT_OUT = Path(__file__).parent / "eval_results" / "tool_selection_results.json"
DEFAULT_ENV_FILE = Path(__file__).resolve().parents[2] / "ai-research-assistant" / ".env"
DEFAULT_GATEWAY_URL = "http://localhost:8080/mcp"
DEFAULT_GATEWAY_TOKEN = "local-demo-token"
DEFAULT_MODEL = "claude-haiku-4-5-20251001"
TOOL_NAME = "query_research_corpus"
MCP_PROTOCOL_VERSION = "2025-06-18"
ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
ANTHROPIC_VERSION = "2023-06-01"
REQUEST_TIMEOUT_SECONDS = 30


class McpDiscoveryError(Exception):
    """Raised when the live tool schema can't be fetched from the gateway."""


def load_env_file(path: Path) -> dict[str, str]:
    """Parse simple KEY=VALUE lines from a .env file; ignore comments/blanks."""
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def parse_mcp_response(content_type: str, raw_body: str) -> dict:
    """Parse an MCP JSON-RPC response body, whether sent as plain JSON or as
    a single Streamable HTTP SSE frame (observed: tools/list responds via
    SSE, initialize responds with plain JSON)."""
    if "text/event-stream" in content_type:
        data_lines = [line for line in raw_body.splitlines() if line.startswith("data:")]
        if not data_lines:
            raise McpDiscoveryError(f"no 'data:' line in SSE response: {raw_body!r}")
        return json.loads(data_lines[0][len("data:"):].strip())
    if not raw_body:
        return {}
    return json.loads(raw_body)


def find_tool(tools: list[dict], name: str) -> dict:
    for tool in tools:
        if tool.get("name") == name:
            return tool
    raise McpDiscoveryError(f"tool '{name}' not found in tools/list result: {tools}")


def to_anthropic_tool_spec(mcp_tool: dict) -> dict:
    """Map an MCP tools/list entry to the Anthropic Messages API tool shape."""
    return {
        "name": mcp_tool["name"],
        "description": mcp_tool["description"],
        "input_schema": mcp_tool["inputSchema"],
    }


def _mcp_post(url: str, headers: dict, payload: dict) -> tuple[dict, dict]:
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers=headers, method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw_body = response.read().decode()
            response_headers = dict(response.headers)
    except urllib.error.URLError as exc:
        raise McpDiscoveryError(f"request to {url} failed: {exc}") from exc
    content_type = response_headers.get("Content-Type", "")
    return parse_mcp_response(content_type, raw_body), response_headers


def discover_tool(gateway_url: str, token: str, tool_name: str) -> dict:
    """Fetch a tool's live schema from a running MCP gateway via Streamable
    HTTP: initialize -> notifications/initialized -> tools/list. This is what
    a real MCP client does, so the eval exercises the tool description that
    is actually being served, never a hand-copied one."""
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }

    init_result, response_headers = _mcp_post(gateway_url, headers, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": MCP_PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "jua55-tool-selection-eval", "version": "0.1"},
        },
    })
    if "error" in init_result:
        raise McpDiscoveryError(f"initialize failed: {init_result['error']}")

    session_id = response_headers.get("Mcp-Session-Id")
    if not session_id:
        raise McpDiscoveryError("gateway did not return an Mcp-Session-Id header")
    session_headers = {**headers, "Mcp-Session-Id": session_id}

    notify_request = urllib.request.Request(
        gateway_url,
        data=json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}).encode(),
        headers=session_headers, method="POST",
    )
    try:
        with urllib.request.urlopen(notify_request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            response.read()
    except urllib.error.URLError as exc:
        raise McpDiscoveryError(f"notifications/initialized failed: {exc}") from exc

    list_result, _ = _mcp_post(gateway_url, session_headers, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/list",
    })
    if "error" in list_result:
        raise McpDiscoveryError(f"tools/list failed: {list_result['error']}")

    tools = list_result.get("result", {}).get("tools", [])
    return find_tool(tools, tool_name)


def call_claude(question: str, tool_spec: dict, api_key: str, model: str) -> bool:
    """Send one labeled question to Claude with the live tool attached.
    Returns True iff Claude's response includes a tool_use block for it."""
    payload = {
        "model": model,
        "max_tokens": 300,
        "temperature": 0,  # deterministic tool-choice, not creative sampling
        "tools": [tool_spec],
        "messages": [{"role": "user", "content": question}],
    }
    request = urllib.request.Request(
        ANTHROPIC_API_URL,
        data=json.dumps(payload).encode(),
        headers={
            "x-api-key": api_key,
            "anthropic-version": ANTHROPIC_VERSION,
            "content-type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        body = json.loads(response.read().decode())
    return any(
        block.get("type") == "tool_use" and block.get("name") == tool_spec["name"]
        for block in body.get("content", [])
    )


def verdict(expected_tool_call: bool, actual_tool_call: bool) -> str:
    return "pass" if expected_tool_call == actual_tool_call else "fail"


def compute_stats(results: list[dict]) -> dict:
    """Pure aggregation over eval results. Accuracy is computed only over
    scored (pass/fail) entries — an 'error' entry means the question was
    never actually evaluated and must not silently count as a fail, or
    inflate the denominator as if it were."""
    scored = [r for r in results if r["verdict"] != "error"]
    errored = [r for r in results if r["verdict"] == "error"]
    correct = sum(r["verdict"] == "pass" for r in scored)

    by_trap: dict[str, dict] = {}
    for r in scored:
        trap = by_trap.setdefault(r["trap_class"], {"correct": 0, "total": 0})
        trap["total"] += 1
        if r["verdict"] == "pass":
            trap["correct"] += 1

    return {
        "total_questions": len(results),
        "evaluated": len(scored),
        "correct": correct,
        "accuracy": correct / len(scored) if scored else 0.0,
        "by_trap": by_trap,
        "misroutes": [r for r in scored if r["verdict"] == "fail"],
        "errored": errored,
    }


def exit_code_for(results: list[dict]) -> int:
    """Non-zero whenever any question was not actually evaluated, so a run
    where every request errors out can never look like a clean pass."""
    return 1 if any(r["verdict"] == "error" for r in results) else 0


def print_summary(results: list[dict]) -> None:
    stats = compute_stats(results)
    print(f"\nOverall accuracy: {stats['correct']}/{stats['evaluated']} evaluated "
          f"({stats['accuracy']:.1%}) — {stats['total_questions']} total, "
          f"{len(stats['errored'])} errored\n")

    print("Per-trap-class accuracy (evaluated only):")
    for trap in sorted(stats["by_trap"]):
        trap_stats = stats["by_trap"][trap]
        trap_accuracy = trap_stats["correct"] / trap_stats["total"] if trap_stats["total"] else 0.0
        print(f"  {trap:<28} {trap_stats['correct']}/{trap_stats['total']} ({trap_accuracy:.1%})")

    if stats["misroutes"]:
        print(f"\nMisroutes ({len(stats['misroutes'])}):")
        for r in stats["misroutes"]:
            print(f"  [{r['id']}] {r['trap_class']}: expected_tool_call={r['expected_tool_call']} "
                  f"actual_tool_call={r['actual_tool_call']}")
            print(f"       {r['question']}")

    if stats["errored"]:
        print(f"\nErrors ({len(stats['errored'])}) — NOT included in accuracy above:")
        for r in stats["errored"]:
            print(f"  [{r['id']}] {r['trap_class']}: {r['error']}")
            print(f"       {r['question']}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Measure whether Claude picks query_research_corpus correctly."
    )
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--gateway-url", default=DEFAULT_GATEWAY_URL,
                         help=f"MCP Streamable HTTP endpoint (default: {DEFAULT_GATEWAY_URL})")
    parser.add_argument("--gateway-token", default=DEFAULT_GATEWAY_TOKEN,
                         help="Bearer token for the gateway (default: local-demo-token)")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE,
                         help=f"Fallback source for ANTHROPIC_API_KEY (default: {DEFAULT_ENV_FILE})")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--ids", type=str, default=None,
                         help="Comma-separated question ids to run, e.g. t01,t17")
    args = parser.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY") or load_env_file(args.env_file).get("ANTHROPIC_API_KEY")
    if not api_key:
        print(f"ANTHROPIC_API_KEY not found in the environment or in {args.env_file}")
        return 1

    if not args.dataset.exists():
        print(f"Dataset not found: {args.dataset}")
        return 1
    data = json.loads(args.dataset.read_text())
    questions = data["questions"]

    if args.ids:
        id_filter = {qid.strip() for qid in args.ids.split(",") if qid.strip()}
        questions = [q for q in questions if q["id"] in id_filter]
        missing = id_filter - {q["id"] for q in questions}
        if missing:
            print(f"WARNING: ids not found in dataset: {sorted(missing)}")
        if not questions:
            print("No matching questions for --ids. Nothing to evaluate.")
            return 1

    print(f"Discovering '{TOOL_NAME}' from {args.gateway_url} ...")
    try:
        mcp_tool = discover_tool(args.gateway_url, args.gateway_token, TOOL_NAME)
    except McpDiscoveryError as exc:
        print(f"Tool discovery failed: {exc}")
        print("Is the gateway running? See README's demo stack (`docker compose up`).")
        return 1
    tool_spec = to_anthropic_tool_spec(mcp_tool)
    print(f"Discovered tool description ({len(tool_spec['description'])} chars).\n")

    results = []
    for i, q in enumerate(questions, start=1):
        print(f"[{i}/{len(questions)}] {q['id']} ({q['trap_class']:<26}) ... ", end="", flush=True)
        try:
            actual = call_claude(q["question"], tool_spec, api_key, args.model)
            entry = {
                "id": q["id"],
                "question": q["question"],
                "trap_class": q["trap_class"],
                "expected_tool_call": q["expected_tool_call"],
                "actual_tool_call": actual,
                "verdict": verdict(q["expected_tool_call"], actual),
            }
        except (urllib.error.URLError, json.JSONDecodeError) as exc:
            entry = {
                "id": q["id"],
                "question": q["question"],
                "trap_class": q["trap_class"],
                "expected_tool_call": q["expected_tool_call"],
                "actual_tool_call": None,
                "verdict": "error",
                "error": str(exc),
            }
        results.append(entry)
        print(entry["verdict"].upper())

    print_summary(results)

    stats = compute_stats(results)
    output = {
        "run_at": datetime.now(timezone.utc).isoformat(),
        "dataset": str(args.dataset),
        "gateway_url": args.gateway_url,
        "model": args.model,
        "tool_description": tool_spec["description"],
        "total_questions": stats["total_questions"],
        "evaluated": stats["evaluated"],
        "errors": len(stats["errored"]),
        "correct": stats["correct"],
        "accuracy": stats["accuracy"],
        "results": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(output, indent=2))
    print(f"\nResults written to {args.out}")

    code = exit_code_for(results)
    if code != 0:
        print(f"\n{len(stats['errored'])} of {stats['total_questions']} questions could not be "
              f"evaluated due to errors — exiting non-zero. The accuracy above is partial, not a "
              f"completed run.")
    return code


if __name__ == "__main__":
    sys.exit(main())
