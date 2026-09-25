package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.classification.IndicatorClassifier;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.port.out.dataframeimport.ImportValueValidatorRegistry;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.domain.refang.Refanger;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** IOC-aware validation rules for declarative as-is and processed CSV imports. */
public final class CsvImportValueValidatorRegistry implements ImportValueValidatorRegistry {

    private static final Set<IndicatorType> HASH_TYPES = Set.of(
            IndicatorType.MD5, IndicatorType.SHA1, IndicatorType.SHA256);

    private final Refanger refanger;
    private final IndicatorExtractor extractor;
    private final IndicatorClassifier classifier;
    private final Map<String, Predicate<ClassifiedIndicator>> conditions;

    /** Creates validators from the same extraction and classification policy as ordinary ingest. */
    public CsvImportValueValidatorRegistry(
            Refanger refanger,
            IndicatorExtractor extractor,
            IndicatorClassifier classifier,
            Map<String, Predicate<ClassifiedIndicator>> conditions) {
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.conditions = Map.copyOf(Objects.requireNonNull(conditions, "conditions"));
    }

    @Override
    public boolean isValid(String rule, String value) {
        ClassifiedIndicator indicator = wholeIndicator(value);
        if (indicator == null) {
            return false;
        }
        return switch (rule) {
            case "bare-ip" -> indicator.indicator().type() == IndicatorType.IPV4
                    && condition("is-bare-ip", indicator);
            case "url-address" -> condition("is-address-with-detail", indicator);
            case "clean-domain" -> condition("is-clean-host", indicator);
            case "hash" -> HASH_TYPES.contains(indicator.indicator().type());
            default -> throw new IllegalArgumentException("Unknown import value validation rule: " + rule);
        };
    }

    private ClassifiedIndicator wholeIndicator(String value) {
        String processed = refanger.refang(value).text();
        var extracted = extractor.extract(processed).indicators();
        if (extracted.size() != 1) {
            return null;
        }
        RawIndicator raw = extracted.getFirst();
        if (!processed.substring(0, raw.position()).isBlank()
                || !processed.substring(raw.position() + raw.value().length()).isBlank()) {
            return null;
        }
        Indicator indicator = new Indicator(raw.value(), raw.type(), new SourceContext(null, null));
        if (!classifier.supports(indicator)) {
            return null;
        }
        return new ClassifiedIndicator(indicator, classifier.classify(indicator));
    }

    private boolean condition(String name, ClassifiedIndicator indicator) {
        Predicate<ClassifiedIndicator> condition = conditions.get(name);
        if (condition == null) {
            throw new IllegalStateException("Missing import validation condition: " + name);
        }
        return condition.test(indicator);
    }
}
