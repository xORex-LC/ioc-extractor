package com.iocextractor.application.processing;

import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.model.IndicatorType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactIndicatorParserTest {
    @Test
    void acceptsOnlyOneCompleteNetworkOrFileValue() {
        assertThat(parser(new RawIndicator("example.com:443/path", IndicatorType.DOMAIN, 0))
                .parse(" example.com:443/path ").isAvailable()).isTrue();
        assertThat(parser(new RawIndicator("abcdef", IndicatorType.MD5, 0))
                .parse("abcdef").isAvailable()).isTrue();
    }

    @Test
    void rejectsPartialAndMultipleMatchesWithoutInferringACellValue() {
        assertThat(parser(new RawIndicator("example.com", IndicatorType.DOMAIN, 1))
                .parse("xexample.com").failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.PARTIAL_OR_MULTIPLE_MATCH);
        assertThat(parser(new RawIndicator("example.com", IndicatorType.DOMAIN, 0))
                .parse("example.com/path").failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.PARTIAL_OR_MULTIPLE_MATCH);
        assertThat(parser(new RawIndicator("a.com", IndicatorType.DOMAIN, 0),
                new RawIndicator("b.com", IndicatorType.DOMAIN, 6))
                .parse("a.com b.com").failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.PARTIAL_OR_MULTIPLE_MATCH);
    }

    @Test
    void distinguishesMissingMatchFromMalformedNetworkAddress() {
        assertThat(parser().parse(null).failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.NO_INDICATOR);
        assertThat(parser().parse(" ").failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.NO_INDICATOR);
        assertThat(parser().parse("none").failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.PARTIAL_OR_MULTIPLE_MATCH);
        ExactIndicatorParser.Result invalid = parser(
                new RawIndicator("example.com:bad", IndicatorType.DOMAIN, 0))
                .parse("example.com:bad");
        assertThat(invalid.isAvailable()).isFalse();
        assertThat(invalid.failure())
                .isEqualTo(ExactIndicatorParser.FailureReason.INVALID_NETWORK_ADDRESS);
    }

    @Test
    void outcomeRequiresExactlyOneState() {
        assertThatThrownBy(() -> new ExactIndicatorParser.Result(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExactIndicatorParser.Result(
                new RawIndicator("a.com", IndicatorType.DOMAIN, 0),
                ExactIndicatorParser.FailureReason.NO_INDICATOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExactIndicatorParser parser(RawIndicator... matches) {
        IndicatorExtractor extractor = ignored -> new ExtractionOutcome(List.of(matches), List.of());
        return new ExactIndicatorParser(extractor, new NetworkAddressParser());
    }
}
