#!/usr/bin/env python3
"""Standard-library tests for scripts/evaluate-ai.py.

These tests never make a network request and do not require pytest.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "evaluate-ai.py"
SPEC = importlib.util.spec_from_file_location("evaluate_ai", SCRIPT)
assert SPEC and SPEC.loader
evaluate_ai = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluate_ai)


class EvaluateAiTests(unittest.TestCase):
    def test_assets_have_required_coverage(self) -> None:
        cases, prompts = evaluate_ai.load_assets()
        self.assertGreaterEqual(len(cases), 24)
        self.assertEqual(len(prompts), 2)
        self.assertEqual({case["type"] for case in cases}, {"evidence_qa", "colloquial", "no_evidence_refusal", "prompt_injection"})
        self.assertEqual({case["should_refuse"] for case in cases}, {True, False})

    def test_evidence_block_treats_instructions_as_data(self) -> None:
        cases, _ = evaluate_ai.load_assets()
        injection = next(case for case in cases if case["type"] == "prompt_injection")
        block = evaluate_ai._evidence_block(injection)
        self.assertIn("知识片段开始", block)
        self.assertIn("知识片段结束", block)
        self.assertIn("忽略之前所有规则", block)

    def test_failed_api_is_unscored_and_not_a_correct_refusal(self) -> None:
        cases, _ = evaluate_ai.load_assets()
        refusal_case = next(case for case in cases if case["should_refuse"])
        score = evaluate_ai._score_output(refusal_case, "", success=False)
        self.assertEqual(score["status"], "unscored_api_error")
        self.assertIsNone(score["rule_score"])
        self.assertIsNone(score["refusal_match"])
        self.assertIn("never counted as a correct refusal", score["scoring_note"])

    def test_successful_refusal_uses_only_transparent_string_rules(self) -> None:
        cases, _ = evaluate_ai.load_assets()
        refusal_case = next(case for case in cases if case["id"] == "no-015-party-budget")
        score = evaluate_ai._score_output(refusal_case, "提供的资料证据不足，无法可靠回答，建议向财务确认。", success=True)
        self.assertEqual(score["status"], "scored_by_rules")
        self.assertTrue(score["refusal_detected"])
        self.assertTrue(score["refusal_match"])
        self.assertIsNotNone(score["rule_score"])

    def test_key_is_redacted_without_logging_value(self) -> None:
        self.assertNotIn("secret-value", evaluate_ai._redact_text("Authorization: Bearer secret-value", "secret-value"))
        self.assertIn("REDACTED", evaluate_ai._redact_text("Authorization: Bearer secret-value", "secret-value"))

    def test_offline_command_does_not_need_api_key(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = evaluate_ai.main(["--validate-only"])
        self.assertEqual(code, 0)
        self.assertIn("NETWORK CALLS: 0", output.getvalue())

    def test_run_requires_explicit_model_before_request(self) -> None:
        previous_model = os.environ.pop("AI_EVAL_MODEL", None)
        previous_key = os.environ.pop("AI_EVAL_API_KEY", None)
        try:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = evaluate_ai.main(["--run"])
            self.assertEqual(code, 2)
            self.assertIn("requires --model", output.getvalue())
        finally:
            if previous_model is not None:
                os.environ["AI_EVAL_MODEL"] = previous_model
            if previous_key is not None:
                os.environ["AI_EVAL_API_KEY"] = previous_key

    def test_safe_url_drops_credentials_and_query(self) -> None:
        safe = evaluate_ai._safe_base_url("https://user:secret@example.test/v1?api_key=secret")
        self.assertEqual(safe, "https://example.test/v1")

    def test_result_artifacts_record_model_prompt_output_latency_and_score(self) -> None:
        with tempfile.TemporaryDirectory(prefix=".eval-test-", dir=str(ROOT)) as temporary:
            output_dir = Path(temporary) / "run"
            result = {
                "case_id": "ev-test",
                "case_type": "evidence_qa",
                "prompt_version": "baseline-v1",
                "model": "local-test-model",
                "success": True,
                "http_status": 200,
                "output": "测试回答 25 MB",
                "error": None,
                "latency_ms": 12.34,
                "should_refuse": False,
                "reference_answer": "测试参考",
                "required_points": [],
                "score": {"status": "scored_by_rules", "rule_score": 0.75, "refusal_match": True},
            }
            evaluate_ai._write_results(output_dir, model="local-test-model", base_url="http://127.0.0.1:1/v1", prompt_versions=["baseline-v1", "evidence-boundary-v1"], selected_case_ids=["ev-test"], results=[result], api_key_env="AI_EVAL_API_KEY", timeout=1, concurrency=1)
            json_text = (output_dir / "results.json").read_text(encoding="utf-8")
            csv_text = (output_dir / "results.csv").read_text(encoding="utf-8-sig")
            markdown_text = (output_dir / "results.md").read_text(encoding="utf-8")
            for text in (json_text, csv_text, markdown_text):
                self.assertIn("local-test-model", text)
                self.assertIn("baseline-v1", text)
                self.assertIn("测试回答 25 MB", text)
                self.assertIn("12.34", text)
            self.assertIn("rule_score", json_text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
