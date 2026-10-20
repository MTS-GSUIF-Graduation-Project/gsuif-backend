import fs from 'node:fs';
import ts from 'typescript';

const [typescriptPath, htmlPath] = process.argv.slice(2);
const sourceText = fs.readFileSync(typescriptPath, 'utf8');
const html = fs.readFileSync(htmlPath, 'utf8');
const source = ts.createSourceFile(typescriptPath, sourceText, ts.ScriptTarget.Latest, true,
  ts.ScriptKind.TS);
const violations = [];
const report = (rule, artifact = typescriptPath) =>
  violations.push({ rule, standard: 'SCRUM-50/UNMAPPED', artifact: artifact.replaceAll('\\', '/') });

const imports = new Set(source.statements
  .filter(ts.isImportDeclaration)
  .map(item => item.moduleSpecifier.text));
for (const required of ['@angular/core', '@angular/common', '@angular/forms', './angular-visibility']) {
  if (!imports.has(required)) report('ANGULAR_IMPORTS');
}

const dto = source.statements.find(item =>
  ts.isInterfaceDeclaration(item) && item.name.text === 'AssetTicketDto');
if (!dto) {
  report('ANGULAR_DTO_INTERFACE');
} else {
  const properties = new Set(dto.members
    .filter(ts.isPropertySignature)
    .map(item => item.name.getText(source)));
  for (const required of ['id', 'title', 'status']) {
    if (!properties.has(required)) report('ANGULAR_DTO_FIELDS');
  }
}

const component = source.statements.find(item =>
  ts.isClassDeclaration(item) && item.name?.text === 'AssetTicketComponent');
if (!component) {
  report('ANGULAR_COMPONENT_CLASS');
} else {
  const decorators = ts.canHaveDecorators(component) ? ts.getDecorators(component) ?? [] : [];
  const decorator = decorators.find(item =>
    ts.isCallExpression(item.expression)
      && item.expression.expression.getText(source) === 'Component');
  if (!decorator) {
    report('ANGULAR_COMPONENT_DECORATOR');
  } else {
    const metadata = decorator.expression.arguments[0];
    const property = name => ts.isObjectLiteralExpression(metadata)
      ? metadata.properties.find(item => ts.isPropertyAssignment(item)
          && item.name.getText(source).replaceAll("'", '').replaceAll('"', '') === name)
      : undefined;
    if (property('selector')?.initializer.getText(source) !== "'app-generated-asset-ticket'")
      report('ANGULAR_COMPONENT_SELECTOR');
    if (property('standalone')?.initializer.kind !== ts.SyntaxKind.TrueKeyword)
      report('ANGULAR_COMPONENT_STANDALONE');
    if (property('templateUrl')?.initializer.getText(source) !== "'./asset-ticket.component.html'")
      report('ANGULAR_TEMPLATE_RELATIONSHIP');
  }
  const fields = new Set(component.members
    .filter(ts.isPropertyDeclaration)
    .map(item => item.name.getText(source)));
  for (const required of ['visibilityContext', 'visibilityRules', 'model', 'rows', 'options']) {
    if (!fields.has(required)) report('ANGULAR_COMPONENT_FIELDS');
  }
  const methods = new Set(component.members
    .filter(ts.isMethodDeclaration)
    .map(item => item.name.getText(source)));
  if (!methods.has('isVisible')) report('ANGULAR_COMPONENT_METHODS');
}

for (const required of [
  'class="generated-grid"',
  '*ngIf="isVisible(',
  'type="text"',
  '<select',
  'row.id',
  'row.title',
]) {
  if (!html.includes(required)) report('ANGULAR_TEMPLATE_STRUCTURE', htmlPath);
}

violations.sort((left, right) =>
  left.standard.localeCompare(right.standard)
    || left.rule.localeCompare(right.rule)
    || left.artifact.localeCompare(right.artifact));
process.stdout.write(JSON.stringify(violations));
