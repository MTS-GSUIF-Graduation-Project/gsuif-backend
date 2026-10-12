import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { VisibilityContext, VisibilityEvaluator } from './angular-visibility';

export interface ${className}Dto {
<#list properties as property>
  ${property.name}${property.optional}: ${property.type};
</#list>
}
@Component({ selector: 'app-generated-${kebab}', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './${kebab}.component.html' })
export class ${className}Component {
  // Display decisions only. API authorization belongs to the consumer application.
  @Input() visibilityContext: VisibilityContext | null = null;
  private readonly visibilityRules: Record<string, unknown> = {
<#list rules as id, rule>
    '${id}': JSON.parse('${rule?js_string}'),
</#list>
  };

  isVisible(id: string): boolean {
    if (!Object.prototype.hasOwnProperty.call(this.visibilityRules, id)) return true;
    return new VisibilityEvaluator(this.visibilityContext).evaluate(this.visibilityRules[id]);
  }

  model = {} as ${className}Dto;
  rows: ${className}Dto[] = [];
  options: Record<string, string[]> = {
<#list options as name, values>
    ${name}: [<#list values as value>'${value?js_string}'<#sep>, </#list>],
</#list>
  };
}
