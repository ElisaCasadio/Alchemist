package it.unibo.alchemist.boundary.projectview.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unibo.alchemist.boundary.projectview.ProjectGUI;
import javafx.application.Platform;

/**
 * 
 *
 */
public class Watcher implements Runnable {

    private static final Logger L = LoggerFactory.getLogger(ProjectGUI.class);
    private static final long TIMEOUT = 10;
    private final LeftLayoutController ctrlLeft;

    private WatchService watcher = null;
    private String folderPath;
    private boolean isAlive = true;
    private final Map<WatchKey, Path> keys = new HashMap<>();

    /**
     * 
     * @param ctrlLeft The controller of LeftLayout.
     */
    public Watcher(final LeftLayoutController ctrlLeft) {
        try {
            this.watcher = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            L.error("This system does not support watching file system objects for changes and events.", e);
        }
        this.ctrlLeft = ctrlLeft;
    }

    /**
     * 
     * @param path a folder path of project.
     */
    public void registerPath(final String path) {
        this.folderPath = path;
        final Path dir = Paths.get(path);
        recursiveRegistration(dir);
    }

    /**
     * 
     */
    public void terminate() {
        this.isAlive = false;
    }

    @Override
    public void run() {
        while (this.isAlive) {
            WatchKey key = null;
            try {
                key = this.watcher.poll(TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                L.error("Error watcher, because it was interrupted. This is most likely a bug.", e);
            }
            if (key != null) {
                for (final WatchEvent<?> event : key.pollEvents()) {
                    final WatchEvent.Kind<?> kind = event.kind();
                    if (event.context() instanceof Path) {
                        final Path fileName = (Path) event.context();
                        //TODO: System.out.println(kind + " : " + fileName);
                        if (StandardWatchEventKinds.ENTRY_MODIFY.equals(kind)) {
                            if (fileName.toString().equals(".alchemist_project_descriptor.json")) {
                                //TODO: refresh centerlayout
                                //System.out.println("Modified .alchemist_project_descriptor.json");
                            } else {
                                refreshTreeView(this.folderPath);
                            }
                        } else if (StandardWatchEventKinds.ENTRY_CREATE.equals(kind)) {
                            recursiveRegistration(resolvePath(key, fileName));
                        } else if (StandardWatchEventKinds.ENTRY_DELETE.equals(kind)) {
                            WatchKey keyToDelete = null;
                            for (final WatchKey w : keys.keySet()) {
                                if (keys.get(w).equals(resolvePath(key, fileName))) {
                                    keyToDelete = w;
                                }
                            }
                            if (keyToDelete != null) {
                                keys.remove(keyToDelete);
                                keyToDelete.cancel();
                            }
                        } else {
                            throw new IllegalStateException("Unexpected event of kind " + kind);
                        }
                    }
                }
                key.reset();
            }
        }
        if (!isAlive) {
            try {
                this.watcher.close();
                //TODO: System.out.println("Watch Service closed");
            } catch (IOException e) {
                L.error("I/O error while closing of watcher.", e);
            }
        }
    }

    private void recursiveRegistration(final Path root) {
        /*
         * Work around idi0tic Windows file manager behavior by trying multiple times in case of failure.
         */
        for (int attempts = 0; attempts < 3; attempts++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                        try {
                            final WatchKey key = dir.register(watcher, 
                                    StandardWatchEventKinds.ENTRY_CREATE, 
                                    StandardWatchEventKinds.ENTRY_DELETE, 
                                    StandardWatchEventKinds.ENTRY_MODIFY);
                            keys.put(key, dir);
                            //TODO: System.out.println("Watch Service has registered: " + dir.getFileName() + " (path: " + dir.toAbsolutePath() + " )");
                        } catch (IOException e) {
                            L.error("Error register the folder path to watcher. This is most likely a bug.", e);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                break;
            } catch (IOException e) {
                try {
                    L.error("There was an I/O error. This is most likely due to you using the Windows file manager.", e);
                    Thread.sleep(10);
                } catch (InterruptedException e1) {
                    L.error("The watcher got interrupted. Please report this error, it is most likely a bug.", e);
                }
            }
        }
    }

    private Path resolvePath(final WatchKey key, final Path name) {
        return keys.get(key).resolve(name);
    }

    private void refreshTreeView(final String path) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                ctrlLeft.setTreeView(new File(path));
            }
        });
    }
}