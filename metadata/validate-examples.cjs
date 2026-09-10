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
  "relationship.schema.json",
  "project.schema.json",
  "page.schema.json",
  "metadata-version.schema.json",
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
  relationship: ajv.getSchema("gsuif/relationship.schema.json"),
  metadataVersion: ajv.getSchema("gsuif/metadata-version.schema.json"),
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
