#!/usr/bin/env python3
"""Validate components.yaml (T-17) against capability-matrix.md decisions."""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "components.yaml"

EXPECTED_STRATEGY = {
    "BE-01": "HAND_WRITTEN",
    "BE-02": "FREEMARKER",
    "BE-03": "OPENAPI_DTO_PLUS_HAND_WRITTEN",
    "BE-04": "HAND_WRITTEN",
    "BE-05": "OPENAPI_PLUS_FREEMARKER",
    "BE-06": "HAND_WRITTEN",
    "BE-07": "HAND_WRITTEN",
    "BE-08": "HAND_WRITTEN",
    "BE-09": "HAND_WRITTEN",
    "BE-10": "HAND_WRITTEN",
    "BE-11": "OPENAPI_ANNOTATIONS_PLUS_HAND_WRITTEN",
    "BE-12": "HAND_WRITTEN",
    "BE-13": "HAND_WRITTEN",
    "BE-14": "HAND_WRITTEN",
    "BE-15": "HAND_WRITTEN",
    "BE-16": "HAND_WRITTEN",
    "BE-17": "HAND_WRITTEN",
    "BE-18": "HAND_WRITTEN",
    "BE-19": "HAND_WRITTEN",
}

OPENAPI_VERSION = "7.16.0"
FREEMARKER_VERSION = "2.3.34"


def main() -> int:
    catalog = yaml.safe_load(CATALOG.read_text(encoding="utf-8"))
    issues: list[str] = []

    if catalog.get("catalog_version") != "1.0":
        issues.append(f"catalog_version={catalog.get('catalog_version')} expected 1.0")

    resolution = catalog.get("tool_template_resolution", {})
    if resolution.get("status") != "COMPLETE":
        issues.append("tool_template_resolution.status is not COMPLETE")

    for comp in catalog["components"]:
        cid = comp["id"]
        gen = comp.get("generation")
        if not gen:
            issues.append(f"{cid}: missing generation block")
            continue

        tool = gen.get("tool") or {}
        if not tool.get("name"):
            issues.append(f"{cid}: blank tool.name")
        if not gen.get("status"):
            issues.append(f"{cid}: blank status")
        if not gen.get("strategy"):
            issues.append(f"{cid}: blank strategy")

        expected = EXPECTED_STRATEGY.get(cid)
        if expected and gen.get("strategy") != expected:
            issues.append(
                f"{cid}: strategy {gen.get('strategy')} != capability-matrix {expected}"
            )

        if "openapi-generator" in str(tool.get("name", "")):
            if tool.get("version") != OPENAPI_VERSION:
                issues.append(f"{cid}: openapi version {tool.get('version')}")

        for node in (tool, tool.get("supplement") or {}):
            if isinstance(node, dict) and node.get("name") == "Apache FreeMarker":
                if node.get("version") != FREEMARKER_VERSION:
                    issues.append(f"{cid}: FreeMarker version {node.get('version')}")

        ds = gen.get("decision_source") or {}
        if not ds.get("capability_matrix"):
            issues.append(f"{cid}: missing decision_source.capability_matrix")

    for comp in catalog["deferred_components"]:
        cid = comp["id"]
        gen = comp.get("generation")
        if not gen or gen.get("status") != "DEFERRED":
            issues.append(f"{cid}: deferred generation block incomplete")

    print(f"Active components: {len(catalog['components'])}")
    print(f"Deferred components: {len(catalog['deferred_components'])}")
    print(f"Issues: {len(issues)}")
    for issue in issues:
        print(f"  - {issue}")

    if not issues:
        print("PASS: T-17 catalog validation")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
