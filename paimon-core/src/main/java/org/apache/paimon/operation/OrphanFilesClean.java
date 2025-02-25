/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.operation;

import org.apache.paimon.FileStore;
import org.apache.paimon.Snapshot;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.apache.paimon.index.IndexFileHandler;
import org.apache.paimon.index.IndexFileMeta;
import org.apache.paimon.manifest.IndexManifestEntry;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.utils.DateTimeUtils;
import org.apache.paimon.utils.FileUtils;
import org.apache.paimon.utils.SnapshotManager;
import org.apache.paimon.utils.TagManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.paimon.io.DataFilePathFactory.BUCKET_PATH_PREFIX;

/**
 * To remove the data files and metadata files that are not used by table (so-called "orphan
 * files").
 *
 * <p>It will ignore exception when listing all files because it's OK to not delete unread files.
 *
 * <p>To avoid deleting newly written files, it only deletes orphan files older than 1 day by
 * default. The interval can be modified by {@link #olderThan}.
 *
 * <p>To avoid conflicting with snapshot expiration, tag deletion and rollback, it will skip the
 * snapshot/tag when catching {@link FileNotFoundException} in the process of listing used files.
 *
 * <p>To avoid deleting files that are used but not read by mistaken, it will stop removing process
 * when failed to read used files.
 */
