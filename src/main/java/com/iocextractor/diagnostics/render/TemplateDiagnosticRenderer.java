package com.iocextractor.diagnostics.render;

import com.iocextractor.diagnostics.Diagnostic;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderer that substitutes {@code {key}} placeholders from diagnostic context
 * into the default template declared by the diagnostic code.
 */
public final class TemplateDiagnosticRenderer implements DiagnosticRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    @Override
    public String render(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        var matcher = PLACEHOLDER.matcher(diagnostic.code().defaultMessageTemplate());
        var rendered = new StringBuilder();
        while (matcher.find()) {
            var key = matcher.group(1);
            var value = diagnostic.context().get(key);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(
                    value == null ? matcher.group(0) : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
