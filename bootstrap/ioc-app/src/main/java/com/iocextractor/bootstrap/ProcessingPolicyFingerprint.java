package com.iocextractor.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Stable identity of every configured input that can alter prepared canonical rows. */
final class ProcessingPolicyFingerprint {

    private static final String POLICY_EPOCH = "processing-policy:v1";

    private ProcessingPolicyFingerprint() {
    }

    static String from(IocProperties properties) {
        Objects.requireNonNull(properties, "properties");
        MessageDigest digest = sha256();
        add(digest, POLICY_EPOCH);
        addValue(digest, properties.source());
        addValue(digest, properties.refang());
        addValue(digest, properties.engine());
        addValue(digest, properties.patterns());
        addValue(digest, properties.classify());
        addValue(digest, properties.sink());
        addValue(digest, properties.pipeline());
        addValue(digest, properties.artifactIdentity());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addValue(MessageDigest digest, Object value) {
        if (value == null) {
            add(digest, null);
            return;
        }
        if (value instanceof IdStart idStart) {
            add(digest, idStart.normalized());
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            add(digest, enumValue.name());
            return;
        }
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof TemporalAmount) {
            add(digest, value.toString());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            var entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> Objects.toString(entry.getKey())));
            add(digest, "map:" + entries.size());
            entries.forEach(entry -> {
                addValue(digest, entry.getKey());
                addValue(digest, entry.getValue());
            });
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            var items = new ArrayList<>();
            iterable.forEach(items::add);
            add(digest, "list:" + items.size());
            items.forEach(item -> addValue(digest, item));
            return;
        }
        if (value.getClass().isRecord()) {
            add(digest, value.getClass().getName());
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                add(digest, component.getName());
                addValue(digest, invoke(component, value));
            }
            return;
        }
        add(digest, value.getClass().getName());
        add(digest, value.toString());
    }

    private static Object invoke(RecordComponent component, Object value) {
        try {
            return component.getAccessor().invoke(value);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(
                    "Cannot fingerprint configuration component: " + component.getName(), failure);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
