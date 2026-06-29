package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static com.hierynomus.msdtyp.AccessMask.GENERIC_READ;
import static com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE;
import static com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY;
import static com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL;
import static com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN;
import static com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OVERWRITE_IF;
import static com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_DELETE;
import static com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ;
import static com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_WRITE;

final class SmbjShareClient implements SmbShareClient {

    private static final EnumSet<SMB2ShareAccess> SHARE_ALL = EnumSet.of(
            FILE_SHARE_READ,
            FILE_SHARE_WRITE,
            FILE_SHARE_DELETE);

    private final SMBClient client;
    private final DiskShare share;

    SmbjShareClient(SMBClient client, DiskShare share) {
        this.client = client;
        this.share = share;
    }

    @Override
    public List<SmbRemoteEntry> list(String remotePath) {
        return share.list(toSmbPath(remotePath)).stream()
                .filter(entry -> !isSpecial(entry.getFileName()))
                .map(entry -> new SmbRemoteEntry(
                        SmbFileTransport.join(remotePath, entry.getFileName()),
                        entry.getEndOfFile(),
                        toInstant(entry.getChangeTime()),
                        isDirectory(entry.getFileAttributes())))
                .toList();
    }

    @Override
    public Optional<SmbRemoteEntry> stat(String remotePath) {
        String smbPath = toSmbPath(remotePath);
        if (!share.fileExists(smbPath) && !share.folderExists(smbPath)) {
            return Optional.empty();
        }
        FileAllInformation information = share.getFileInformation(smbPath);
        return Optional.of(new SmbRemoteEntry(
                remotePath,
                information.getStandardInformation().getEndOfFile(),
                toInstant(information.getBasicInformation().getChangeTime()),
                information.getStandardInformation().isDirectory()));
    }

    @Override
    public void download(String remotePath, Path localDestination) {
        try (File remote = share.openFile(
                toSmbPath(remotePath),
                EnumSet.of(GENERIC_READ),
                EnumSet.of(FILE_ATTRIBUTE_NORMAL),
                SHARE_ALL,
                FILE_OPEN,
                null);
             InputStream input = remote.getInputStream();
             OutputStream output = Files.newOutputStream(localDestination)) {
            input.transferTo(output);
        } catch (IOException e) {
            throw SmbExceptionMapper.map(e, "download", remotePath);
        }
    }

    @Override
    public void delete(String remotePath) {
        String smbPath = toSmbPath(remotePath);
        if (share.fileExists(smbPath)) {
            share.rm(smbPath);
        } else if (share.folderExists(smbPath)) {
            share.rmdir(smbPath, true);
        }
    }

    @Override
    public boolean fileExists(String remotePath) {
        return share.fileExists(toSmbPath(remotePath));
    }

    @Override
    public boolean directoryExists(String remotePath) {
        return share.folderExists(toSmbPath(remotePath));
    }

    @Override
    public String readText(String remotePath) {
        try (File remote = share.openFile(
                toSmbPath(remotePath),
                EnumSet.of(GENERIC_READ),
                EnumSet.of(FILE_ATTRIBUTE_NORMAL),
                SHARE_ALL,
                FILE_OPEN,
                null);
             InputStream input = remote.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw SmbExceptionMapper.map(e, "read", remotePath);
        }
    }

    @Override
    public void createDirectories(String remotePath) {
        ArrayDeque<String> segments = new ArrayDeque<>(List.of(remotePath.split("/")));
        StringBuilder current = new StringBuilder();
        while (!segments.isEmpty()) {
            String segment = segments.removeFirst();
            if (segment.isBlank()) {
                continue;
            }
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(segment);
            String smbPath = toSmbPath(current.toString());
            if (!share.folderExists(smbPath)) {
                share.mkdir(smbPath);
            }
        }
    }

    @Override
    public void upload(Path localFile, String remotePath) {
        try (File remote = share.openFile(
                toSmbPath(remotePath),
                EnumSet.of(GENERIC_WRITE),
                EnumSet.of(FILE_ATTRIBUTE_NORMAL),
                SHARE_ALL,
                FILE_OVERWRITE_IF,
                null);
             OutputStream output = remote.getOutputStream()) {
            Files.copy(localFile, output);
        } catch (IOException e) {
            throw SmbExceptionMapper.map(e, "upload", remotePath);
        }
    }

    @Override
    public void rename(String sourcePath, String targetPath) {
        try (DiskEntry entry = share.open(
                toSmbPath(sourcePath),
                EnumSet.of(AccessMask.DELETE),
                EnumSet.of(FILE_ATTRIBUTE_DIRECTORY),
                SHARE_ALL,
                FILE_OPEN,
                null)) {
            entry.rename(toSmbPath(targetPath), false);
        }
    }

    @Override
    public void deleteTree(String remotePath) {
        delete(remotePath);
    }

    @Override
    public void close() {
        try {
            share.close();
        } catch (IOException ignored) {
            // Best-effort close; SMBJ client close below closes remaining connections.
        } finally {
            client.close();
        }
    }

    private static boolean isSpecial(String fileName) {
        return ".".equals(fileName) || "..".equals(fileName);
    }

    private static boolean isDirectory(long attributes) {
        return (attributes & FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
    }

    private static Instant toInstant(com.hierynomus.msdtyp.FileTime fileTime) {
        return fileTime == null ? Instant.EPOCH : fileTime.toInstant();
    }

    private static String toSmbPath(String remotePath) {
        return remotePath.replace('/', '\\');
    }
}
