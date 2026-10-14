package com.iocextractor.application.port.out.artifact;

import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.extract.ExtractionClaims;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.refang.TextRewrite;
import java.io.Writer;

/** Disposable source scratch owned by the admitted document, never canonical authority. */
public interface DocumentSourceWorkspace extends TextRewrite, ExtractionClaims, AutoCloseable {
    /** One UTF-16 output stream; the caller closes it before accessing text. */
    Writer writer();
    CharSequence text();
    int maximumMatchCharacters();
    /** Decisions retain type priority and within-pattern encounter order. */
    RowSource<ExtractionDecision> decisions();
    java.util.Optional<com.iocextractor.domain.model.IndicatorType> acceptedType(int start, int end);
    /** Accepted matches are stable-sorted by absolute position. */
    RowSource<RawIndicator> indicators();
    void attribute(AttributionDecision decision);
    RowSource<AttributionDecision> attributions();
    @Override
    void close();
}
