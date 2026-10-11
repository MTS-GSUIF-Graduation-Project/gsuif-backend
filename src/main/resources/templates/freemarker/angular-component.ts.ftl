import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface ${className}Dto {
<#list properties as property>
  ${property.name}${property.optional}: ${property.type};
</#list>
}
@Component({ selector: 'app-generated-${kebab}', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './${kebab}.component.html' })
export class ${className}Component {
  model = {} as ${className}Dto;
  rows: ${className}Dto[] = [];
  options: Record<string, string[]> = {
<#list options as name, values>
    ${name}: [<#list values as value>'${value?js_string}'<#sep>, </#list>],
</#list>
  };
}
