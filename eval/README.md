# Tool-selection eval (JUA-55)

Manual, run-on-demand check of whether Claude picks `query_research_corpus`
correctly — not a CI gate, mirroring how `eval_golden.py`
(`ai-research-assistant`) and `eval_classification.py` (`ai-agent-module`)
are also run on demand rather than wired into a pipeline.

Two eval-only dependencies (see `requirements.txt`), unlike `quality/*.py`'s
stdlib-only scripts: the official MCP and Anthropic Python SDKs, pinned
separately and not touching the Java application.

## What it does

1. Connects to a running gateway's MCP endpoint and discovers the live
   `query_research_corpus` schema via the official MCP SDK's Streamable
   HTTP client, so the eval always exercises the description actually being
   served, never a hand-copied one.
2. Sends each labeled prompt in `tool_selection_set.json` to Claude Haiku
   (official Anthropic SDK) with that tool attached, and records whether
   Claude called it.
3. Scores against the label, prints a per-trap-class breakdown and any
   misroutes, and writes `eval_results/tool_selection_results.json`.

## Setup

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Run it

Start the demo stack first (repo root):

```sh
docker compose up --build
```

Then, from this directory:

```sh
.venv/bin/python eval_tool_selection.py
```

`ANTHROPIC_API_KEY` is read from the environment first, falling back to
`../../ai-research-assistant/.env` (a sibling checkout, `--env-file` to
override). The gateway URL/token and dataset can also be overridden — see
`.venv/bin/python eval_tool_selection.py --help`.

Each full run costs a handful of Anthropic API calls (26 questions by
default); use `--ids` to run a subset while iterating, e.g.
`--ids t01,t17`.

Tool-choice is pinned to `temperature=0` via `extra_body` (the installed
`anthropic` SDK dropped `temperature` from `Messages.create`'s typed
signature, but the API still accepts it that way) for a reproducible score.

## Tests

```sh
.venv/bin/python -m unittest discover -s tests -v
```

Covers the logic this project owns: env-file parsing, scoring, and the
error/accuracy separation (an errored request must not count toward
accuracy or silently exit 0 — see `compute_stats`/`exit_code_for`). The MCP
handshake and the Anthropic API calls are the official SDKs' job, not
re-tested here — exercised instead by actually running the script above
against a live gateway, not a fake one.
