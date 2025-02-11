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

package edu.wisc.library.ocfl.core.inventory;

import edu.wisc.library.ocfl.api.DigestAlgorithmRegistry;
import edu.wisc.library.ocfl.api.OcflConstants;
import edu.wisc.library.ocfl.api.exception.CorruptObjectException;
import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.exception.OcflNoSuchFileException;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.core.ObjectPaths;
import edu.wisc.library.ocfl.core.model.Inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SidecarMapper {

    private SidecarMapper() {

    }

    public static void writeSidecar(Inventory inventory, String digest, Path dstDirectory) {
        try {
            var sidecarPath = ObjectPaths.inventorySidecarPath(dstDirectory, inventory);
            Files.writeString(sidecarPath, digest + "\t" + OcflConstants.INVENTORY_FILE);
        } catch (IOException e) {
            throw new OcflIOException(e);
        }
    }

    public static String readDigestRequired(Path sidecarPath) {
        try {
            return readDigestOptional(sidecarPath);
        } catch (OcflNoSuchFileException e) {
            throw new CorruptObjectException("Inventory sidecar does not exist at " + sidecarPath);
        }
    }

    public static String readDigestOptional(Path sidecarPath) {
        try {
            var parts = Files.readString(sidecarPath).split("\\s+");
            if (parts.length != 2) {
                throw new CorruptObjectException("Invalid inventory sidecar file: " + sidecarPath);
            }
            return parts[0];
        } catch (NoSuchFileException e) {
            throw new OcflNoSuchFileException(e);
        } catch (IOException e) {
            throw new OcflIOException(e);
        }
    }

    public static DigestAlgorithm getDigestAlgorithmFromSidecar(String inventorySidecarPath) {
        return getDigestAlgorithmFromSidecar(Paths.get(inventorySidecarPath));
    }

    public static DigestAlgorithm getDigestAlgorithmFromSidecar(Path inventorySidecarPath) {
        var value = inventorySidecarPath.getFileName().toString()
                .substring(OcflConstants.INVENTORY_SIDECAR_PREFIX.length());
        var algorithm = DigestAlgorithmRegistry.getAlgorithm(value);

        if (!OcflConstants.ALLOWED_DIGEST_ALGORITHMS.contains(algorithm)) {
            throw new CorruptObjectException(String.format("Inventory sidecar at <%s> specifies digest algorithm %s. Allowed algorithms are: %s",
                    inventorySidecarPath, value, OcflConstants.ALLOWED_DIGEST_ALGORITHMS));
        }

        return algorithm;
    }

}
