<div class="generated-grid" style="display:grid;grid-auto-columns:minmax(0,1fr)">
<#list controls as control>
<#if control.visible>
<div class="generated-grid-item" *ngIf="isVisible('${control.id}')" style="grid-row:${control.gridRow!control.row} / span ${control.height?html};grid-column:${control.gridCol!control.col} / span ${control.width?html}" data-grid-row="${control.row?html}" data-grid-col="${control.col?html}" data-grid-width="${control.width?html}" data-grid-height="${control.height?html}">
<#include control.template>
</div>
</#if>
</#list>
</div>
