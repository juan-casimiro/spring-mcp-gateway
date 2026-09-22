"""Unit tests for the network-free logic in eval_tool_selection.py: env-file
parsing, MCP response parsing (plain JSON vs. SSE), tool lookup/mapping, and
scoring. No live gateway or Anthropic call is made here."""
import json
import sys
import unittest
from pathlib import Path
from tempfile import NamedTemporaryFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from eval_tool_selection import (
    McpDiscoveryError,
    find_tool,
    load_env_file,
    parse_mcp_response,
    to_anthropic_tool_spec,
    verdict,
)


class LoadEnvFileTests(unittest.TestCase):
    def test_missing_file_returns_empty(self):
        self.assertEqual(load_env_file(Path("/nonexistent/.env")), {})

    def test_parses_key_value_and_skips_comments_and_blanks(self):
        with NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("# comment\n\nANTHROPIC_API_KEY=sk-test-value\nOTHER=\"quoted\"\n")
            path = Path(f.name)
        try:
            values = load_env_file(path)
            self.assertEqual(values["ANTHROPIC_API_KEY"], "sk-test-value")
            self.assertEqual(values["OTHER"], "quoted")
        finally:
            path.unlink()


class ParseMcpResponseTests(unittest.TestCase):
    def test_plain_json(self):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"ok": True}})
        self.assertEqual(
            parse_mcp_response("application/json", body),
            {"jsonrpc": "2.0", "id": 1, "result": {"ok": True}},
        )

    def test_empty_body_returns_empty_dict(self):
        self.assertEqual(parse_mcp_response("application/json", ""), {})

    def test_sse_single_data_line(self):
        payload = {"jsonrpc": "2.0", "id": 2, "result": {"tools": []}}
        raw = f"id:abc\nevent:message\ndata:{json.dumps(payload)}\n"
        self.assertEqual(parse_mcp_response("text/event-stream", raw), payload)

    def test_sse_without_data_line_raises(self):
        with self.assertRaises(McpDiscoveryError):
            parse_mcp_response("text/event-stream", "id:abc\nevent:message\n")


class FindToolTests(unittest.TestCase):
    def test_finds_matching_tool(self):
        tools = [{"name": "other"}, {"name": "query_research_corpus", "description": "x"}]
        self.assertEqual(find_tool(tools, "query_research_corpus")["description"], "x")

    def test_raises_when_tool_absent(self):
        with self.assertRaises(McpDiscoveryError):
            find_tool([{"name": "other"}], "query_research_corpus")


class ToAnthropicToolSpecTests(unittest.TestCase):
    def test_maps_mcp_fields_to_anthropic_shape(self):
        mcp_tool = {
            "name": "query_research_corpus",
            "description": "desc",
            "inputSchema": {"type": "object", "properties": {}},
            "annotations": {"title": ""},  # not part of the Anthropic tool shape
        }
        spec = to_anthropic_tool_spec(mcp_tool)
        self.assertEqual(spec, {
            "name": "query_research_corpus",
            "description": "desc",
            "input_schema": {"type": "object", "properties": {}},
        })


class VerdictTests(unittest.TestCase):
    def test_pass_when_expected_matches_actual(self):
        self.assertEqual(verdict(True, True), "pass")
        self.assertEqual(verdict(False, False), "pass")

    def test_fail_when_expected_does_not_match_actual(self):
        self.assertEqual(verdict(True, False), "fail")
        self.assertEqual(verdict(False, True), "fail")


if __name__ == "__main__":
    unittest.main()
