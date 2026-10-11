<#list controls as control>
<#if control.type == "table">
<table><thead><tr><#list control.columns as column><th ngNonBindable>${column.label?html}</th></#list></tr></thead><tbody><tr *ngFor="let row of rows"><#list control.columns as column><td>{{ row.${column.key} }}</td></#list></tr></tbody></table>
<#elseif control.type == "form">
<form><span ngNonBindable>${control.label?html}</span><button type="button" ngNonBindable>${control.submitLabel?html}</button></form>
<#elseif control.type == "navigation">
<nav><#list control.items as item><a href="${item.route?html}" ngNonBindable>${item.label?html}</a></#list></nav>
<#elseif control.type == "modal">
<section role="dialog" aria-modal="true"><h2 ngNonBindable>${control.label?html}</h2></section>
<#elseif control.type == "pagination">
<nav aria-label="Pagination" data-page-size="${control.pageSize?c}"></nav>
<#else>
<label><span ngNonBindable>${control.label?html}</span><#if control.type == "select"><select [(ngModel)]="model.${control.key}"><option *ngFor="let option of options['${control.key?js_string}']" [value]="option">{{ option }}</option></select><#else><input type="<#if control.type == "date-field">date<#else>text</#if>" [(ngModel)]="model.${control.key}"></#if></label>
</#if>
</#list>
