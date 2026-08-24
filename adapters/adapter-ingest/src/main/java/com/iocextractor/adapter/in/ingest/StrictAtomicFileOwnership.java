package com.iocextractor.adapter.in.ingest;

import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Collision-safe local ownership transfer shared by ordinary and dataframe intake.
 *
 * <p>The primitive deliberately has no copy or non-atomic fallback. A caller may
 * process the target only after this operation proves an atomic same-filesystem
 * rename of one regular, non-symbolic-link source.</p>
 */
final class StrictAtomicFileOwnership {

    private final AtomicMove move;

    StrictAtomicFileOwnership() {
        this((source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    StrictAtomicFileOwnership(AtomicMove move) {
        this.move = Objects.requireNonNull(move, "move");
    }

    void claim(Path source, Path target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Path targetParent = target.getParent();
        if (targetParent == null) {
            throw new IocExtractorException("Cannot claim a source into a parentless target");
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(source)) {
                throw new IocExtractorException("Only a regular non-symbolic-link source can be claimed");
            }
            Files.createDirectories(targetParent);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IocExtractorException("Source ownership target already exists");
            }
            move.move(source, target);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IocExtractorException("Atomic source ownership transfer is not supported", failure);
        } catch (IocExtractorException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IocExtractorException("Atomic source ownership transfer failed", failure);
        }
    }

    @FunctionalInterface
    interface AtomicMove {
        void move(Path source, Path target) throws IOException;
    }
}
