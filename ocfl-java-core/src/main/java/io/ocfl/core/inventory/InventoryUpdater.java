/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019-2021 University of Wisconsin Board of Regents
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.ocfl.core.inventory;

import io.ocfl.api.OcflConfig;
import io.ocfl.api.OcflConstants;
import io.ocfl.api.OcflOption;
import io.ocfl.api.exception.OcflInputException;
import io.ocfl.api.exception.OverwriteException;
import io.ocfl.api.model.DigestAlgorithm;
import io.ocfl.api.model.VersionInfo;
import io.ocfl.api.model.VersionNum;
import io.ocfl.api.util.Enforce;
import io.ocfl.core.model.Inventory;
import io.ocfl.core.model.InventoryBuilder;
import io.ocfl.core.model.Version;
import io.ocfl.core.model.VersionBuilder;
import io.ocfl.core.path.ContentPathMapper;
import io.ocfl.core.path.constraint.LogicalPathConstraints;
import io.ocfl.core.path.constraint.PathConstraintProcessor;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class is used to record changes to OCFL objects and construct an updated inventory.
 */
public class InventoryUpdater {

    private final Inventory inventory;

    private final String objectId;
    private final boolean mutableHead;

    private final InventoryBuilder inventoryBuilder;
    private final VersionBuilder versionBuilder;

    private final ContentPathMapper contentPathMapper;
    private final PathConstraintProcessor logicalPathConstraints;

    private final Lock lock = new ReentrantLock();

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private ContentPathMapper.Builder contentPathMapperBuilder;

        public Builder() {
            contentPathMapperBuilder = ContentPathMapper.builder();
        }

        public Builder contentPathMapperBuilder(ContentPathMapper.Builder contentPathMapperBuilder) {
            this.contentPathMapperBuilder =
                    Enforce.notNull(contentPathMapperBuilder, "contentPathMapperBuilder cannot be null");
            return this;
        }

        /**
         * Constructs a new InventoryUpdater that does NOT copy over the state from the previous version.
         *
         * @param inventory the original inventory
         * @return inventory updater
         */
        public InventoryUpdater buildBlankState(Inventory inventory) {
            Enforce.notNull(inventory, "inventory cannot be null");

            var inventoryBuilder = inventory.buildNextVersionFrom();
            var versionBuilder = Version.builder();

            return new InventoryUpdater(
                    inventory,
                    inventoryBuilder,
                    versionBuilder,
                    contentPathMapperBuilder.buildStandardVersion(inventory));
        }

        /**
         * Constructs a new InventoryUpdater that copies over the state from the previous version.
         *
         * @param inventory the original inventory
         * @return inventory updater
         */
        public InventoryUpdater buildCopyState(Inventory inventory) {
            Enforce.notNull(inventory, "inventory cannot be null");

            var inventoryBuilder = inventory.buildNextVersionFrom();
            VersionBuilder versionBuilder;

            if (inventory.getHeadVersion() != null) {
                versionBuilder = Version.builder(inventory.getHeadVersion());
            } else {
                versionBuilder = Version.builder();
            }

            return new InventoryUpdater(
                    inventory,
                    inventoryBuilder,
                    versionBuilder,
                    contentPathMapperBuilder.buildStandardVersion(inventory));
        }

        /**
         * Constructs a new InventoryUpdater that copies over the state from a previous version.
         *
         * @param inventory the original inventory
         * @param versionNum the id over the version to copy
         * @return inventory updater
         */
        public InventoryUpdater buildCopyState(Inventory inventory, VersionNum versionNum) {
            Enforce.notNull(inventory, "inventory cannot be null");
            Enforce.notNull(versionNum, "versionNum cannot be null");

            var inventoryBuilder = inventory.buildNextVersionFrom();
            var versionBuilder = Version.builder(inventory.getVersion(versionNum));

            return new InventoryUpdater(
                    inventory,
                    inventoryBuilder,
                    versionBuilder,
                    contentPathMapperBuilder.buildStandardVersion(inventory));
        }

