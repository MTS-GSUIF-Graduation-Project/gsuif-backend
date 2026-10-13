import fs from 'node:fs';
import ts from 'typescript';

const source = fs.readFileSync(process.argv[2], 'utf8');
const javascript = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 }
}).outputText;
const { VisibilityEvaluator } = await import('data:text/javascript;base64,' + Buffer.from(javascript).toString('base64'));
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
for (const [index, item] of cases.entries()) {
  const actual = new VisibilityEvaluator(item.context).evaluate(item.rule);
  if (actual !== item.expected) throw new Error(`case ${index}: expected ${item.expected}, got ${actual}`);
}
const state = { context: { roles: [], permissions: [], fields: {} } };
const role = { op: 'role', value: 'EDITOR' };
const visible = () => new VisibilityEvaluator(state.context).evaluate(role);
if (visible()) throw new Error('missing role should hide');
state.context = { roles: ['EDITOR'], permissions: [], fields: {} };
if (!visible()) throw new Error('updated context should show');
