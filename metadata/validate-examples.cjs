"use strict";

/**
 * T-13 local schema check (draft 2020-12 via Ajv 2020).
 * Does not replace SCRUM-27 / T-24 (networknt on the JVM).
 *
 * Valid examples: each file is { project, page, metadataVersion } and must pass.
 * Invalid fixtures: metadata/examples/invalid/*.json must be rejected.
 */

const fs = require("node:fs");
const path = require("node:path");
const Ajv2020 = require("ajv/dist/2020");
const addFormats = require("ajv-formats");

const root = __dirname;
const schemaDir = path.join(root, "schema");
const examplesDir = path.join(root, "examples");
const invalidDir = path.join(examplesDir, "invalid");

const schemaFiles = [
  "component.schema.json",
  "api-binding.schema.json",
  "project.schema.json",
  "page.schema.json",
  "metadata-version.schema.json",
  "component-1.1.0.schema.json",
  "page-1.1.0.schema.json",
  "metadata-version-1.1.0.schema.json",
  "visibility-rule-1.2.0.schema.json",
  "api-binding-1.2.0.schema.json",
  "page-1.2.0.schema.json",
  "metadata-version-1.2.0.schema.json",
];

const exampleFiles = ["simple.json", "one-to-many.json", "many-to-many.json", "many-to-many-1.2.0.json"];

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function formatErrors(errors) {
  return (errors || [])
    .map((error) => `  ${error.instancePath || "/"} ${error.message}`)
    .join("\n");
}

function validRelationships(record) {
  const componentIds = new Set(record.components.map((component) => component.id));
  const childIds = new Set();
  const edges = new Map();
  for (const binding of record.apiBindings) {
    for (const linked of binding.linkedComponentIds || []) {
      if (!componentIds.has(linked)) return false;
    }
    const parent = binding.parentComponentId;
    const children = binding.childComponentIds;
    if (parent === undefined && children === undefined) continue;
    if (!parent || !Array.isArray(children) || children.length === 0 || !componentIds.has(parent)) return false;
    for (const child of children) {
      if (!componentIds.has(child) || child === parent || childIds.has(child)) return false;
      childIds.add(child);
      if (!edges.has(parent)) edges.set(parent, []);
      edges.get(parent).push(child);
    }
  }
  const active = new Set(), done = new Set();
  function cyclic(node) {
    if (active.has(node)) return true;
    if (done.has(node)) return false;
    active.add(node);
    for (const child of edges.get(node) || []) if (cyclic(child)) return true;
    active.delete(node);
    done.add(node);
    return false;
  }
  return ![...edges.keys()].some(cyclic);
}

const ajv = new Ajv2020({
  allErrors: true,
  strict: true,
  // Visibility field comparisons deliberately accept any JSON scalar.
  allowUnionTypes: true,
});
addFormats(ajv);

for (const fileName of schemaFiles) {
  const schema = readJson(path.join(schemaDir, fileName));
  // The binding's ../ reference resolves to a root-relative URI in Ajv.
  // Register that alias while retaining the schema's classpath-compatible $id
  // and recursive # references used by the JVM validator.
  const key = fileName === "visibility-rule-1.2.0.schema.json"
    ? "/visibility-rule-1.2.0.schema.json"
    : undefined;
  ajv.addSchema(schema, key);
}

const validators = {
  project: ajv.getSchema("gsuif/project.schema.json"),
  page: ajv.getSchema("gsuif/page.schema.json"),
  component: ajv.getSchema("gsuif/component.schema.json"),
  apiBinding: ajv.getSchema("gsuif/api-binding.schema.json"),
  metadataVersion: ajv.getSchema("gsuif/metadata-version.schema.json"),
  component11: ajv.getSchema("gsuif/component-1.1.0.schema.json"),
  page11: ajv.getSchema("gsuif/page-1.1.0.schema.json"),
  metadataVersion11: ajv.getSchema("gsuif/metadata-version-1.1.0.schema.json"),
  apiBinding12: ajv.getSchema("gsuif/api-binding-1.2.0.schema.json"),
  page12: ajv.getSchema("gsuif/page-1.2.0.schema.json"),
  metadataVersion12: ajv.getSchema("gsuif/metadata-version-1.2.0.schema.json"),
};

if (Object.values(validators).some((validate) => !validate)) {
  console.error(
    "Failed to compile schemas. Check $id values (expected gsuif/*.schema.json)."
  );
  process.exit(1);
}

let failed = 0;

