package edu.wisc.library.ocfl.core.util;

import edu.wisc.library.ocfl.api.model.*;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.Version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Used to map Inventory objects to API response objects
 */
public class ResponseMapper {

    public ObjectDetails mapInventory(Inventory inventory) {
        var details = new ObjectDetails()
                .setId(inventory.getId())
                .setHeadVersionId(inventory.getHead().toString());

        var versionMap = inventory.getVersions().entrySet().stream()
                .map(entry -> mapVersion(inventory, entry.getKey().toString(), entry.getValue()))
                .collect(Collectors.toMap(VersionDetails::getVersionId, Function.identity()));

        details.setVersions(versionMap);

        return details;
    }

    public VersionDetails mapVersion(Inventory inventory, String versionId, Version version) {
        var details = new VersionDetails()
                .setObjectId(inventory.getId())
                .setVersionId(versionId)
                .setCreated(version.getCreated())
                .setFiles(mapFileDetails(inventory, version));

        var commitInfo = new CommitInfo().setMessage(version.getMessage());

        if (version.getUser() != null) {
            commitInfo.setUser(new User()
                    .setName(version.getUser().getName())
                    .setAddress(version.getUser().getAddress()));
        }

        details.setCommitInfo(commitInfo);

        return details;
    }

    // TODO this isn't very efficient
    private Collection<FileDetails> mapFileDetails(Inventory inventory, Version version) {
        var fileDetails = new ArrayList<FileDetails>();
        var fileFixityMap = new HashMap<String, FileDetails>();

        var digestAlgorithm = inventory.getDigestAlgorithm();

        version.getState().forEach((digest, paths) -> {
            paths.forEach(path -> {
                var details = new FileDetails().setFilePath(path)
                        .addDigest(digestAlgorithm.getValue(), digest);
                fileFixityMap.put(digest, details);
                fileDetails.add(details);
            });
        });

        inventory.getFixity().forEach((algorithm, digests) -> {
            if (algorithm != digestAlgorithm) {
                digests.forEach((digest, paths) -> {
                    paths.forEach(path -> {
                        var details = fileFixityMap.get(inventory.getFileId(path));
                        if (details != null) {
                            details.addDigest(algorithm.getValue(), digest);
                        }
                    });
                });
            }
        });

        return fileDetails;
    }

}