# Tool-selection eval (JUA-55)

Manual, run-on-demand check of whether Claude picks `query_research_corpus`
correctly — not a CI gate, mirroring how `eval_golden.py`
(`ai-research-assistant`) and `eval_classification.py` (`ai-agent-module`)
are also run on demand rather than wired into a pipeline.

Uses only the Python standard library, matching `quality/*.py`.

## What it does

1. Connects to a running gateway's MCP endpoint and discovers the live
   `query_research_corpus` schema (`initialize` -> `notifications/initialized`
   -> `tools/list`), so the eval always exercises the description actually
   being served, never a hand-copied one.
2. Sends each labeled prompt in `tool_selection_set.json` to Claude Haiku
   with that tool attached, and records whether Claude called it.
3. Scores against the label, prints a per-trap-class breakdown and any
   misroutes, and writes `eval_results/tool_selection_results.json`.

## Run it

Start the demo stack first (repo root):

```sh
docker compose up --build
```

Then, from this directory:

```sh
python3 eval_tool_selection.py
```

`ANTHROPIC_API_KEY` is read from the environment first, falling back to
`../../ai-research-assistant/.env` (a sibling checkout, `--env-file` to
override). The gateway URL/token and dataset can also be overridden — see
`python3 eval_tool_selection.py --help`.

## Tests

```sh
python3 -m unittest discover -s tests -v
```

Covers the network-free logic only (env-file parsing, MCP response parsing
for both plain-JSON and SSE replies, tool lookup, scoring). Live discovery
and the Claude calls are exercised by actually running the script above, not
by an automated test — there is no fake gateway/Anthropic server here, only
the real ones.