        /**
         * Constructs a new InventoryUpdater that copies over the state from the previous version, and creates a mutable
         * HEAD version.
         *
         * @param inventory the original inventory
         * @return inventory updater
         */
        public InventoryUpdater buildCopyStateMutable(Inventory inventory) {
            Enforce.notNull(inventory, "inventory cannot be null");

            var inventoryBuilder = inventory.buildNextVersionFrom().mutableHead(true);
            var versionBuilder = Version.builder(inventory.getHeadVersion());

            return new InventoryUpdater(
                    inventory,
                    inventoryBuilder,
                    versionBuilder,
                    contentPathMapperBuilder.buildMutableVersion(inventory));
        }
    }

    private InventoryUpdater(
            Inventory inventory,
            InventoryBuilder inventoryBuilder,
            VersionBuilder versionBuilder,
            ContentPathMapper contentPathMapper) {
        this.inventory = Enforce.notNull(inventory, "inventory cannot be null");
        this.inventoryBuilder = Enforce.notNull(inventoryBuilder, "inventoryBuilder cannot be null");
        this.versionBuilder = Enforce.notNull(versionBuilder, "versionBuilder cannot be null");
        this.contentPathMapper = Enforce.notNull(contentPathMapper, "contentPathMapper cannot be null");
        this.objectId = inventory.getId();
        this.mutableHead = inventoryBuilder.hasMutableHead();
        this.logicalPathConstraints = LogicalPathConstraints.constraints();
    }

    /**
     * Constructs a new {@link Inventory} that contains a new {@link Version} based on the changes that were recorded.
     * After calling this method, the InventoryUpdater instance should NOT be used again.
     *
     * @param createdTimestamp when the version was created
     * @param versionInfo information about the version
     * @return new inventory
     */
    public Inventory buildNewInventory(OffsetDateTime createdTimestamp, VersionInfo versionInfo) {
        lock.lock();
        try {
            return inventoryBuilder
                    .addHeadVersion(versionBuilder
                            .versionInfo(versionInfo)
                            .created(createdTimestamp)
                            .build())
                    .build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Upgrades the inventory to the current default OCFL version if applicable. An inventory is only upgraded if
     * its version is prior to the configured default OCFL version, and object upgrades on write are enabled.
     *
     * @param config the OCFL configuration
     * @return true if the inventory is upgraded; false otherwise
     */
    public boolean upgradeInventory(OcflConfig config) {
        lock.lock();
        try {
            if (config.isUpgradeObjectsOnWrite()
                    && inventoryBuilder
                                    .getType()
                                    .compareTo(config.getOcflVersion().getInventoryType())
                            < 0) {
                inventoryBuilder.type(config.getOcflVersion().getInventoryType());
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds a file. If there is already a file with the same digest in the manifest, only the state is updated.
     *
     * @param fileId the file's digest
     * @param logicalPath the logical path to insert the file at within the object
     * @param options options
     * @return details about the file if it was added to the manifest
     */
    public AddFileResult addFile(String fileId, String logicalPath, OcflOption... options) {
        lock.lock();
        try {
            logicalPathConstraints.apply(logicalPath);

            overwriteProtection(logicalPath, options);
            versionBuilder.validateNonConflictingPath(logicalPath);

            var oldFileId = versionBuilder.getFileId(logicalPath);

            if (fileId.equalsIgnoreCase(oldFileId)) {
                var contentPath = contentPathMapper.fromLogicalPath(logicalPath);
                if (inventoryBuilder.containsContentPath(contentPath)) {
                    // This means that the exact same file was added multiple times and it is being used as the source
                    // of the file content
                    return new AddFileResult(contentPath, pathUnderContentDir(contentPath));
                }
            }

            // This is the case when the same logical path was added multiple times, but the content changed
            if (oldFileId != null) {
                versionBuilder.removeLogicalPath(logicalPath);
                removeFileFromManifest(oldFileId);
            }

            String contentPath = null;

            if (!inventoryBuilder.containsFileId(fileId)) {
                contentPath = contentPathMapper.fromLogicalPath(logicalPath);
                inventoryBuilder.addFileToManifest(fileId, contentPath);
            }

            versionBuilder.addFile(fileId, logicalPath);

            return new AddFileResult(contentPath, pathUnderContentDir(contentPath));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Maps the logical path to a content path and returns the part of the content path that's under the
     * content directory.
     *
     * @param logicalPath the logical path
     * @return content path part that's under the content directory
     */
    public String innerContentPath(String logicalPath) {
        return pathUnderContentDir(contentPathMapper.fromLogicalPath(logicalPath));
    }

    /**
     * Adds an entry to the fixity block. An entry is not added if the algorithm is the same as the inventory's algorithm.
     *
     * @param logicalPath the file's logical path
     * @param algorithm algorithm used to calculate the digest
     * @param digest the digest value
     */
    public void addFixity(String logicalPath, DigestAlgorithm algorithm, String digest) {
        lock.lock();
        try {
            if (algorithm.equals(inventory.getDigestAlgorithm())) {
                return;
            }

            var fileId = versionBuilder.getFileId(logicalPath);

            if (fileId != null) {
                inventoryBuilder.getContentPaths(fileId).forEach(contentPath -> {
                    inventoryBuilder.addFixityForFile(contentPath, algorithm, digest);
                });
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the fixity digest for the specified file or null.
     *
     * @param logicalPath the logical path to the file
     * @param algorithm the digest algorithm
     * @return the digest or null
     */
    public String getFixityDigest(String logicalPath, DigestAlgorithm algorithm) {
        lock.lock();
        try {
            if (inventory.getDigestAlgorithm().equals(algorithm)) {
                return versionBuilder.getFileId(logicalPath);
            }

            String digest = null;
            var fileId = versionBuilder.getFileId(logicalPath);

            if (fileId != null) {
                digest = inventoryBuilder.getFileFixity(fileId, algorithm);
            }

            return digest;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes all entries from the fixity block.
     */
    public void clearFixity() {
        lock.lock();
        try {
            inventoryBuilder.clearFixity();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a file from the current version. If the file was added in the same version, it is also removed from the
     * manifest.
     *
     * @param logicalPath logical path to the file
     * @return files that were removed from the manifest
     */
    public Set<RemoveFileResult> removeFile(String logicalPath) {
        lock.lock();
        try {
            var fileId = versionBuilder.removeLogicalPath(logicalPath);
            return removeFileFromManifestWithResults(fileId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Renames a file in the current version to a new logical path. If there is an existing file at the new logical path,
     * and {@link OcflOption#OVERWRITE} is specified, then the existing file is replaced. If the replaced file was originally
     * added in the current version, then it is also removed from the manifest.
     *
     * @param srcLogicalPath current logical path
     * @param dstLogicalPath new logical path
     * @param options options
     * @return files that were removed from the manifest
     */
    public Set<RemoveFileResult> renameFile(String srcLogicalPath, String dstLogicalPath, OcflOption... options) {
        lock.lock();
        try {
            logicalPathConstraints.apply(dstLogicalPath);

            var srcDigest = versionBuilder.getFileId(srcLogicalPath);

            if (srcDigest == null) {
                throw new OcflInputException(
                        String.format("The following path was not found in object %s: %s", objectId, srcLogicalPath));
            }

            overwriteProtection(dstLogicalPath, options);
            versionBuilder.validateNonConflictingPath(dstLogicalPath);

            var dstFileId = versionBuilder.getFileId(dstLogicalPath);

            versionBuilder.removeLogicalPath(srcLogicalPath);
            versionBuilder.removeLogicalPath(dstLogicalPath);
            versionBuilder.addFile(srcDigest, dstLogicalPath);

            return removeFileFromManifestWithResults(dstFileId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reinstates a file from a previous version to the current version. If there is an existing file at the new logical path,
     * and {@link OcflOption#OVERWRITE} is specified, then the existing file is replaced. If the replaced file was originally
     * added in the current version, then it is also removed from the manifest.
     *
     * @param sourceVersion the version number the source logical path corresponds to
     * @param srcLogicalPath the source logical path of the file to reinstate
     * @param dstLogicalPath the destination logical path to reinstate the file at
     * @param options options
     * @return files that were removed from the manifest
     */
    public Set<RemoveFileResult> reinstateFile(
            VersionNum sourceVersion, String srcLogicalPath, String dstLogicalPath, OcflOption... options) {
        lock.lock();
        try {
            logicalPathConstraints.apply(dstLogicalPath);

            var srcDigest = getDigestFromVersion(sourceVersion, srcLogicalPath);

            if (srcDigest == null) {
                throw new OcflInputException(String.format(
                        "Object %s version %s does not contain a file at %s", objectId, sourceVersion, srcLogicalPath));
            }

            overwriteProtection(dstLogicalPath, options);
            versionBuilder.validateNonConflictingPath(dstLogicalPath);

            var dstFileId = versionBuilder.getFileId(dstLogicalPath);

            versionBuilder.removeLogicalPath(dstLogicalPath);
            versionBuilder.addFile(srcDigest, dstLogicalPath);

            return removeFileFromManifestWithResults(dstFileId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes all of the files from the version's state.
     */
    public void clearState() {
        lock.lock();
        try {
            var state = new HashSet<>(versionBuilder.getInvertedState().keySet());
            state.forEach(this::removeFile);
        } finally {
            lock.unlock();
        }
    }

    private String getDigestFromVersion(VersionNum versionNum, String logicalPath) {
        String digest = null;

        if (inventory != null) {
            var version = inventory.getVersion(versionNum);

            if (version != null) {
                digest = version.getFileId(logicalPath);
            }
        }

        return digest;
    }

    private Set<RemoveFileResult> removeFileFromManifestWithResults(String fileId) {
        var results = new HashSet<RemoveFileResult>();

        if (fileId != null) {
            var removePaths = removeFileFromManifest(fileId);
            removePaths.forEach(removePath -> {
                results.add(new RemoveFileResult(removePath, pathUnderContentDir(removePath)));
            });
        }

        return results;
    }

    private Set<String> removeFileFromManifest(String fileId) {
        if (mutableHead) {
            return removeFileFromManifest(fileId, OcflConstants.MUTABLE_HEAD_VERSION_PATH);
        } else {
            return removeFileFromManifest(fileId, inventory.nextVersionNum().toString() + "/");
        }
    }

    private Set<String> removeFileFromManifest(String fileId, String prefix) {
        var contentPaths = inventoryBuilder.getContentPaths(fileId);
        var removePaths = new HashSet<String>();

        contentPaths.forEach(contentPath -> {
            if (contentPath.startsWith(prefix) && !versionBuilder.containsFileId(fileId)) {
                inventoryBuilder.removeFileId(fileId);
                removePaths.add(contentPath);
            }
        });

        return removePaths;
    }

    private void overwriteProtection(String logicalPath, OcflOption... options) {
        if (versionBuilder.containsLogicalPath(logicalPath) && !OcflOption.contains(OcflOption.OVERWRITE, options)) {
            throw new OverwriteException(String.format(
                    "There is already a file at %s in object %s. Use OcflOption.OVERWRITE to overwrite it.",
                    logicalPath, objectId));
        }
    }

    private String pathUnderContentDir(String contentPath) {
        if (contentPath == null) {
            return null;
        }
        var content = inventory.resolveContentDirectory() + "/";
        var startIndex = contentPath.indexOf(content);
        return contentPath.substring(startIndex + content.length());
    }

    /**
     * Indicates the result of an add file operation.
     */
    public static class AddFileResult {

        private final boolean isNew;
        private final String contentPath;
        private final String pathUnderContentDir;

        private AddFileResult(String contentPath, String pathUnderContentDir) {
            this.isNew = contentPath != null;
            this.contentPath = contentPath;
            this.pathUnderContentDir = pathUnderContentDir;
        }

        /**
         * @return true if the file was added to the manifest
         */
        public boolean isNew() {
            return isNew;
        }

        /**
         * The content path of the file
         *
         * @return null if the file is not new
         */
        public String getContentPath() {
            return contentPath;
        }

        /**
         * The portion of the content path that's under the content directory
         *
         * @return null if the file is not new
         */
        public String getPathUnderContentDir() {
            return pathUnderContentDir;
        }
    }

    /**
     * Indicates that a file was removed from the manifest.
     */
    public static class RemoveFileResult {

        private final String contentPath;
        private final String pathUnderContentDir;

        private RemoveFileResult(String contentPath, String pathUnderContentDir) {
            this.contentPath = contentPath;
            this.pathUnderContentDir = pathUnderContentDir;
        }

        /**
         * @return the content path
         */
        public String getContentPath() {
            return contentPath;
        }

        /**
         * @return the portion of the content path that's under the content directory
         */
        public String getPathUnderContentDir() {
            return pathUnderContentDir;
        }
    }
}
