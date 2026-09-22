#!/usr/bin/env python3
"""Measures whether Claude picks query_research_corpus correctly, against a
hand-labeled set of prompts (JUA-55).

Discovers the tool's live schema from a running MCP gateway using the
official MCP Python SDK's Streamable HTTP client, rather than a hardcoded
copy, so the eval always exercises the description actually being served.
Then calls the Anthropic Messages API (official SDK) with that tool
attached for each labeled prompt and records whether Claude chose to call
it.

Same methodology as ai-research-assistant's eval_golden.py and
ai-agent-module's eval_classification.py: per-question pass/fail, a
per-trap-class breakdown, and a JSON artifact.

Unlike quality/*.py, this script has two eval-only dependencies (the `mcp`
and `anthropic` SDKs — see requirements.txt) rather than being stdlib-only:
hand-rolling the Streamable HTTP session/SSE handshake and raw Anthropic
HTTP calls previously required as much test-covered infrastructure code as
the eval itself. They're pinned separately from the Java application and
don't affect it.

Requires a running gateway (see repo README's demo stack / `docker compose
up`) and an ANTHROPIC_API_KEY, either in the environment or in a .env file
(--env-file; defaults to ../ai-research-assistant/.env, a sibling checkout).

Usage:
    python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
    .venv/bin/python eval_tool_selection.py
    .venv/bin/python eval_tool_selection.py --gateway-url http://localhost:8080/mcp
    .venv/bin/python eval_tool_selection.py --ids t01,t17
"""
import argparse
import asyncio
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import anthropic
import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

DEFAULT_DATASET = Path(__file__).parent / "tool_selection_set.json"
DEFAULT_OUT = Path(__file__).parent / "eval_results" / "tool_selection_results.json"
DEFAULT_ENV_FILE = Path(__file__).resolve().parents[2] / "ai-research-assistant" / ".env"
DEFAULT_GATEWAY_URL = "http://localhost:8080/mcp"
DEFAULT_GATEWAY_TOKEN = "local-demo-token"
DEFAULT_MODEL = "claude-haiku-4-5-20251001"
TOOL_NAME = "query_research_corpus"
REQUEST_TIMEOUT_SECONDS = 30.0


class ToolDiscoveryError(Exception):
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


async def _discover_tool_async(gateway_url: str, token: str, tool_name: str) -> dict:
    async with httpx2.AsyncClient(
        headers={"Authorization": f"Bearer {token}"}, timeout=REQUEST_TIMEOUT_SECONDS,
    ) as http_client:
        async with streamable_http_client(gateway_url, http_client=http_client) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                result = await session.list_tools()
                for tool in result.tools:
                    if tool.name == tool_name:
                        return {
                            "name": tool.name,
                            "description": tool.description,
                            "input_schema": tool.input_schema,
                        }
    raise ToolDiscoveryError(f"tool '{tool_name}' not found on {gateway_url}")


def discover_tool(gateway_url: str, token: str, tool_name: str) -> dict:
    """Fetch a tool's live schema from a running MCP gateway via the
    official MCP SDK's Streamable HTTP client (initialize -> tools/list),
    so the eval exercises the tool description actually being served,
    never a hand-copied one. Wraps whatever the SDK/transport raises
    (connection refused, timeout, protocol errors) into one clear error."""
    try:
        return asyncio.run(_discover_tool_async(gateway_url, token, tool_name))
    except ToolDiscoveryError:
        raise
    except Exception as exc:  # noqa: BLE001 - one clear discovery-failure message for any transport/SDK error
        raise ToolDiscoveryError(f"discovery against {gateway_url} failed: {exc}") from exc


def call_claude(client: anthropic.Anthropic, question: str, tool_spec: dict, model: str) -> bool:
    """Send one labeled question to Claude with the live tool attached.
    Returns True iff Claude's response includes a tool_use block for it.

    The installed anthropic SDK's Messages.create no longer has a typed
    `temperature` parameter, but the API itself still accepts it — sent via
    `extra_body` per the SDK's migration guidance for parameters dropped
    from the typed signature. Pinned to 0 for deterministic tool-choice:
    the recorded 24/26 result was produced at temperature 0, and a
    borderline case was observed to flip without it."""
    response = client.messages.create(
        model=model,
        max_tokens=300,
        tools=[tool_spec],
        messages=[{"role": "user", "content": question}],
        timeout=REQUEST_TIMEOUT_SECONDS,
        extra_body={"temperature": 0},
    )
    return any(
        block.type == "tool_use" and getattr(block, "name", None) == tool_spec["name"]
        for block in response.content
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
        tool_spec = discover_tool(args.gateway_url, args.gateway_token, TOOL_NAME)
    except ToolDiscoveryError as exc:
        print(f"Tool discovery failed: {exc}")
        print("Is the gateway running? See README's demo stack (`docker compose up`).")
        return 1
    print(f"Discovered tool description ({len(tool_spec['description'])} chars).\n")

    client = anthropic.Anthropic(api_key=api_key)
    results = []
    for i, q in enumerate(questions, start=1):
        print(f"[{i}/{len(questions)}] {q['id']} ({q['trap_class']:<26}) ... ", end="", flush=True)
        try:
            actual = call_claude(client, q["question"], tool_spec, args.model)
            entry = {
                "id": q["id"],
                "question": q["question"],
                "trap_class": q["trap_class"],
                "expected_tool_call": q["expected_tool_call"],
                "actual_tool_call": actual,
                "verdict": verdict(q["expected_tool_call"], actual),
            }
        except anthropic.APIError as exc:
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
