package com.jhonju.ps3netsrv.server.utils;

import com.jhonju.ps3netsrv.server.io.FileCustom;
import com.jhonju.ps3netsrv.server.io.IFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PathResolver {

    private final String primaryRoot;

    public PathResolver(String primaryRoot) {
        this.primaryRoot = primaryRoot;
    }

    /**
     * Resolves a client path to one or more IFile objects.
     * Supports Virtual Linked Directories via .INI files.
     */
    public List<IFile> resolve(String clientPath) throws IOException {
        List<IFile> results = new ArrayList<>();

        // 1. Check primary root
        File primaryFile = new File(primaryRoot, clientPath);
        if (primaryFile.exists()) {
            results.add(new FileCustom(primaryFile));
        }

        // 2. Resolve linked directories if needed
        // We look for .INI files corresponding to path components.
        // Example: /PS3ISO/MyGame.iso -> Check if PS3ISO.INI exists in primaryRoot.
        String normalizedPath = clientPath.replace("\\", "/");
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        String[] parts = normalizedPath.split("/");
        if (parts.length > 0) {
            String firstComponent = parts[0];
            File iniFile = new File(primaryRoot, firstComponent + ".INI");
            if (iniFile.exists() && iniFile.isFile()) {
                List<String> linkedPaths = readIniFile(iniFile);
                for (String linkedPath : linkedPaths) {
                    // Reconstruct the path relative to the linked root
                    StringBuilder subPath = new StringBuilder();
                    for (int i = 1; i < parts.length; i++) {
                        subPath.append(File.separator).append(parts[i]);
                    }
                    File linkedFile = new File(linkedPath, subPath.toString());
                    if (linkedFile.exists()) {
                        results.add(new FileCustom(linkedFile));
                    }
                }
            }
        }

        return results;
    }

    /**
     * Returns all matching files for a directory listing, merging primary and
     * linked roots.
     */
    public List<IFile> resolveAllForDir(String clientPath) throws IOException {
        return resolve(clientPath);
    }

    /**
     * Finds the first existing file match.
     */
    public IFile resolveFirst(String clientPath) throws IOException {
        List<IFile> results = resolve(clientPath);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Resolves a path for writing. Only considers the primary root.
     * Returns an IFile matching the primary root, even if it doesn't exist.
     */
    public IFile resolveForWrite(String clientPath) throws IOException {
        File primaryFile = new File(primaryRoot, clientPath);
        return new FileCustom(primaryFile);
    }

    private List<String> readIniFile(File iniFile) {
        List<String> paths = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(iniFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
                    paths.add(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading INI file: " + iniFile.getAbsolutePath());
        }
        return paths;
    }
}
