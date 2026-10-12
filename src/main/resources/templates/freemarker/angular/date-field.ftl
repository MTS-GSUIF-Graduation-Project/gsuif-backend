<label><span ngNonBindable>${control.label?html}</span><input type="date" [(ngModel)]="model.${control.key}"<#if control.disabled> disabled</#if>></label>
