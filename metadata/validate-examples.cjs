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
];

const exampleFiles = ["simple.json", "one-to-many.json", "many-to-many.json"];

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function formatErrors(errors) {
  return (errors || [])
    .map((error) => `  ${error.instancePath || "/"} ${error.message}`)
    .join("\n");
}

const ajv = new Ajv2020({
  allErrors: true,
  strict: true,
});
addFormats(ajv);

for (const fileName of schemaFiles) {
  const schema = readJson(path.join(schemaDir, fileName));
  ajv.addSchema(schema);
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
    ["page", validators.page, bundle.page],
    ["metadataVersion", validators.metadataVersion, bundle.metadataVersion],
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
