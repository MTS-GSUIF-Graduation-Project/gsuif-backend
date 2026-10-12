// Display decisions only. API authorization belongs to the consumer application.
export interface VisibilityContext {
  permissions: string[];
  roles: string[];
  fields: Record<string, unknown>;
}

export class VisibilityEvaluator {
  constructor(private readonly visibilityContext: VisibilityContext | null) {}

  evaluate(rule: unknown): boolean {
    return this.evaluateRule(rule, 0) === true;
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
      return Array.isArray(values) && values.every((entry: unknown) => typeof entry === 'string')
          ? values.includes(value) : null;
    }
    if (op === 'field') {
      const expected = rule['equals'];
      const field = rule['field'];
      if (keys.length !== 3 || typeof field !== 'string' || !field || expected === undefined) return null;
      const fields = this.visibilityContext.fields;
      if (!fields || typeof fields !== 'object' || Array.isArray(fields) || !Object.prototype.hasOwnProperty.call(fields, field)) return null;
      const actual = fields[field];
      if (actual === undefined) return null;
      if (expected && typeof expected === 'object' && !Array.isArray(expected)) {
        const number = (expected as Record<string, unknown>)['$jsonNumber'];
        if (typeof number !== 'string' || Object.keys(expected).length !== 1) return null;
        if (typeof actual === 'number') {
          // JavaScript erases the decimal token in 1.0; an ordinary 1 is an integer.
          // Use a token wrapper when the original JSON numeric type must be retained.
          if (!Number.isFinite(actual)) return null;
          const expectedInteger = /^-?(?:0|[1-9][0-9]*)$/.test(number);
          if (expectedInteger !== Number.isInteger(actual)) return false;
          if (expectedInteger && !Number.isSafeInteger(actual)) return null;
          return Object.is(Number(number), actual);
        }
        // A token wrapper remains available when exact JSON numeric types matter.
        if (actual === null || typeof actual === 'string' || typeof actual === 'boolean') return false;
        if (!actual || typeof actual !== 'object' || Array.isArray(actual) ||
            Object.keys(actual).length !== 1) return null;
        const actualNumber = (actual as Record<string, unknown>)['$jsonNumber'];
        return typeof actualNumber === 'string' ? this.sameJsonNumber(number, actualNumber) : null;
      }
      if (actual !== null && typeof actual === 'object') {
        if (Array.isArray(actual) || Object.keys(actual).length !== 1) return null;
        const number = (actual as Record<string, unknown>)['$jsonNumber'];
        if (typeof number !== 'string' || !/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(number)) return null;
        return false;
      }
      if (typeof expected === 'number') return null;
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

}
