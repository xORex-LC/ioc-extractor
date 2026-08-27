package com.iocextractor.adapter.out.transport.smb;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

interface SmbShareClient extends AutoCloseable {

    List<SmbRemoteEntry> list(String remotePath);

    Optional<SmbRemoteEntry> stat(String remotePath);

    void download(String remotePath, Path localDestination);

    void delete(String remotePath);

    /** Deletes one exact regular file and never falls back to directory removal. */
    void deleteRegularFile(String remotePath);

    boolean fileExists(String remotePath);

    boolean directoryExists(String remotePath);

    String readText(String remotePath);

    void createDirectories(String remotePath);

    /** Creates one empty regular file and fails when the exact object already exists. */
    void createEmptyFile(String remotePath);

    void upload(Path localFile, String remotePath);

    void rename(String sourcePath, String targetPath);

    void deleteTree(String remotePath);

    @Override
    void close();
}
