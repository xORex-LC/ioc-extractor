package com.iocextractor.domain.feature;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkAddressParserTest {
    private final NetworkAddressParser parser = new NetworkAddressParser();
    private final NetworkHostDeriver deriver = new NetworkHostDeriver(parser);
    private final NetworkSchemeDeriver schemeDeriver = new NetworkSchemeDeriver(parser);
    private final SourceContext source = new SourceContext("document-section", null);

    @ParameterizedTest
    @CsvSource({
        "https://best-malware.com/troyan.exe, best-malware.com, DOMAIN",
        "10.93.12.187:9090/clean-prometheus/no-virus/true, 10.93.12.187, IPV4",
        "https://10.93.12.187/path, 10.93.12.187, IPV4",
        "10.93.12.187, 10.93.12.187, IPV4",
        "HTTPS://Sub.Example.COM:443/path?x=1#part, sub.example.com, DOMAIN",
        "example.com?x=1, example.com, DOMAIN"
    })
    void derivesOneHostWithOriginalSource(String original, String host, IndicatorType type) {
        Indicator input = new Indicator(original, IndicatorType.URL, source);

        NetworkHostDeriver.Result result = deriver.derive(input);

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.indicator()).isEqualTo(new Indicator(host, type, source));
        assertThat(input.value()).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource({
        "https://Sub.Example.com:8443/Path?q=One#Part, Sub.Example.com:8443/Path?q=One#Part, URL",
        "HTTP://192.0.2.44:9090/One?x=Two, 192.0.2.44:9090/One?x=Two, URL",
        "example.com/Path, example.com/Path, URL",
        "https://example.com?Q=One, example.com?Q=One, URL",
        "https://example.com, example.com, DOMAIN",
        "http://192.0.2.44, 192.0.2.44, IPV4",
        "192.0.2.44#Part, 192.0.2.44#Part, URL",
        "192.0.2.44, 192.0.2.44, IPV4"
    })
    void removesOnlySchemeAndReclassifiesAddressShape(String original, String expected, IndicatorType type) {
        var input = new Indicator(original, IndicatorType.URL, source);

        var result = schemeDeriver.derive(input);

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.indicator()).isEqualTo(new Indicator(expected, type, source));
        assertThat(input.value()).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource({
        "ftp://example.com/Path, URL, UNSUPPORTED_SCHEME",
        "https://user@example.com/Path, URL, INVALID_AUTHORITY",
        "https://example.com:65536/Path, URL, INVALID_PORT",
        "0123456789, MD5, UNSUPPORTED_ADDRESS_FORM"
    })
    void schemeRemovalPreservesExpectedFailures(String value, IndicatorType type,
                                               NetworkAddressParser.FailureReason failure) {
        var result = schemeDeriver.derive(new Indicator(value, type, source));

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.indicator()).isNull();
        assertThat(result.failure()).isEqualTo(failure);
    }

    @ParameterizedTest
    @CsvSource({
        "example.com:, INVALID_PORT",
        "example.com:abc/path, INVALID_PORT",
        "example.com:0, INVALID_PORT",
        "example.com:65536, INVALID_PORT",
        "example.com:123456, INVALID_PORT",
        "example.com:12x, INVALID_PORT",
        "example.com:12!, INVALID_PORT",
        "https://user@example.com/path, INVALID_AUTHORITY",
        "https://[2001:db8::1]/path, INVALID_AUTHORITY",
        "https://256.1.2.3/path, INVALID_HOST",
        "https://01.2.3.4/path, INVALID_HOST",
        "1.a.2.3, INVALID_HOST",
        "1.+.2.3, INVALID_HOST",
        "1..2.3, INVALID_HOST",
        "1234.2.3.4, INVALID_HOST",
        "ftp://example.com/path, UNSUPPORTED_SCHEME",
        "http://example.com\\path, INVALID_AUTHORITY",
        "example.com], INVALID_AUTHORITY",
        "example.com:80:90/path, UNSUPPORTED_ADDRESS_FORM",
        "example.com#first#second, UNSUPPORTED_ADDRESS_FORM",
        "bad_label.example/path, INVALID_HOST",
        "x.a/path, INVALID_HOST",
        "example.12, INVALID_HOST",
        "example._a, INVALID_HOST",
        "example.éx, INVALID_HOST",
        "example+com.com, INVALID_HOST",
        "example{com.com, INVALID_HOST",
        "example..com, INVALID_HOST",
        "-example.com, INVALID_HOST",
        "example-.com, INVALID_HOST",
        "example.com., INVALID_HOST",
        "localhost, INVALID_HOST"
    })
    void rejectsMalformedOrUnsupportedAddress(String input, NetworkAddressParser.FailureReason expected) {
        assertThat(parser.parse(input).failure()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "https:///path", "https://example.com/path with space"})
    void rejectsEmptyOrIncompleteAddress(String input) {
        assertThat(parser.parse(input).isAvailable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "example.com/path?x#fragment, true, true, true",
        "example.com#fragment?x, false, false, true",
        "example.com:443, false, false, false"
    })
    void reportsPathQueryAndFragmentWithoutReinterpretingPort(String input,
            boolean hasPath, boolean hasQuery, boolean hasFragment) {
        NetworkAddressParser.Address address = parser.parse(input).address();

        assertThat(address.hasPath()).isEqualTo(hasPath);
        assertThat(address.hasQuery()).isEqualTo(hasQuery);
        assertThat(address.hasFragment()).isEqualTo(hasFragment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com:", "ftp://example.com/path"})
    void preservesTypedFailureWhenDerivingHost(String input) {
        NetworkHostDeriver.Result result = deriver.derive(
                new Indicator(input, IndicatorType.URL, source));

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.failure()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MD5", "SHA256"})
    void rejectsNonNetworkIndicatorForHostDerivation(IndicatorType type) {
        NetworkHostDeriver.Result result = deriver.derive(new Indicator("0123456789", type, source));

        assertThat(result.failure()).isEqualTo(NetworkAddressParser.FailureReason.UNSUPPORTED_ADDRESS_FORM);
    }

    @org.junit.jupiter.api.Test
    void outcomeCannotBeBothAvailableAndUnavailable() {
        assertThatThrownBy(() -> new NetworkAddressParser.Result(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NetworkHostDeriver.Result(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NetworkSchemeDeriver.Result(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    void rejectsExcessiveDnsLengthsAndControlCharacters() {
        assertThat(parser.parse(null).failure()).isEqualTo(NetworkAddressParser.FailureReason.EMPTY);
        assertThat(parser.parse("example.com" + (char) 1).failure())
                .isEqualTo(NetworkAddressParser.FailureReason.UNSUPPORTED_ADDRESS_FORM);
        assertThat(parser.parse("a".repeat(64) + ".com").failure())
                .isEqualTo(NetworkAddressParser.FailureReason.INVALID_HOST);
        assertThat(parser.parse("a".repeat(250) + ".com").failure())
                .isEqualTo(NetworkAddressParser.FailureReason.INVALID_HOST);
        assertThat(parser.parse("example." + "a".repeat(64)).failure())
                .isEqualTo(NetworkAddressParser.FailureReason.INVALID_HOST);
    }
}
