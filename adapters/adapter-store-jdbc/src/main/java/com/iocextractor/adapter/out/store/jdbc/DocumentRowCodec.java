package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.common.IocExtractorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Versioned bounded row encoding; preserves absent columns, explicit nulls and field rank. */
final class DocumentRowCodec {
    private final int maximumRowBytes;
    private final int maximumFieldBytes;

    DocumentRowCodec(int maximumRowBytes, int maximumFieldBytes) {
        this.maximumRowBytes = maximumRowBytes;
        this.maximumFieldBytes = maximumFieldBytes;
    }

    byte[] encode(PreparedArtifactRow row) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            text(output, row.idColumn().orElse(null));
            output.writeInt(row.template().values().size());
            if (row.template().values().size() > 128 || row.orderedFieldPositions().size() > 128) {
                throw new IllegalArgumentException("Document row has too many fields");
            }
            for (var field : row.template().values().entrySet()) {
                text(output, field.getKey());
                text(output, field.getValue());
                requireSize(bytes.size());
            }
            output.writeInt(row.orderedFieldPositions().size());
            for (var field : row.orderedFieldPositions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                text(output, field.getKey());
                output.writeLong(field.getValue().value());
                requireSize(bytes.size());
            }
            requireSize(bytes.size());
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot encode document preparation row", failure);
        }
    }

    PreparedArtifactRow decode(byte[] bytes) {
        requireSize(bytes.length);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 1) { throw new IOException("Unsupported document row version"); }
            String id = text(input);
            var values = new LinkedHashMap<String, String>();
            int fields = count(input);
            for (int index = 0; index < fields; index++) {
                String field = field(input);
                if (values.containsKey(field)) { throw new IOException("Duplicate document field"); }
                values.put(field, text(input));
            }
            var positions = new LinkedHashMap<String, OccurrencePosition>();
            int ordered = count(input);
            for (int index = 0; index < ordered; index++) {
                String field = field(input);
                if (positions.containsKey(field)) { throw new IOException("Duplicate ordered document field"); }
                positions.put(field, new OccurrencePosition(input.readLong()));
            }
            if (input.available() != 0) { throw new IOException("Trailing document row bytes"); }
            return new PreparedArtifactRow(ArtifactRow.ordered(values), Optional.ofNullable(id), positions);
        } catch (IOException failure) {
            throw new IocExtractorException("Corrupt document preparation row", failure);
        }
    }

    private int count(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 128) { throw new IOException("Invalid document field count"); }
        return count;
    }

    private void text(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        if (value.length() > maximumFieldBytes) { throw new IOException("Document field exceeds byte limit"); }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumFieldBytes) { throw new IOException("Document field exceeds byte limit"); }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String text(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size == -1) { return null; }
        if (size < 0 || size > maximumFieldBytes || size > input.available()) {
            throw new IOException("Invalid document field size");
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input.readNBytes(size))).toString();
    }

    private String field(DataInputStream input) throws IOException {
        String field = text(input);
        if (field == null || field.isBlank()) { throw new IOException("Invalid document field name"); }
        return field;
    }

    private void requireSize(int size) {
        if (size > maximumRowBytes) { throw new IocExtractorException("Document row exceeds byte limit"); }
    }
}
