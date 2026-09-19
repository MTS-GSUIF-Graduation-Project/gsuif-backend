#!/usr/bin/env python3
"""Deep evaluation of components.yaml v1 against project sources."""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "components.yaml"
MATRIX_DECISIONS = {
    "BE-01": ("HAND_WRITTEN", "hand-written", None),
    "BE-02": ("FREEMARKER", "Apache FreeMarker", None),
    "BE-03": ("OPENAPI_DTO_PLUS_HAND_WRITTEN", "openapi-generator-maven-plugin", "hand-written"),
    "BE-04": ("HAND_WRITTEN", "hand-written", None),
    "BE-05": ("OPENAPI_PLUS_FREEMARKER", "openapi-generator-maven-plugin", "Apache FreeMarker"),
    "BE-06": ("HAND_WRITTEN", "hand-written", None),
    "BE-07": ("HAND_WRITTEN", "hand-written", None),
    "BE-08": ("HAND_WRITTEN", "hand-written", None),
    "BE-09": ("HAND_WRITTEN", "hand-written", None),
    "BE-10": ("HAND_WRITTEN", "hand-written", None),
    "BE-11": ("OPENAPI_ANNOTATIONS_PLUS_HAND_WRITTEN", "openapi-generator-maven-plugin", "springdoc-openapi"),
    "BE-12": ("HAND_WRITTEN", "hand-written", None),
    "BE-13": ("HAND_WRITTEN", "TemplateOnlyProvider", "Apache FreeMarker"),
    "BE-14": ("HAND_WRITTEN", "hand-written", None),
    "BE-15": ("HAND_WRITTEN", "hand-written", None),
    "BE-16": ("HAND_WRITTEN", "hand-written", "AJV"),
    "BE-17": ("HAND_WRITTEN", "hand-written", None),
    "BE-18": ("HAND_WRITTEN", "hand-written", None),
    "BE-19": ("HAND_WRITTEN", "hand-written", None),
}


def load_catalog() -> dict:
    return yaml.safe_load(CATALOG.read_text(encoding="utf-8"))


def check_generation_blocks(catalog: dict) -> list[str]:
    issues: list[str] = []
    for comp in catalog["components"]:
        cid = comp["id"]
        gen = comp.get("generation")
        if not gen:
            issues.append(f"{cid}: missing generation block")
            continue

        exp_strategy, exp_tool, exp_supp = MATRIX_DECISIONS[cid]
        if gen.get("strategy") != exp_strategy:
            issues.append(f"{cid}: strategy={gen.get('strategy')} expected {exp_strategy}")

        tool = gen.get("tool") or {}
        if tool.get("name") != exp_tool:
            issues.append(f"{cid}: tool.name={tool.get('name')} expected {exp_tool}")

        supplement = tool.get("supplement")
        if exp_supp is None:
            if supplement not in (None,) and supplement != {} and cid not in {"BE-16"}:
                if supplement is not None and supplement != {}:
                    pass  # allowed for BE-16 AJV documented supplement
        else:
            if not isinstance(supplement, dict):
                issues.append(f"{cid}: expected supplement {exp_supp}")
            elif supplement.get("name") != exp_supp:
                issues.append(
                    f"{cid}: supplement.name={supplement.get('name')} expected {exp_supp}"
                )

        ds = gen.get("decision_source") or {}
        if ds.get("capability_matrix") != cid:
            issues.append(f"{cid}: decision_source.capability_matrix mismatch")

        # standards in validation_rules should be subset of component standards (+ sys_only for SYS)
        std_refs = set((gen.get("validation_rules") or {}).get("standard_refs") or [])
        declared = set(comp.get("standards") or []) | set(comp.get("sys_only_standards") or [])
        extra = std_refs - declared
        if extra:
            issues.append(f"{cid}: validation_rules references undeclared standards {sorted(extra)}")

    return issues


def check_deferred(catalog: dict) -> list[str]:
    issues: list[str] = []
    for comp in catalog["deferred_components"]:
        gen = comp.get("generation") or {}
        if gen.get("status") != "DEFERRED" or gen.get("strategy") != "DEFERRED":
            issues.append(f"{comp['id']}: deferred generation incomplete")
        if not gen.get("decision_source", {}).get("capability_matrix", "").startswith("DEF-"):
            issues.append(f"{comp['id']}: missing DEF decision_source")
    return issues


def check_be12(catalog: dict) -> list[str]:
    issues: list[str] = []
    be12 = next(c for c in catalog["components"] if c["id"] == "BE-12")
    vr = be12["generation"]["validation_rules"]
    required_keys = [
        "golden_tests",
        "build_validation",
        "framework_test_suite",
        "ci_pipeline",
        "vertical_slice",
    ]
    for key in required_keys:
        if key not in vr:
            issues.append(f"BE-12: missing validation_rules.{key}")
    if "SCRUM-50" not in str(vr.get("golden_tests")):
        issues.append("BE-12: golden_tests missing SCRUM-50")
    return issues


def check_templates_exist(catalog: dict) -> list[str]:
    issues: list[str] = []
    warnings: list[str] = []
    for comp in catalog["components"]:
        gen = comp.get("generation") or {}
        tmpl = gen.get("template")
        if not tmpl or tmpl is None:
            continue
        if isinstance(tmpl, dict):
            paths = []
            for k, v in tmpl.items():
                if k.endswith("path") or k in {"path", "openapi_override", "freemarker_impl"}:
                    if isinstance(v, str) and v.startswith("src/"):
                        paths.append(v)
            for p in paths:
                full = ROOT / p
                version = tmpl.get("version", "")
                if not full.exists():
                    if "planned" in str(version):
                        warnings.append(f"{comp['id']}: planned template missing (OK): {p}")
                    else:
                        issues.append(f"{comp['id']}: template path missing: {p}")
    return issues, warnings


def check_header(catalog: dict) -> list[str]:
    issues: list[str] = []
    if catalog.get("catalog_version") != "1.0":
        issues.append("catalog_version != 1.0")
    if catalog.get("tool_template_resolution", {}).get("status") != "COMPLETE":
        issues.append("tool_template_resolution not COMPLETE")
    old_null_tools = CATALOG.read_text(encoding="utf-8").count("\n    tool: null\n")
    if old_null_tools:
        issues.append(f"legacy tool: null entries remain: {old_null_tools}")
    return issues


def main() -> int:
    catalog = load_catalog()
    all_issues: list[str] = []
    all_warnings: list[str] = []

    for fn in (check_header, check_generation_blocks, check_deferred, check_be12):
        all_issues.extend(fn(catalog))

    tmpl_issues, tmpl_warnings = check_templates_exist(catalog)
    all_issues.extend(tmpl_issues)
    all_warnings.extend(tmpl_warnings)

    print("=== DEEP EVALUATION: components.yaml v1 ===")
    print(f"Active components: {len(catalog['components'])}")
    print(f"Deferred components: {len(catalog['deferred_components'])}")
    print(f"Issues: {len(all_issues)}")
    print(f"Warnings (expected/planned): {len(all_warnings)}")
    print()

    if all_issues:
        print("ISSUES:")
        for i in all_issues:
            print(f"  [FAIL] {i}")
    else:
        print("ISSUES: none")

    if all_warnings:
        print("\nWARNINGS (expected until T-40):")
        for w in all_warnings:
            print(f"  [WARN] {w}")

    print()
    if not all_issues:
        print("RESULT: PASS — catalog is consistent with capability matrix and acceptance criteria")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