for (const fileName of exampleFiles) {
  const filePath = path.join(examplesDir, fileName);
  const bundle = readJson(filePath);
  const checks = [
    ["project", validators.project, bundle.project],
    ["page", fileName.includes("1.2.0") ? validators.page12 : validators.page, bundle.page],
    ["metadataVersion", fileName.includes("1.2.0") ? validators.metadataVersion12 : validators.metadataVersion, bundle.metadataVersion],
  ];

  for (const [label, validate, instance] of checks) {
    if (instance === undefined) {
      failed += 1;
      console.error(`${fileName}: missing "${label}"`);
      continue;
    }
    const ok = validate(instance);
    if (!ok) {
      failed += 1;
      console.error(`${fileName} → ${label} failed:\n${formatErrors(validate.errors)}`);
    }
  }
  if (fileName.includes("1.2.0")) {
    for (const [label, record] of [["page", bundle.page], ["snapshot", bundle.metadataVersion.snapshot]]) {
      if (!validRelationships(record)) {
        failed += 1;
        console.error(`${fileName}: ${label} has invalid component relationships`);
      }
    }
  }
}

// Version 1.1.0 extends the unchanged Phase 1 base component.
const baseComponent = readJson(path.join(examplesDir, "simple.json")).page.components[0];
function checkComponent(type, config, accepted, label) {
  const component = { ...baseComponent, type, ...config };
  const ok = validators.component11(component);
  if (ok !== accepted) {
    failed += 1;
    console.error(`1.1.0 ${label}: expected ${accepted ? "valid" : "invalid"}\n${formatErrors(validators.component11.errors)}`);
  }
}
const validConfigs = {
  form: { formConfig: { submitLabel: "Save" } },
  table: { tableConfig: { columns: [{ fieldKey: "name", label: "Name" }] } },
  navigation: { navigationConfig: { items: [{ label: "Home", route: "/home" }] } },
  modal: {},
  pagination: { paginationConfig: { pageSize: 1 } },
};
for (const [type, config] of Object.entries(validConfigs)) {
  checkComponent(type, config, true, `${type} valid`);
  for (const [otherType, otherConfig] of Object.entries(validConfigs)) {
    if (otherType !== type && Object.keys(otherConfig).length) {
      checkComponent(type, { ...config, ...otherConfig }, false, `${type} forbids ${otherType} config`);
    }
  }
}
checkComponent("form", {}, false, "form requires config");
checkComponent("form", { formConfig: { submitLabel: "" } }, false, "empty submit label");
checkComponent("table", {}, true, "legacy table");
checkComponent("table", { tableConfig: { columns: [] } }, false, "empty columns");
checkComponent("table", { tableConfig: { columns: [{ fieldKey: "", label: "Name" }] } }, false, "empty field key");
checkComponent("table", { tableConfig: { columns: [{ fieldKey: "name", label: "" }] } }, false, "empty column label");
checkComponent("navigation", { navigationConfig: { items: [{ label: "Home", route: "https://host" }] } }, false, "absolute route");
checkComponent("navigation", { navigationConfig: { items: [{ label: "Home", route: "//host" }] } }, false, "network route");
checkComponent("navigation", { navigationConfig: { items: [{ label: "", route: "/home" }] } }, false, "empty navigation label");
checkComponent("navigation", { navigationConfig: { items: [] } }, false, "empty navigation items");
checkComponent("pagination", { paginationConfig: { pageSize: 0 } }, false, "zero page size");
checkComponent("pagination", { paginationConfig: { pageSize: 1.5 } }, false, "fractional page size");
checkComponent("pagination", {}, false, "pagination requires config");
checkComponent("custom-widget", {}, true, "open unknown type");
checkComponent("custom-widget", validConfigs.form, false, "unknown type forbids typed config");
checkComponent("modal", { modalConfig: {} }, false, "modal has no config");
checkComponent("form", { formConfig: { submitLabel: "Save", extra: true } }, false, "unknown config property");

