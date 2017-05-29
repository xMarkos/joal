package org.araymond.joal.core.torrent.watcher;

import com.google.common.base.Preconditions;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.lang3.StringUtils;
import org.araymond.joal.core.exception.NoMoreTorrentsFileAvailableException;
import org.araymond.joal.core.ttorent.client.MockedTorrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Created by raymo on 28/01/2017.
 */
@Component
public class TorrentFileProvider extends FileAlterationListenerAdaptor {
    private static final Logger logger = LoggerFactory.getLogger(TorrentFileProvider.class);

    private final TorrentFileWatcher watcher;
    private final Map<File, MockedTorrent> torrentFiles;
    private final Path archiveFolder;
    private final Set<TorrentFileChangeAware> torrentFileChangeListener;

    private boolean isInitOver = false;

    @PostConstruct
    void postConstruct() {
        this.watcher.start();
        this.isInitOver = true;
    }

    @PreDestroy
    void preDestroy() {
        this.watcher.stop();
    }

    @Inject
    TorrentFileProvider(@Value("${joal-conf}") final String confFolder) throws FileNotFoundException {
        if (StringUtils.isBlank(confFolder)) {
            throw new IllegalArgumentException("A config path is required.");
        }
        final Path torrentFolder = Paths.get(confFolder).resolve("torrents");
        if (!Files.exists(torrentFolder)) {
            logger.error("Folder " + torrentFolder.toAbsolutePath() + " does not exists.");
            throw new FileNotFoundException(String.format("Torrent folder '%s' not found.", torrentFolder.toAbsolutePath()));
        }

        this.archiveFolder = torrentFolder.resolve("archived");
        if (!Files.exists(archiveFolder)) {
            try {
                Files.createDirectory(archiveFolder);
            } catch (final IOException e) {
                logger.error("Failed to create archive folder.", e);
                throw new IllegalStateException("Failed to create archive folder.", e);
            }
        }

        this.torrentFiles = Collections.synchronizedMap(new HashMap<File, MockedTorrent>());

        this.watcher = new TorrentFileWatcher(this, torrentFolder);
        torrentFileChangeListener = new HashSet<>();
    }

    @Override
    public void onFileDelete(final File file) {
        if (!this.torrentFiles.containsKey(file)) {
            return;
        }

        final MockedTorrent torrent = this.torrentFiles.get(file);
        if (torrent == null) {
            return;
        }

        logger.info("Torrent file deleting detected, hot deleted file: {}", file.getAbsolutePath());
        this.torrentFiles.remove(file);
        this.torrentFileChangeListener.forEach(listener -> listener.onTorrentRemoved(torrent));
    }

    @Override
    public void onFileCreate(final File file) {
        logger.info("Torrent file addition detected, hot creating file: {}", file.getAbsolutePath());
        try {
            final MockedTorrent torrent = MockedTorrent.fromFile(file);
            this.torrentFiles.put(file, torrent);
            if (this.isInitOver) {
                this.torrentFileChangeListener.forEach(listener -> listener.onTorrentAdded(torrent));
            }
        } catch (final IOException | NoSuchAlgorithmException e) {
            logger.warn("File '{}' not added to torrent list, because failed to read file '", file.getAbsolutePath(), e);
        }
    }

    @Override
    public void onFileChange(final File file) {
        logger.info("Torrent file change detected, hot reloading file: {}", file.getAbsolutePath());
        this.onFileDelete(file);
        this.onFileCreate(file);
    }

    public void registerListener(final TorrentFileChangeAware listener) {
        this.torrentFileChangeListener.add(listener);
    }

    public void unRegisterListener(final TorrentFileChangeAware listener) {
        this.torrentFileChangeListener.remove(listener);
    }

    public MockedTorrent getTorrentNotIn(final List<MockedTorrent> unwantedTorrents) throws NoMoreTorrentsFileAvailableException {
        Preconditions.checkNotNull(unwantedTorrents, "List of unwantedTorrents cannot be null.");
        return this.torrentFiles.values().stream()
                .filter(torrent -> !unwantedTorrents.contains(torrent))
                .findAny()
                .orElseThrow(() -> new NoMoreTorrentsFileAvailableException("No more torrent file available."));
    }

    void moveToArchiveFolder(final File torrentFile) {
        if (!torrentFile.exists()) {
            return;
        }
        this.onFileDelete(torrentFile);

        try {
            Files.deleteIfExists(archiveFolder.resolve(torrentFile.getName()));
            Files.move(torrentFile.toPath(), archiveFolder.resolve(torrentFile.getName()));
            logger.info("Successfully moved file: {} to archive folder", torrentFile.getAbsolutePath());
        } catch (final IOException e) {
            logger.warn("Failed to archive file: {}, the file won't be used anymore for the current session, but it remains on the folder.", e);
        }
    }

    public void moveToArchiveFolder(final MockedTorrent torrent) {
        this.moveToArchiveFolder(torrent.getPath().toFile());
    }

    public int getTorrentCount() {
        return this.torrentFiles.size();
    }


}