public class OrphanFilesClean {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanFilesClean.class);

    private static final int READ_FILE_RETRY_NUM = 3;
    private static final int READ_FILE_RETRY_INTERVAL = 5;

    private final SnapshotManager snapshotManager;
    private final TagManager tagManager;
    private final FileIO fileIO;
    private final Path location;
    private final int partitionKeysNum;
    private final ManifestList manifestList;
    private final ManifestFile manifestFile;
    private final IndexFileHandler indexFileHandler;

    // an estimated value of how many files were deleted
    private int deletedFilesNum = 0;
    private long olderThanMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);

    public OrphanFilesClean(FileStoreTable table) {
        this.snapshotManager = table.snapshotManager();
        this.tagManager = table.tagManager();
        this.fileIO = table.fileIO();
        this.location = table.location();
        this.partitionKeysNum = table.partitionKeys().size();

        FileStore<?> store = table.store();
        this.manifestList = store.manifestListFactory().create();
        this.manifestFile = store.manifestFileFactory().create();
        this.indexFileHandler = store.newIndexFileHandler();
    }

    public OrphanFilesClean olderThan(String timestamp) {
        // The FileStatus#getModificationTime returns milliseconds
        this.olderThanMillis =
                DateTimeUtils.parseTimestampData(timestamp, 3, DateTimeUtils.LOCAL_TZ)
                        .getMillisecond();
        return this;
    }

    public int clean() throws IOException, ExecutionException, InterruptedException {
        if (snapshotManager.earliestSnapshotId() == null) {
            LOG.info("No snapshot found, skip removing.");
            return 0;
        }

        // specially handle the snapshot directory
        List<Path> nonSnapshotFiles = snapshotManager.tryGetNonSnapshotFiles(this::oldEnough);
        nonSnapshotFiles.forEach(this::deleteFileOrDirQuietly);
        deletedFilesNum += nonSnapshotFiles.size();

        Set<String> usedFiles = getUsedFiles();
        Map<String, Path> candidates = getCandidateDeletingFiles();

        Set<String> deleted = new HashSet<>(candidates.keySet());
        deleted.removeAll(usedFiles);

        for (String file : deleted) {
            Path path = candidates.get(file);
            deleteFileOrDirQuietly(path);
        }
        deletedFilesNum += deleted.size();

        return deletedFilesNum;
    }

    /** Get all the files used by snapshots and tags. */
    private Set<String> getUsedFiles()
            throws IOException, ExecutionException, InterruptedException {
        // safely get all snapshots to be read
        Set<Snapshot> readSnapshots = new HashSet<>(snapshotManager.safelyGetAllSnapshots());
        SortedMap<Snapshot, String> tags = tagManager.tags();
        List<Snapshot> taggedSnapshots = new ArrayList<>(tags.keySet());
        readSnapshots.addAll(taggedSnapshots);

        return FileUtils.COMMON_IO_FORK_JOIN_POOL
                .submit(
                        () ->
                                readSnapshots
                                        .parallelStream()
                                        .flatMap(
                                                snapshot ->
                                                        getUsedFilesForSnapshot(snapshot).stream())
                                        .collect(Collectors.toSet()))
                .get();
    }

    /**
     * Get all the candidate deleting files in the specified directories and filter them by
     * olderThanMillis.
     */
    private Map<String, Path> getCandidateDeletingFiles() {
        List<Path> fileDirs = listPaimonFileDirs();
        try {
            return FileUtils.COMMON_IO_FORK_JOIN_POOL
                    .submit(
                            () ->
                                    fileDirs.parallelStream()
                                            .flatMap(p -> tryBestListingDirs(p).stream())
                                            .filter(this::oldEnough)
                                            .map(FileStatus::getPath)
                                            .collect(
                                                    Collectors.toMap(
                                                            Path::getName, Function.identity())))
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            LOG.debug("Failed to get candidate deleting files.", e);
            return Collections.emptyMap();
        }
    }

    /**
     * If getting null when reading some files, the snapshot/tag is being deleted, so just return an
     * empty result.
     */
    private List<String> getUsedFilesForSnapshot(Snapshot snapshot) {
        List<String> files = new ArrayList<>();
        addManifestList(files, snapshot);

        try {
            // try to read manifests
            List<ManifestFileMeta> manifestFileMetas =
                    retryReadingFiles(() -> readAllManifestsWithIOException(snapshot));
            if (manifestFileMetas == null) {
                return Collections.emptyList();
            }
            List<String> manifestFileName =
                    manifestFileMetas.stream()
                            .map(ManifestFileMeta::fileName)
                            .collect(Collectors.toList());
            files.addAll(manifestFileName);

            // try to read data files
            List<String> dataFiles = retryReadingDataFiles(manifestFileName);
            if (dataFiles == null) {
                return Collections.emptyList();
            }
            files.addAll(dataFiles);

            // try to read index files
            String indexManifest = snapshot.indexManifest();
            if (indexManifest != null && indexFileHandler.existsManifest(indexManifest)) {
                files.add(indexManifest);

                List<IndexManifestEntry> indexManifestEntries =
                        retryReadingFiles(
                                () -> indexFileHandler.readManifestWithIOException(indexManifest));
                if (indexManifestEntries == null) {
                    return Collections.emptyList();
                }

                indexManifestEntries.stream()
                        .map(IndexManifestEntry::indexFile)
                        .map(IndexFileMeta::fileName)
                        .forEach(files::add);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return files;
    }

    private void addManifestList(List<String> used, Snapshot snapshot) {
        used.add(snapshot.baseManifestList());
        used.add(snapshot.deltaManifestList());
        String changelogManifestList = snapshot.changelogManifestList();
        if (changelogManifestList != null) {
            used.add(changelogManifestList);
        }
    }

    /**
     * Retry reading files when {@link IOException} was thrown by the reader. If the exception is
     * {@link FileNotFoundException}, return null. Finally, if retry times reaches the limits,
     * rethrow the IOException.
     */
    @Nullable
    private <T> T retryReadingFiles(ReaderWithIOException<T> reader) throws IOException {
        int retryNumber = 0;
        IOException caught = null;
        while (retryNumber++ < READ_FILE_RETRY_NUM) {
            try {
                return reader.read();
            } catch (FileNotFoundException e) {
                return null;
            } catch (IOException e) {
                caught = e;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(READ_FILE_RETRY_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        throw caught;
    }

    private List<ManifestFileMeta> readAllManifestsWithIOException(Snapshot snapshot)
            throws IOException {
        List<ManifestFileMeta> result = new ArrayList<>();

        result.addAll(manifestList.readWithIOException(snapshot.baseManifestList()));
        result.addAll(manifestList.readWithIOException(snapshot.deltaManifestList()));

        String changelogManifestList = snapshot.changelogManifestList();
        if (changelogManifestList != null) {
            result.addAll(manifestList.readWithIOException(changelogManifestList));
        }

        return result;
    }

    @Nullable
    private List<String> retryReadingDataFiles(List<String> manifestNames) throws IOException {
        List<String> dataFiles = new ArrayList<>();
        for (String manifestName : manifestNames) {
            List<ManifestEntry> manifestEntries =
                    retryReadingFiles(() -> manifestFile.readWithIOException(manifestName));
            if (manifestEntries == null) {
                return null;
            }

            manifestEntries.stream()
                    .map(ManifestEntry::file)
                    .forEach(
                            f -> {
                                dataFiles.add(f.fileName());
                                dataFiles.addAll(f.extraFiles());
                            });
        }
        return dataFiles;
    }

    /** List directories that contains data files and manifest files. */
    private List<Path> listPaimonFileDirs() {
        List<Path> paimonFileDirs = new ArrayList<>();

        paimonFileDirs.add(new Path(location, "manifest"));
        paimonFileDirs.add(new Path(location, "index"));
        paimonFileDirs.addAll(listAndCleanDataDirs(location, partitionKeysNum));

        return paimonFileDirs;
    }

    /**
     * If failed to list directory, just return an empty result because it's OK to not delete them.
     */
    private List<FileStatus> tryBestListingDirs(Path dir) {
        try {
            if (!fileIO.exists(dir)) {
                return Collections.emptyList();
            }

            List<FileStatus> status =
                    retryReadingFiles(
                            () -> {
                                FileStatus[] s = fileIO.listStatus(dir);
                                return s == null ? Collections.emptyList() : Arrays.asList(s);
                            });
            return status == null ? Collections.emptyList() : status;
        } catch (IOException e) {
            LOG.debug("Failed to list directory {}, skip it.", dir, e);
            return Collections.emptyList();
        }
    }

    private boolean oldEnough(FileStatus status) {
        return status.getModificationTime() < olderThanMillis;
    }

    /**
     * List directories that contains data files and may clean non Paimon data dirs/files. The
     * argument level is used to control recursive depth.
     */
    private List<Path> listAndCleanDataDirs(Path dir, int level) {
        List<FileStatus> dirs = tryBestListingDirs(dir);

        if (level == 0) {
            // return bucket paths
            return filterAndCleanDataDirs(
                    dirs,
                    p -> p.getName().startsWith(BUCKET_PATH_PREFIX),
                    // if buckets are under partition, we can do clean
                    partitionKeysNum -> partitionKeysNum != 0);
        }

        List<Path> partitionPaths =
                filterAndCleanDataDirs(
                        dirs,
                        p -> p.getName().contains("="),
                        // if partitions are under a parent partition, we can do clean
                        partitionKeysNum -> level != partitionKeysNum);

        // dive into the next partition level
        try {
            return FileUtils.COMMON_IO_FORK_JOIN_POOL
                    .submit(
                            () ->
                                    partitionPaths
                                            .parallelStream()
                                            .flatMap(
                                                    p ->
                                                            listAndCleanDataDirs(p, level - 1)
                                                                    .stream())
                                            .collect(Collectors.toList()))
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            LOG.debug("Failed to list partition directory {}", dir, e);
            return Collections.emptyList();
        }
    }

    private List<Path> filterAndCleanDataDirs(
            List<FileStatus> statuses, Predicate<Path> filter, Predicate<Integer> cleanCondition) {
        List<Path> filtered = new ArrayList<>();
        List<FileStatus> mayBeClean = new ArrayList<>();

        for (FileStatus status : statuses) {
            Path path = status.getPath();
            if (filter.test(path)) {
                filtered.add(path);
            } else {
                mayBeClean.add(status);
            }
        }

        if (cleanCondition.test(partitionKeysNum)) {
            mayBeClean.stream()
                    .filter(this::oldEnough)
                    .map(FileStatus::getPath)
                    .forEach(
                            p -> {
                                deleteFileOrDirQuietly(p);
                                deletedFilesNum++;
                            });
        }

        return filtered;
    }

    private void deleteFileOrDirQuietly(Path path) {
        try {
            if (fileIO.isDir(path)) {
                fileIO.deleteDirectoryQuietly(path);
            } else {
                fileIO.deleteQuietly(path);
            }
        } catch (IOException ignored) {
        }
    }

    /** A helper functional interface for method {@link #retryReadingFiles}. */
    @FunctionalInterface
    private interface ReaderWithIOException<T> {

        T read() throws IOException;
    }
}