const example11 = readJson(path.join(examplesDir, "simple.json"));
example11.metadataVersion.schemaVersion = "1.1.0";
example11.metadataVersion.snapshot.components = [
  { ...baseComponent, type: "form", formConfig: { submitLabel: "Save" } },
];
if (!validators.metadataVersion11(example11.metadataVersion)) {
  failed += 1;
  console.error(`1.1.0 envelope failed:\n${formatErrors(validators.metadataVersion11.errors)}`);
}
example11.page.components = example11.metadataVersion.snapshot.components;
if (!validators.page11(example11.page)) {
  failed += 1;
  console.error(`1.1.0 page failed:\n${formatErrors(validators.page11.errors)}`);
}
const example12 = readJson(path.join(examplesDir, "many-to-many-1.2.0.json"));
const clone = (value) => JSON.parse(JSON.stringify(value));
const nestedVisibilityRule = (operators) => operators.reduce(
  (rule, op) => op === "NOT" ? { op, rule } : { op, rules: [rule] },
  { op: "permission", value: "users:read" },
);
for (const [label, operators] of [
  ["nested AND", Array(16).fill("AND")],
  ["nested OR", Array(16).fill("OR")],
  ["mixed AND/OR", Array.from({ length: 16 }, (_, index) => index % 2 ? "OR" : "AND")],
]) {
  for (const target of ["page12", "metadataVersion12"]) {
    const instance = clone(target === "page12" ? example12.page : example12.metadataVersion);
    const record = target === "page12" ? instance : instance.snapshot;
    record.apiBindings[0].visibilityRule = nestedVisibilityRule(operators);
    if (!validators[target](instance)) {
      failed++;
      console.error(
        `1.2.0 ${label} failed for ${target}:\n${formatErrors(validators[target].errors)}`,
      );
    }
  }
}
// Exercise the scalar union and recursive references through both public schemas.
for (const equals of ["approved", 42, true, null, {}, []]) {
  for (const target of ["page12", "metadataVersion12"]) {
    const instance = clone(target === "page12" ? example12.page : example12.metadataVersion);
    const record = target === "page12" ? instance : instance.snapshot;
    record.apiBindings[0].visibilityRule = {
      op: "NOT", rule: { op: "AND", rules: [{ op: "field", field: "status", equals }] },
    };
    const expected = equals === null || typeof equals !== "object";
    if (validators[target](instance) !== expected) {
      failed++;
      console.error(`1.2.0 nested field comparison ${JSON.stringify(equals)}: expected ${expected} for ${target}`);
    }
  }
}
for (const [label, mutate] of [
  ["malformed parent UUID", (v) => { v.apiBindings[0].parentComponentId = "invalid"; }],
  ["malformed child UUID", (v) => { v.apiBindings[0].childComponentIds[0] = "invalid"; }],
  ["duplicate child", (v) => { v.apiBindings[0].childComponentIds.push(v.apiBindings[0].childComponentIds[0]); }],
  ["unpaired relationship", (v) => { delete v.apiBindings[0].parentComponentId; }],
  ["unsupported rule", (v) => { v.apiBindings[0].visibilityRule = { op: "NOT", rule: { op: "unsupported" } }; }],
]) {
  for (const [target, original] of [["page12", example12.page], ["metadataVersion12", example12.metadataVersion]]) {
    const instance = clone(original);
    mutate(target === "page12" ? instance : instance.snapshot);
    if (validators[target](instance)) { failed++; console.error(`1.2.0 ${label} accepted by ${target}`); }
  }
}
for (const target of ["page", "page11", "metadataVersion", "metadataVersion11"]) {
  const instance = target.startsWith("page") ? example12.page : example12.metadataVersion;
  if (validators[target](instance)) { failed++; console.error(`${target} accepted 1.2.0 binding fields`); }
}
for (const [label, mutate] of [
  ["missing linked", (v) => { v.apiBindings[2].linkedComponentIds[0] = "12000000-0000-4000-8000-000000000099"; }],
  ["missing parent", (v) => { v.apiBindings[0].parentComponentId = "12000000-0000-4000-8000-000000000099"; }],
  ["missing child", (v) => { v.apiBindings[0].childComponentIds[0] = "12000000-0000-4000-8000-000000000099"; }],
  ["self link", (v) => { v.apiBindings[0].childComponentIds[0] = v.apiBindings[0].parentComponentId; }],
  ["shared child", (v) => { v.apiBindings[1].childComponentIds[0] = v.apiBindings[0].childComponentIds[0]; }],
  ["cycle", (v) => { v.apiBindings[2].parentComponentId = v.apiBindings[0].childComponentIds[0]; v.apiBindings[2].childComponentIds = [v.apiBindings[0].parentComponentId]; }],
]) {
  for (const original of [example12.page, example12.metadataVersion.snapshot]) {
    const record = clone(original); mutate(record);
    if (validRelationships(record)) { failed++; console.error(`1.2.0 ${label} relationship accepted`); }
  }
}
const invalidFiles = fs
  .readdirSync(invalidDir)
  .filter((fileName) => fileName.endsWith(".json"))
  .sort();

if (invalidFiles.length === 0) {
  failed += 1;
  console.error("No invalid fixtures found under examples/invalid/");
}

for (const fileName of invalidFiles) {
  const fixture = readJson(path.join(invalidDir, fileName));
  const validate = validators[fixture.target];
  if (!validate) {
    failed += 1;
    console.error(
      `${fileName}: unknown target "${fixture.target}" (expected ${Object.keys(validators).join(", ")})`
    );
    continue;
  }
  if (fixture.instance === undefined) {
    failed += 1;
    console.error(`${fileName}: missing "instance"`);
    continue;
  }
  const ok = validate(fixture.instance);
  if (ok) {
    failed += 1;
    console.error(
      `${fileName} → ${fixture.target} was accepted; this fixture must be rejected`
    );
  }
}

if (failed > 0) {
  console.error(`\nAjv 2020: ${failed} check(s) failed.`);
  process.exit(1);
}

console.log(
  `Ajv 2020: ${exampleFiles.length} valid example files passed; ${invalidFiles.length} invalid fixtures rejected.`
);
