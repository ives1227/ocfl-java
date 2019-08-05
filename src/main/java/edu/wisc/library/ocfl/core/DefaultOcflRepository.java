package edu.wisc.library.ocfl.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.wisc.library.ocfl.api.OcflObjectReader;
import edu.wisc.library.ocfl.api.OcflObjectUpdater;
import edu.wisc.library.ocfl.api.OcflRepository;
import edu.wisc.library.ocfl.api.model.CommitInfo;
import edu.wisc.library.ocfl.api.model.ObjectDetails;
import edu.wisc.library.ocfl.api.model.ObjectId;
import edu.wisc.library.ocfl.api.model.VersionDetails;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.lock.ObjectLock;
import edu.wisc.library.ocfl.core.model.DigestAlgorithm;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.InventoryType;
import edu.wisc.library.ocfl.core.model.VersionId;
import edu.wisc.library.ocfl.core.util.FileUtil;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class DefaultOcflRepository implements OcflRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOcflRepository.class);

    private OcflStorage storage;
    private ObjectMapper objectMapper;
    private Path workDir;
    private ObjectLock objectLock;
    private ResponseMapper responseMapper;

    private Set<DigestAlgorithm> fixityAlgorithms;
    private InventoryType inventoryType;
    private DigestAlgorithm digestAlgorithm;
    private String contentDirectory;

    public DefaultOcflRepository(OcflStorage storage, ObjectMapper objectMapper,
                                 Path workDir, ObjectLock objectLock, Set<DigestAlgorithm> fixityAlgorithms,
                                 InventoryType inventoryType, DigestAlgorithm digestAlgorithm,
                                 String contentDirectory) {
        this.storage = Enforce.notNull(storage, "storage cannot be null");
        this.objectMapper = Enforce.notNull(objectMapper, "objectMapper cannot be null");
        this.workDir = Enforce.notNull(workDir, "workDir cannot be null");
        this.objectLock = Enforce.notNull(objectLock, "objectLock cannot be null");
        this.fixityAlgorithms = Enforce.notNull(fixityAlgorithms, "fixityAlgorithms cannot be null");
        this.inventoryType = Enforce.notNull(inventoryType, "inventoryType cannot be null");
        this.digestAlgorithm = Enforce.notNull(digestAlgorithm, "digestAlgorithm cannot be null");
        this.contentDirectory = Enforce.notBlank(contentDirectory, "contentDirectory cannot be blank");

        responseMapper = new ResponseMapper();
    }

    @Override
    public ObjectId putObject(ObjectId objectId, Path path, CommitInfo commitInfo) {
        // TODO additional id restrictions? eg must contain at least 1 alpha numeric character, max length?
        Enforce.notNull(objectId, "objectId cannot be null");
        Enforce.notNull(path, "path cannot be null");

        // It is necessary to lock at the start of an update operation so that the diffs are computed correctly
        return objectLock.doInLock(objectId.getObjectId(), () -> {
            var inventory = storage.loadInventory(objectId.getObjectId());

            if (inventory == null) {
                inventory = new Inventory()
                        .setId(objectId.getObjectId())
                        .setType(inventoryType)
                        .setDigestAlgorithm(digestAlgorithm)
                        .setContentDirectory(contentDirectory);
            }

            enforceObjectVersionForUpdate(objectId, inventory);

            // Only needs to be cleaned on failure
            var stagingDir = stageNewVersion(inventory, path, commitInfo);

            try {
                storage.storeNewVersion(inventory, stagingDir);
                return ObjectId.version(objectId.getObjectId(), inventory.getHead().toString());
            } catch (RuntimeException e) {
                FileUtil.safeDeletePath(stagingDir);
                throw e;
            }
        });
    }

    @Override
    public ObjectId updateObject(ObjectId objectId, CommitInfo commitInfo, Consumer<OcflObjectUpdater> objectUpdater) {
        Enforce.notNull(objectId, "objectId cannot be null");
        Enforce.notNull(objectUpdater, "objectUpdater cannot be null");

        // It is necessary to lock at the start of an update operation so that the diffs are computed correctly
        return objectLock.doInLock(objectId.getObjectId(), () -> {
            var inventory = requireInventory(objectId);
            enforceObjectVersionForUpdate(objectId, inventory);

            var inventoryUpdater = InventoryUpdater.newVersionForUpdate(inventory, fixityAlgorithms);
            inventoryUpdater.addCommitInfo(commitInfo);

            // Only needs to be cleaned on failure
            var stagingDir = FileUtil.createTempDir(workDir, inventory.getId());
            var contentDir = FileUtil.createDirectories(stagingDir.resolve(inventory.getContentDirectory()));

            try {
                objectUpdater.accept(new DefaultOcflObjectUpdater(inventoryUpdater, contentDir));
                writeInventory(inventory, stagingDir);
                storage.storeNewVersion(inventory, stagingDir);
                return ObjectId.version(objectId.getObjectId(), inventory.getHead().toString());
            } catch (RuntimeException e) {
                FileUtil.safeDeletePath(stagingDir);
                throw e;
            }
        });
    }

    @Override
    public void getObject(ObjectId objectId, Path outputPath) {
        Enforce.notNull(objectId, "objectId cannot be null");
        Enforce.notNull(outputPath, "outputPath cannot be null");
        Enforce.expressionTrue(Files.exists(outputPath), outputPath, "outputPath must exist");
        Enforce.expressionTrue(Files.isDirectory(outputPath), outputPath, "outputPath must be a directory");

        var inventory = requireInventory(objectId);

        requireVersion(objectId, inventory);
        var versionId = resolveVersion(objectId, inventory);

        getObjectInternal(inventory, versionId, outputPath);
    }

    @Override
    public void readObject(ObjectId objectId, Consumer<OcflObjectReader> objectReader) {
        Enforce.notNull(objectId, "objectId cannot be null");
        Enforce.notNull(objectReader, "objectReader cannot be null");

        var inventory = requireInventory(objectId);

        requireVersion(objectId, inventory);

        var stagingDir = FileUtil.createTempDir(workDir, inventory.getId());
        var versionId = resolveVersion(objectId, inventory);

        try (var reader = new DefaultOcflObjectReader(
                storage, inventory, versionId, stagingDir)) {
            objectReader.accept(reader);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    @Override
    public ObjectDetails describeObject(String objectId) {
        Enforce.notBlank(objectId, "objectId cannot be blank");

        var inventory = requireInventory(ObjectId.head(objectId));

        return responseMapper.mapInventory(inventory);
    }

    @Override
    public VersionDetails describeVersion(ObjectId objectId) {
        Enforce.notNull(objectId, "objectId cannot be null");

        var inventory = requireInventory(objectId);
        requireVersion(objectId, inventory);

        var version = inventory.getVersions().get(VersionId.fromValue(objectId.getVersionId()));

        return responseMapper.mapVersion(inventory, objectId.getVersionId(), version);
    }

    private Inventory requireInventory(ObjectId objectId) {
        var inventory = storage.loadInventory(objectId.getObjectId());
        if (inventory == null) {
            // TODO modeled exception
            throw new IllegalArgumentException(String.format("Object %s was not found.", objectId));
        }
        return inventory;
    }

    private Path stageNewVersion(Inventory inventory, Path sourcePath, CommitInfo commitInfo) {
        var stagingDir = FileUtil.createTempDir(workDir, inventory.getId());

        var inventoryUpdater = InventoryUpdater.newVersionForInsert(inventory, fixityAlgorithms);
        inventoryUpdater.addCommitInfo(commitInfo);

        var files = FileUtil.findFiles(sourcePath);
        // TODO handle case when no files. is valid?

        var contentDir = FileUtil.createDirectories(stagingDir.resolve(inventory.getContentDirectory()));

        for (var file : files) {
            var relativePath = sourcePath.relativize(file);
            var isNewFile = inventoryUpdater.addFile(file, relativePath);

            if (isNewFile) {
                FileUtil.copyFileMakeParents(file, contentDir.resolve(relativePath));
            }
        }

        writeInventory(inventory, stagingDir);

        return stagingDir;
    }

    private void getObjectInternal(Inventory inventory, VersionId versionId, Path outputPath) {
        var fileMap = resolveVersionContents(inventory, versionId);
        // Only needs to be cleaned on failure
        var stagingDir = FileUtil.createTempDir(workDir, inventory.getId());

        try {
            storage.reconstructObjectVersion(inventory, fileMap, stagingDir);

            FileUtil.moveDirectory(stagingDir, outputPath);
        } catch (RuntimeException e) {
            FileUtil.safeDeletePath(stagingDir);
            throw e;
        }
    }

    private Map<String, Set<String>> resolveVersionContents(Inventory inventory, VersionId versionId) {
        var manifest = inventory.getManifest();
        var version = inventory.getVersions().get(versionId);

        var fileMap = new HashMap<String, Set<String>>();

        version.getState().forEach((id, files) -> {
            if (!manifest.containsKey(id)) {
                throw new IllegalStateException(String.format("Missing manifest entry for %s in object %s.",
                        id, inventory.getId()));
            }

            var source = manifest.get(id).iterator().next();
            fileMap.put(source, files);
        });

        return fileMap;
    }

    private void writeInventory(Inventory inventory, Path tempDir) {
        try {
            var inventoryPath = tempDir.resolve(OcflConstants.INVENTORY_FILE);
            objectMapper.writeValue(inventoryPath.toFile(), inventory);
            String inventoryDigest = computeDigest(inventoryPath, inventory.getDigestAlgorithm());
            Files.writeString(
                    tempDir.resolve(OcflConstants.INVENTORY_FILE + "." + inventory.getDigestAlgorithm().getValue()),
                    inventoryDigest + "\t" + OcflConstants.INVENTORY_FILE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String computeDigest(Path path, DigestAlgorithm algorithm) {
        try {
            return Hex.encodeHexString(DigestUtils.digest(algorithm.getMessageDigest(), path.toFile()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void enforceObjectVersionForUpdate(ObjectId objectId, Inventory inventory) {
        if (!objectId.isHead() && !objectId.getVersionId().equals(inventory.getHead().toString())) {
            throw new IllegalStateException(String.format("Cannot update object %s because the HEAD version is %s, but version %s was specified.",
                    objectId.getObjectId(), inventory.getHead(), objectId.getVersionId()));
        }
    }


    private VersionId resolveVersion(ObjectId objectId, Inventory inventory) {
        var versionId = inventory.getHead();

        if (!objectId.isHead()) {
            versionId = VersionId.fromValue(objectId.getVersionId());
        }

        return versionId;
    }

    private void requireVersion(ObjectId objectId, Inventory inventory) {
        if (objectId.isHead()) {
            return;
        }

        if (!inventory.getVersions().containsKey(VersionId.fromValue(objectId.getVersionId()))) {
            // TODO modeled exception
            throw new IllegalArgumentException(String.format("Object %s version %s was not found.",
                    objectId.getObjectId(), objectId.getVersionId()));
        }
    }

}
