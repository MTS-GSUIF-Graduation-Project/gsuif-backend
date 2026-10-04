<#list controls as control>
<#if control.type == "table">
<table><thead><tr><#list control.columns as column><th>${column?html}</th></#list></tr></thead><tbody><tr *ngFor="let row of rows"><#list control.columns as column><td>{{ row.${column} }}</td></#list></tr></tbody></table>
<#else>
<label><span ngNonBindable>${control.label?html}</span><#if control.type == "select"><select [(ngModel)]="model.${control.key}"><option *ngFor="let option of options.${control.key}" [value]="option">{{ option }}</option></select><#else><input type="<#if control.type == "date-field">date<#else>text</#if>" [(ngModel)]="model.${control.key}"></#if></label>
</#if>
</#list>
