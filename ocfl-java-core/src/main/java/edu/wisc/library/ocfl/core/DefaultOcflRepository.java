/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 University of Wisconsin Board of Regents
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

package edu.wisc.library.ocfl.core;

import edu.wisc.library.ocfl.api.OcflConfig;
import edu.wisc.library.ocfl.api.OcflObjectUpdater;
import edu.wisc.library.ocfl.api.OcflOption;
import edu.wisc.library.ocfl.api.OcflRepository;
import edu.wisc.library.ocfl.api.exception.AlreadyExistsException;
import edu.wisc.library.ocfl.api.exception.NotFoundException;
import edu.wisc.library.ocfl.api.exception.ObjectOutOfSyncException;
import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.exception.OcflStateException;
import edu.wisc.library.ocfl.api.model.FileChangeHistory;
import edu.wisc.library.ocfl.api.model.ObjectDetails;
import edu.wisc.library.ocfl.api.model.ObjectVersionId;
import edu.wisc.library.ocfl.api.model.OcflObjectVersion;
import edu.wisc.library.ocfl.api.model.OcflObjectVersionFile;
import edu.wisc.library.ocfl.api.model.VersionDetails;
import edu.wisc.library.ocfl.api.model.VersionNum;
import edu.wisc.library.ocfl.api.model.VersionInfo;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.inventory.AddFileProcessor;
import edu.wisc.library.ocfl.core.inventory.InventoryMapper;
import edu.wisc.library.ocfl.core.inventory.InventoryUpdater;
import edu.wisc.library.ocfl.core.inventory.SidecarMapper;
import edu.wisc.library.ocfl.core.lock.ObjectLock;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.RevisionNum;
import edu.wisc.library.ocfl.core.path.ContentPathMapper;
import edu.wisc.library.ocfl.core.path.constraint.ContentPathConstraintProcessor;
import edu.wisc.library.ocfl.core.path.mapper.LogicalPathMapper;
import edu.wisc.library.ocfl.core.storage.OcflStorage;
import edu.wisc.library.ocfl.core.util.DigestUtil;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.ResponseMapper;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import edu.wisc.library.ocfl.core.validation.InventoryValidator;
import edu.wisc.library.ocfl.core.validation.ObjectValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Primary implementation of the OcflRepository API. It is storage agnostic. It is typically instantiated using
 * OcflRepositoryBuilder.
 *
 * @see OcflRepositoryBuilder
 */
