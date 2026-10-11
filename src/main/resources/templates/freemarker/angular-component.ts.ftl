import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface ${className}Dto {
<#list properties as property>
  ${property.name}${property.optional}: ${property.type};
</#list>
}
@Component({ selector: 'app-generated-${kebab}', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './${kebab}.component.html' })
export class ${className}Component {
  // Display decisions only. API authorization belongs to the consumer application.
  @Input() visibilityContext: { permissions: string[]; roles: string[]; fields: Record<string, unknown> } | null = null;
  private readonly visibilityRules: Record<string, unknown> = {
<#list rules as id, rule>
    '${id}': JSON.parse('${rule?js_string}'),
</#list>
  };

  isVisible(id: string): boolean {
    if (!Object.prototype.hasOwnProperty.call(this.visibilityRules, id)) return true;
    return this.evaluateRule(this.visibilityRules[id], 0) === true;
  }

  private evaluateRule(input: unknown, depth: number): boolean | null {
    if (depth > 64 || !input || typeof input !== 'object' || Array.isArray(input) || !this.visibilityContext) return null;
    const rule = input as Record<string, unknown>;
    const keys = Object.keys(rule);
    const op = rule['op'];
    if (op === 'permission' || op === 'role') {
      const value = rule['value'];
      if (keys.length !== 2 || typeof value !== 'string' || !value) return null;
      const values = op === 'permission' ? this.visibilityContext.permissions : this.visibilityContext.roles;
      return Array.isArray(values) ? values.includes(value) : null;
    }
    if (op === 'field') {
      const expected = rule['equals'];
      const field = rule['field'];
      if (keys.length !== 3 || typeof field !== 'string' || !field || expected === undefined) return null;
      const fields = this.visibilityContext.fields;
      if (!fields || typeof fields !== 'object' || !Object.prototype.hasOwnProperty.call(fields, field)) return null;
      const actual = fields[field];
      if (actual === undefined) return null;
      if (expected && typeof expected === 'object' && !Array.isArray(expected)) {
        const number = (expected as Record<string, unknown>)['$jsonNumber'];
        if (typeof number !== 'string' || Object.keys(expected).length !== 1) return null;
        // Numeric field values require their JSON token spelling because JS erases 1 versus 1.0.
        if (!actual || typeof actual !== 'object' || Array.isArray(actual) ||
            Object.keys(actual).length !== 1) return null;
        const actualNumber = (actual as Record<string, unknown>)['$jsonNumber'];
        return typeof actualNumber === 'string' ? this.sameJsonNumber(number, actualNumber) : null;
      }
      if (typeof actual === 'number' || (typeof actual === 'object' && actual !== null)) return null;
      return actual === expected;
    }
    if (op === 'NOT') {
      if (keys.length !== 2) return null;
      const child = this.evaluateRule(rule['rule'], depth + 1);
      return child === null ? null : !child;
    }
    if (op === 'AND' || op === 'OR') {
      if (keys.length !== 2 || !Array.isArray(rule['rules']) || rule['rules'].length === 0) return null;
      const results = rule['rules'].map((child: unknown) => this.evaluateRule(child, depth + 1));
      if (results.includes(null)) return null;
      return op === 'AND' ? results.every((value: boolean | null) => value === true)
          : results.some((value: boolean | null) => value === true);
    }
    return null;
  }

  private sameJsonNumber(expected: string, actual: string): boolean | null {
    const token = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/;
    if (!token.test(expected) || !token.test(actual)) return null;
    const integer = /^-?(?:0|[1-9][0-9]*)$/;
    if (integer.test(expected) !== integer.test(actual)) return false;
    if (integer.test(expected)) {
      const left = BigInt(expected);
      const right = BigInt(actual);
      const category = (value: bigint): number => value >= -2147483648n && value <= 2147483647n ? 0
          : value >= -9223372036854775808n && value <= 9223372036854775807n ? 1 : 2;
      return category(left) === category(right) && left === right;
    }
    // Jackson floating-point node equality preserves the sign of zero.
    return Object.is(Number(expected), Number(actual));
  }

  model = {} as ${className}Dto;
  rows: ${className}Dto[] = [];
  options: Record<string, string[]> = {
<#list options as name, values>
    ${name}: [<#list values as value>'${value?js_string}'<#sep>, </#list>],
</#list>
  };
}
