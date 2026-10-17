<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.logging;

public final class MaskingPatternLayout extends ch.qos.logback.classic.PatternLayout {
    @Override
    public String doLayout(ch.qos.logback.classic.spi.ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.doLayout(event));
    }
}