public class DefaultOcflRepository implements OcflRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOcflRepository.class);

    protected final OcflStorage storage;
    protected final InventoryMapper inventoryMapper;
    protected final Path workDir;
    protected final ObjectLock objectLock;
    protected final ObjectValidator objectValidator;
    protected final ResponseMapper responseMapper;
    protected final InventoryUpdater.Builder inventoryUpdaterBuilder;
    protected final AddFileProcessor.Builder addFileProcessorBuilder;

    protected final OcflConfig config;

    private Clock clock;

    private boolean closed = false;

    /**
     * @see OcflRepositoryBuilder
     *
     * @param storage storage layer
     * @param workDir path to the directory to use for assembling ocfl versions
     * @param objectLock locking client
     * @param inventoryMapper object mapper for serializing inventories
     * @param logicalPathMapper logical path mapper
     * @param contentPathConstraintProcessor content path constraint processor
     * @param config ocfl defaults configuration
     */
    public DefaultOcflRepository(OcflStorage storage, Path workDir,
                                 ObjectLock objectLock,
                                 InventoryMapper inventoryMapper,
                                 LogicalPathMapper logicalPathMapper,
                                 ContentPathConstraintProcessor contentPathConstraintProcessor,
                                 OcflConfig config) {
        this.storage = Enforce.notNull(storage, "storage cannot be null");
        this.workDir = Enforce.notNull(workDir, "workDir cannot be null");
        this.objectLock = Enforce.notNull(objectLock, "objectLock cannot be null");
        this.inventoryMapper = Enforce.notNull(inventoryMapper, "inventoryMapper cannot be null");
        this.config = Enforce.notNull(config, "config cannot be null");

        inventoryUpdaterBuilder = InventoryUpdater.builder().contentPathMapperBuilder(
                ContentPathMapper.builder()
                        .logicalPathMapper(logicalPathMapper)
                        .contentPathConstraintProcessor(contentPathConstraintProcessor));

        objectValidator = new ObjectValidator(inventoryMapper);
        responseMapper = new ResponseMapper();
        clock = Clock.systemUTC();

        addFileProcessorBuilder = AddFileProcessor.builder();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId putObject(ObjectVersionId objectVersionId, Path path, VersionInfo versionInfo, OcflOption... options) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        Enforce.notNull(path, "path cannot be null");

        LOG.debug("Putting object at <{}> into OCFL repo under id <{}>", path, objectVersionId.getObjectId());

        var inventory = loadInventoryWithDefault(objectVersionId);
        ensureNoMutableHead(inventory);
        enforceObjectVersionForUpdate(objectVersionId, inventory);

        var inventoryUpdater = inventoryUpdaterBuilder.buildBlankState(inventory);

        var stagingDir = createStagingDir(objectVersionId.getObjectId());
        var contentDir = createStagingContentDir(inventory, stagingDir);

        var fileProcessor = addFileProcessorBuilder.build(inventoryUpdater, contentDir, inventory.getDigestAlgorithm());
        fileProcessor.processPath(path, options);

        var newInventory = buildNewInventory(inventoryUpdater, versionInfo);

        try {
            writeNewVersion(newInventory, stagingDir);
            return ObjectVersionId.version(objectVersionId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId updateObject(ObjectVersionId objectVersionId, VersionInfo versionInfo, Consumer<OcflObjectUpdater> objectUpdater) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        Enforce.notNull(objectUpdater, "objectUpdater cannot be null");

        LOG.debug("Update object <{}>", objectVersionId.getObjectId());

        var inventory = loadInventoryWithDefault(objectVersionId);
        ensureNoMutableHead(inventory);
        enforceObjectVersionForUpdate(objectVersionId, inventory);

        var stagingDir = createStagingDir(objectVersionId.getObjectId());
        var contentDir = createStagingContentDir(inventory, stagingDir);

        var inventoryUpdater = inventoryUpdaterBuilder.buildCopyState(inventory);
        var addFileProcessor = addFileProcessorBuilder.build(inventoryUpdater, contentDir, inventory.getDigestAlgorithm());
        var updater = new DefaultOcflObjectUpdater(inventory, inventoryUpdater, contentDir, addFileProcessor);

        try {
            objectUpdater.accept(updater);
            var newInventory = buildNewInventory(inventoryUpdater, versionInfo);
            writeNewVersion(newInventory, stagingDir);
            return ObjectVersionId.version(objectVersionId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getObject(ObjectVersionId objectVersionId, Path outputPath) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        ensureOutputPath(outputPath);

        LOG.debug("Get object <{}> and copy to <{}>", objectVersionId, outputPath);

        var inventory = requireInventory(objectVersionId);
        var versionNum = requireVersion(objectVersionId, inventory);

        getObjectInternal(inventory, versionNum, outputPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectVersion getObject(ObjectVersionId objectVersionId) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");

        LOG.debug("Get object <{}>", objectVersionId);

        var inventory = requireInventory(objectVersionId);
        var versionNum = requireVersion(objectVersionId, inventory);

        var versionDetails = createVersionDetails(inventory, versionNum);
        var objectStreams = storage.getObjectStreams(inventory, versionNum);

        var files = versionDetails.getFiles().stream()
                .map(file -> {
                    return new OcflObjectVersionFile(file, objectStreams.get(file.getPath()));
                })
                .collect(Collectors.toMap(OcflObjectVersionFile::getPath, v -> v));

        versionDetails.setFileMap(null);

        return new OcflObjectVersion(versionDetails, files);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectDetails describeObject(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        LOG.debug("Describe object <{}>", objectId);

        var inventory = requireInventory(ObjectVersionId.head(objectId));

        return responseMapper.mapInventory(inventory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VersionDetails describeVersion(ObjectVersionId objectVersionId) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectVersionId cannot be null");

        LOG.debug("Describe version <{}>", objectVersionId);

        var inventory = requireInventory(objectVersionId);
        var versionNum = requireVersion(objectVersionId, inventory);

        return createVersionDetails(inventory, versionNum);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileChangeHistory fileChangeHistory(String objectId, String logicalPath) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");
        Enforce.notBlank(logicalPath, "logicalPath cannot be blank");

        LOG.debug("Get file change history for object <{}> logical path <{}>", objectId, logicalPath);

        var inventory = requireInventory(ObjectVersionId.head(objectId));
        var changeHistory = responseMapper.fileChangeHistory(inventory, logicalPath);

        if (changeHistory.getFileChanges().isEmpty()) {
            throw new NotFoundException(String.format("The logical path %s was not found in object %s.", logicalPath, objectId));
        }

        return changeHistory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsObject(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        LOG.debug("Contains object <{}>", objectId);

        return storage.containsObject(objectId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeObject(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        LOG.info("Purge object <{}>", objectId);

        objectLock.doInWriteLock(objectId, () -> storage.purgeObject(objectId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId replicateVersionAsHead(ObjectVersionId objectVersionId, VersionInfo versionInfo) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectVersionId cannot be null");

        LOG.debug("Replicate version <{}>", objectVersionId);

        var inventory = requireInventory(objectVersionId);
        var versionNum = requireVersion(objectVersionId, inventory);

        ensureNoMutableHead(inventory);

        var inventoryUpdater = inventoryUpdaterBuilder.buildCopyState(inventory, versionNum);
        var newInventory = inventoryUpdater.buildNewInventory(now(versionInfo), versionInfo);

        var stagingDir = createStagingDir(objectVersionId.getObjectId());
        // content dir is not used but must exist
        createStagingContentDir(inventory, stagingDir);

        try {
            writeNewVersion(newInventory, stagingDir);
            return ObjectVersionId.version(objectVersionId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rollbackToVersion(ObjectVersionId objectVersionId) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectVersionId cannot be null");

        LOG.info("Rollback to version <{}>", objectVersionId);

        var inventory = requireInventory(objectVersionId);
        var versionNum = requireVersion(objectVersionId, inventory);

        if (versionNum == inventory.getHead()) {
            LOG.debug("Object {} cannot be rollback to version {} because it is already the head version.",
                    objectVersionId.getObjectId(), versionNum);
            return;
        }

        objectLock.doInWriteLock(inventory.getId(), () -> storage.rollbackToVersion(inventory, versionNum));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<String> listObjectIds() {
        ensureOpen();

        LOG.debug("List object ids");

        return storage.listObjectIds();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exportVersion(ObjectVersionId objectVersionId, Path outputPath, OcflOption... options) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        ensureExportPath(outputPath);

        var exportId = objectVersionId;

        if (objectVersionId.isHead()) {
            var inventory = requireInventory(objectVersionId);
            var headVersion = inventory.getHead();
            exportId = ObjectVersionId.version(objectVersionId.getObjectId(), headVersion);
        }

        LOG.debug("Export <{}> to <{}>", objectVersionId, outputPath);

        storage.exportVersion(exportId, outputPath);

        if (!OcflOption.contains(OcflOption.NO_VALIDATION, options)) {
            objectValidator.validateVersion(outputPath, parseInventoryForImport(outputPath, false));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exportObject(String objectId, Path outputPath, OcflOption... options) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");
        ensureExportPath(outputPath);

        var inventory = requireInventory(ObjectVersionId.head(objectId));

        LOG.debug("Export <{}> to <{}>", objectId, outputPath);

        objectLock.doInWriteLock(objectId, () -> storage.exportObject(objectId, outputPath));

        if (!OcflOption.contains(OcflOption.NO_VALIDATION, options)) {
            objectValidator.validateObject(outputPath, inventory);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void importVersion(Path versionPath, OcflOption... options) {
        ensureOpen();

        Enforce.notNull(versionPath, "versionPath cannot be null");

        var importInventory = createImportVersionInventory(versionPath);

        InventoryValidator.validateShallow(importInventory);
        if (!OcflOption.contains(OcflOption.NO_VALIDATION, options)) {
            objectValidator.validateVersion(versionPath, importInventory);
        }

        var stagingDir = createStagingDir(importInventory.getId());

        try {
            importToStaging(versionPath, stagingDir, options);
            objectLock.doInWriteLock(importInventory.getId(), () -> storage.storeNewVersion(importInventory, stagingDir));
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void importObject(Path objectPath, OcflOption... options) {
        ensureOpen();

        Enforce.notNull(objectPath, "objectPath cannot be null");

        var inventory = parseInventoryForImport(objectPath, true);
        var objectId = inventory.getId();

        if (containsObject(objectId)) {
            throw new AlreadyExistsException(String.format("Cannot import object at %s because an object already exists with ID %s.",
                    objectPath, objectId));
        }

        if (OcflOption.contains(OcflOption.NO_VALIDATION, options)) {
            InventoryValidator.validateDeep(inventory);
        } else {
            objectValidator.validateObject(objectPath, inventory);
        }

        var stagingDir = createStagingDir(objectId);

        try {
            importToStaging(objectPath, stagingDir, options);
            objectLock.doInWriteLock(objectId, () -> storage.importObject(objectId, stagingDir));
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        LOG.debug("Close OCFL repository");

        closed = true;
        storage.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflConfig config() {
        return new OcflConfig(config);
    }

    protected Inventory loadInventory(ObjectVersionId objectId) {
        return storage.loadInventory(objectId.getObjectId());
    }

    private Inventory loadInventoryWithDefault(ObjectVersionId objectId) {
        var inventory = loadInventory(objectId);
        if (inventory == null) {
            inventory = createStubInventory(objectId);
        }
        return inventory;
    }

    protected Inventory createStubInventory(ObjectVersionId objectId) {
        var objectRootPath = storage.objectRootPath(objectId.getObjectId());
        return Inventory.stubInventory(objectId.getObjectId(), config, objectRootPath);
    }

    protected Inventory requireInventory(ObjectVersionId objectId) {
        var inventory = loadInventory(objectId);
        if (inventory == null) {
            throw new NotFoundException(String.format("Object %s was not found.", objectId));
        }
        return inventory;
    }

    protected Inventory buildNewInventory(InventoryUpdater inventoryUpdater, VersionInfo versionInfo) {
        return InventoryValidator.validateShallow(inventoryUpdater.buildNewInventory(now(versionInfo), versionInfo));
    }

    private void getObjectInternal(Inventory inventory, VersionNum versionNum, Path outputPath) {
        var stagingDir = createStagingDir(inventory.getId());

        try {
            storage.reconstructObjectVersion(inventory, versionNum, stagingDir);
            FileUtil.moveDirectory(stagingDir, outputPath);
        } catch (FileAlreadyExistsException e) {
            throw new OcflIOException(e);
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    protected void writeNewVersion(Inventory inventory, Path stagingDir) {
        var finalInventory = writeInventory(inventory, stagingDir);
        objectLock.doInWriteLock(inventory.getId(), () -> storage.storeNewVersion(finalInventory, stagingDir));
    }

    protected Inventory writeInventory(Inventory inventory, Path stagingDir) {
        var inventoryPath = ObjectPaths.inventoryPath(stagingDir);
        inventoryMapper.write(inventoryPath, inventory);
        String inventoryDigest = DigestUtil.computeDigestHex(inventory.getDigestAlgorithm(), inventoryPath);
        SidecarMapper.writeSidecar(inventory, inventoryDigest, stagingDir);

        return inventory.buildFrom().currentDigest(inventoryDigest).build();
    }

    private Inventory createImportVersionInventory(Path versionPath) {
        var importInventory = parseInventoryForImport(versionPath, false);
        var objectId = importInventory.getId();

        var existingInventory = loadInventory(ObjectVersionId.head(objectId));
        String existingDigest = null;

        ensureNoMutableHead(existingInventory);

        if (existingInventory == null) {
            if (!VersionNum.V1.equals(importInventory.getHead())) {
                throw new OcflStateException(String.format("Cannot import object %s version %s from source %s." +
                                " The object doest not exist in the repository; therefore only v1 may be imported.",
                        objectId, importInventory.getHead(), versionPath));
            }
        } else {
            if (!existingInventory.getHead().nextVersionNum().equals(importInventory.getHead())) {
                throw new OcflStateException(String.format("Cannot import object %s version %s from source %s." +
                                " The import version must be the next sequential version, and the current version is %s.",
                        objectId, importInventory.getHead(), versionPath, existingInventory.getHead()));
            }

            InventoryValidator.validateCompatibleInventories(importInventory, existingInventory);
            existingDigest = existingInventory.getCurrentDigest();
        }

        var objectRootPath = storage.objectRootPath(objectId);
        return importInventory.buildFrom()
                .objectRootPath(objectRootPath)
                .previousDigest(existingDigest)
                .build();
    }

    private Inventory parseInventoryForImport(Path path, boolean lookForMutableHead) {
        Path inventoryPath = null;
        Path sidecarPath = null;
        boolean hasMutableHead = false;

        if (lookForMutableHead) {
            var mutableInventory = ObjectPaths.mutableHeadInventoryPath(path);
            if (Files.exists(mutableInventory)) {
                inventoryPath = mutableInventory;
                sidecarPath = ObjectPaths.findInventorySidecarPath(path);
                hasMutableHead = true;
            }
        }

        if (!hasMutableHead) {
            inventoryPath = ObjectPaths.inventoryPath(path);
            sidecarPath = ObjectPaths.findInventorySidecarPath(path);
        }

        Enforce.expressionTrue(Files.exists(inventoryPath), inventoryPath, "inventory.json must exist");
        Enforce.expressionTrue(Files.exists(sidecarPath), sidecarPath, "inventory sidecar must exist");

        if (hasMutableHead) {
            // TODO this is not technically the correct revision, but it should not cause a problem for now
            return inventoryMapper.readMutableHead(path.toString(), SidecarMapper.readDigest(sidecarPath), RevisionNum.R1, inventoryPath);
        } else {
            return inventoryMapper.read(path.toString(), SidecarMapper.readDigest(sidecarPath), inventoryPath);
        }
    }

    private void importToStaging(Path source, Path stagingDir, OcflOption... options) {
        if (OcflOption.contains(OcflOption.MOVE_SOURCE, options)) {
            // Delete the staging directory so that the move operation works
            UncheckedFiles.delete(stagingDir);
            try {
                FileUtil.moveDirectory(source, stagingDir);
            } catch (FileAlreadyExistsException e) {
                throw new OcflIOException(e);
            }
        } else {
            FileUtil.recursiveCopy(source, stagingDir);
        }
    }

    protected void enforceObjectVersionForUpdate(ObjectVersionId objectId, Inventory inventory) {
        if (!objectId.isHead() && !objectId.getVersionNum().equals(inventory.getHead())) {
            throw new ObjectOutOfSyncException(String.format("Cannot update object %s because the HEAD version is %s, but version %s was specified.",
                    objectId.getObjectId(), inventory.getHead(), objectId.getVersionNum()));
        }
    }

    private void ensureNoMutableHead(Inventory inventory) {
        if (inventory != null && inventory.hasMutableHead()) {
            throw new OcflStateException(String.format("Cannot create a new version of object %s because it has an active mutable HEAD.",
                    inventory.getId()));
        }
    }

    private VersionNum requireVersion(ObjectVersionId objectId, Inventory inventory) {
        if (objectId.isHead()) {
            return inventory.getHead();
        }

        if (inventory.getVersion(objectId.getVersionNum()) == null) {
            throw new NotFoundException(String.format("Object %s version %s was not found.",
                    objectId.getObjectId(), objectId.getVersionNum()));
        }

        return objectId.getVersionNum();
    }

    protected Path createStagingDir(String objectId) {
        return FileUtil.createObjectTempDir(workDir, objectId);
    }

    private Path createStagingContentDir(Inventory inventory, Path stagingDir) {
        return UncheckedFiles.createDirectories(resolveContentDir(inventory, stagingDir));
    }

    protected Path resolveContentDir(Inventory inventory, Path parent) {
        return parent.resolve(inventory.resolveContentDirectory());
    }

    private VersionDetails createVersionDetails(Inventory inventory, VersionNum versionNum) {
        var version = inventory.getVersion(versionNum);
        return responseMapper.mapVersion(inventory, versionNum, version);
    }

    protected OffsetDateTime now(VersionInfo versionInfo) {
        if (versionInfo != null && versionInfo.getCreated() != null) {
            return versionInfo.getCreated();
        }
        return OffsetDateTime.now(clock);
    }

    protected void ensureOpen() {
        if (closed) {
            throw new OcflStateException(DefaultOcflRepository.class.getName() + " is closed.");
        }
    }

    private void ensureOutputPath(Path outputPath) {
        Enforce.notNull(outputPath, "outputPath cannot be null");
        Enforce.expressionTrue(Files.notExists(outputPath), outputPath, "outputPath must not exist");
        Enforce.expressionTrue(Files.exists(outputPath.getParent()), outputPath, "outputPath parent must exist");
        Enforce.expressionTrue(Files.isDirectory(outputPath.getParent()), outputPath, "outputPath parent must be a directory");
    }

    private void ensureExportPath(Path outputPath) {
        Enforce.notNull(outputPath, "outputPath cannot be null");
        if (Files.exists(outputPath)) {
            Enforce.expressionTrue(Files.isDirectory(outputPath), outputPath, "outputPath must be a directory");
        }
        UncheckedFiles.createDirectories(outputPath);
    }

    /**
     * This is used to manipulate the clock for testing purposes.
     *
     * @param clock clock
     */
    public void setClock(Clock clock) {
        this.clock = Enforce.notNull(clock, "clock cannot be null");
    }

}
