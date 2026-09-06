package com.iocextractor.adapter.in.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class CliGolden {

    private CliGolden() {
    }

    static String text(String name) {
        String resource = "consumer/cli/" + name;
        try (InputStream input = CliGolden.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing CLI golden resource: " + resource);
            }
            String logicalFixture = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            return logicalFixture.replace("\n", System.lineSeparator());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read CLI golden resource: " + resource, failure);
        }
    }
}
