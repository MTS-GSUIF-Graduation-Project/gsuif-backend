"use strict";

/**
 * T-13 local schema check (draft 2020-12 via Ajv 2020).
 * Does not replace SCRUM-27 / T-24 (networknt on the JVM).
 *
 * Each example file is a bundle: { project, page, metadataVersion }.
 * Those three objects are validated against their entity schemas.
 */

const fs = require("node:fs");
const path = require("node:path");
const Ajv2020 = require("ajv/dist/2020");
const addFormats = require("ajv-formats");

const root = __dirname;
const schemaDir = path.join(root, "schema");
const examplesDir = path.join(root, "examples");

const schemaFiles = [
  "component.schema.json",
  "api-binding.schema.json",
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

const validateProject = ajv.getSchema("gsuif/project.schema.json");
const validatePage = ajv.getSchema("gsuif/page.schema.json");
const validateMetadataVersion = ajv.getSchema("gsuif/metadata-version.schema.json");

if (!validateProject || !validatePage || !validateMetadataVersion) {
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
    ["project", validateProject, bundle.project],
    ["page", validatePage, bundle.page],
    ["metadataVersion", validateMetadataVersion, bundle.metadataVersion],
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

if (failed > 0) {
  console.error(`\nAjv 2020: ${failed} check(s) failed.`);
  process.exit(1);
}

console.log(
  `Ajv 2020: ${exampleFiles.length} example files passed (project, page, metadataVersion).`
);
