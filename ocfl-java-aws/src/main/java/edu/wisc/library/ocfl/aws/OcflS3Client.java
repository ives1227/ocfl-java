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

package edu.wisc.library.ocfl.aws;

import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.exception.OcflInputException;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.storage.cloud.CloudClient;
import edu.wisc.library.ocfl.core.storage.cloud.CloudObjectKey;
import edu.wisc.library.ocfl.core.storage.cloud.HeadResult;
import edu.wisc.library.ocfl.core.storage.cloud.KeyNotFoundException;
import edu.wisc.library.ocfl.core.storage.cloud.ListResult;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CloudClient implementation that uses Amazon's S3 synchronous v2 client
 */
public class OcflS3Client implements CloudClient {

    private static final Logger LOG = LoggerFactory.getLogger(OcflS3Client.class);

    private static final int KB = 1024;
    private static final int MB = 1024 * KB;
    private static final long GB = 1024 * MB;
    private static final long TB = 1024 * GB;

    private static final long MAX_FILE_BYTES = 5 * TB;
    private static final int MAX_PART_BYTES = 100 * MB;
    private static final int PART_SIZE_BYTES = 10 * MB;
    private static final int MAX_PARTS = 100;
    private static final int PART_SIZE_INCREMENT = 10;
    private static final int PARTS_INCREMENT = 100;

    private final S3Client s3Client;
    private final String bucket;
    private final String repoPrefix;
    private final CloudObjectKey.Builder keyBuilder;

    /**
     * Used to create a new OcflS3Client instance.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @see OcflS3Client#builder()
     *
     * @param s3Client aws sdk s3 client
     * @param bucket s3 bucket
     */
    public OcflS3Client(S3Client s3Client, String bucket) {
        this(s3Client, bucket, "");
    }

    /**
     * @see OcflS3Client#builder()
     *
     * @param s3Client aws sdk s3 client
     * @param bucket s3 bucket
     * @param prefix key prefix
     */
    public OcflS3Client(S3Client s3Client, String bucket, String prefix) {
        this.s3Client = Enforce.notNull(s3Client, "s3Client cannot be null");
        this.bucket = Enforce.notBlank(bucket, "bucket cannot be blank");
        this.repoPrefix = sanitizeRepoPrefix(Enforce.notNull(prefix, "prefix cannot be null"));
        this.keyBuilder = CloudObjectKey.builder().prefix(repoPrefix);
    }

    private static String sanitizeRepoPrefix(String repoPrefix) {
        return repoPrefix.substring(0, indexLastNonSlash(repoPrefix));
    }

    private static int indexLastNonSlash(String string) {
        for (int i = string.length(); i > 0; i--) {
            if (string.charAt(i - 1) != '/') {
                return i;
            }
        }
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String bucket() {
        return bucket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String prefix() {
        return repoPrefix;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudObjectKey uploadFile(Path srcPath, String dstPath) {
        return uploadFile(srcPath, dstPath, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudObjectKey uploadFile(Path srcPath, String dstPath, String contentType) {
        var fileSize = UncheckedFiles.size(srcPath);
        var dstKey = keyBuilder.buildFromPath(dstPath);

        if (fileSize >= MAX_FILE_BYTES) {
            throw new OcflInputException(String.format("Cannot store file %s because it exceeds the maximum file size.", srcPath));
        }

        if (fileSize > MAX_PART_BYTES) {
            multipartUpload(srcPath, dstKey, fileSize, contentType);
        } else {
            LOG.debug("Uploading {} to bucket {} key {} size {}", srcPath, bucket, dstKey, fileSize);

            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(dstKey.getKey())
                    .contentLength(fileSize)
                    .contentType(contentType)
                    .build(), srcPath);
        }

        return dstKey;
    }

    // TODO reduce memory consumption?
    private void multipartUpload(Path srcPath, CloudObjectKey dstKey, long fileSize, String contentType) {
        var partSize = determinePartSize(fileSize);

        LOG.debug("Multipart upload of {} to bucket {} key {}. File size: {}; part size: {}", srcPath, bucket, dstKey,
                fileSize, partSize);

        var uploadId = beginMultipartUpload(dstKey, contentType);

        var completedParts = new ArrayList<CompletedPart>();

        try {
            try (var channel = FileChannel.open(srcPath, StandardOpenOption.READ)) {
                var buffer = ByteBuffer.allocate(partSize);
                var i = 1;

                while (channel.read(buffer) > 0) {
                    buffer.flip();

                    var partResponse = s3Client.uploadPart(UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(dstKey.getKey())
                            .uploadId(uploadId)
                            .partNumber(i)
                            // TODO entire part is in memory. stream part to file first?
                            .build(), RequestBody.fromByteBuffer(buffer));

                    completedParts.add(CompletedPart.builder()
                            .partNumber(i)
                            .eTag(partResponse.eTag())
                            .build());

                    buffer.clear();
                    i++;
                }
            } catch (IOException e) {
                throw new OcflIOException(e);
            }

            completeMultipartUpload(uploadId, dstKey, completedParts);
        } catch (RuntimeException e) {
            abortMultipartUpload(uploadId, dstKey);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudObjectKey uploadBytes(String dstPath, byte[] bytes, String contentType) {
        var dstKey = keyBuilder.buildFromPath(dstPath);
        LOG.debug("Writing string to bucket {} key {}", bucket, dstKey);

        s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(dstKey.getKey())
                .contentType(contentType)
                .build(), RequestBody.fromBytes(bytes));

        return dstKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudObjectKey copyObject(String srcPath, String dstPath) {
        var srcKey = keyBuilder.buildFromPath(srcPath);
        var dstKey = keyBuilder.buildFromPath(dstPath);

        LOG.debug("Copying {} to {} in bucket {}", srcKey, dstKey, bucket);

        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .destinationBucket(bucket)
                    .destinationKey(dstKey.getKey())
                    .copySource(keyWithBucketName(srcKey.getKey()))
                    .build());
        } catch (NoSuchKeyException e) {
            throw new KeyNotFoundException(e);
        } catch (SdkException e) {
            // TODO verify class and message
            if (e.getMessage().contains("copy source is larger than the maximum allowable size")) {
                multipartCopy(srcKey, dstKey);
            } else {
                throw e;
            }
        }

        return dstKey;
    }

    private void multipartCopy(CloudObjectKey srcKey, CloudObjectKey dstKey) {
        var head = headObject(srcKey);
        var fileSize = head.contentLength();
        var partSize = determinePartSize(fileSize);

        LOG.debug("Multipart copy of {} to {} in bucket {}: File size {}; part size: {}", srcKey, dstKey, bucket,
                fileSize, partSize);

        var uploadId = beginMultipartUpload(dstKey, null);

        try {
            var completedParts = new ArrayList<CompletedPart>();
            var part = 1;
            var position = 0L;

            while (position < fileSize) {
                var end = Math.min(fileSize - 1, part * partSize - 1);
                var partResponse = s3Client.uploadPartCopy(UploadPartCopyRequest.builder()
                        .bucket(bucket)
                        .key(dstKey.getKey())
                        .copySource(keyWithBucketName(srcKey.getKey()))
                        .partNumber(part)
                        .uploadId(uploadId)
                        .copySourceRange(String.format("bytes=%s-%s", position, end))
                        .build());

                completedParts.add(CompletedPart.builder()
                        .partNumber(part)
                        .eTag(partResponse.copyPartResult().eTag())
                        .build());

                part++;
                position = end + 1;
            }

            completeMultipartUpload(uploadId, dstKey, completedParts);
        } catch (RuntimeException e) {
            abortMultipartUpload(uploadId, dstKey);
            throw e;
        }
    }

    private HeadObjectResponse headObject(CloudObjectKey key) {
        return s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key.getKey())
                .build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Path downloadFile(String srcPath, Path dstPath) {
        var srcKey = keyBuilder.buildFromPath(srcPath);
        LOG.debug("Downloading bucket {} key {} to {}", bucket, srcKey, dstPath);

        try {
            s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(srcKey.getKey())
                    .build(), dstPath);
        } catch (NoSuchKeyException e) {
            throw new KeyNotFoundException(e);
        }

        return dstPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream downloadStream(String srcPath) {
        var srcKey = keyBuilder.buildFromPath(srcPath);
        LOG.debug("Streaming bucket {} key {}", bucket, srcKey);

        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(srcKey.getKey())
                    .build());
        } catch (NoSuchKeyException e) {
            throw new KeyNotFoundException(String.format("Key %s not found in bucket %s.", srcKey, bucket), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String downloadString(String srcPath) {
        try (var stream = downloadStream(srcPath)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OcflIOException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeadResult head(String path) {
        var key = keyBuilder.buildFromPath(path);

        try {
            var s3Result = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.getKey())
                    .build());

            return new HeadResult()
                    .setContentEncoding(s3Result.contentEncoding())
                    .setContentLength(s3Result.contentLength())
                    .setETag(s3Result.eTag())
                    .setLastModified(s3Result.lastModified());
        } catch (NoSuchKeyException e) {
            throw new KeyNotFoundException(String.format("Key %s not found in bucket %s.", key, bucket), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListResult list(String prefix) {
        var prefixedPrefix = keyBuilder.buildFromPath(prefix);
        return toListResult(s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefixedPrefix.getKey())
                .build()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListResult listDirectory(String path) {
        var prefix = keyBuilder.buildFromPath(path).getKey();

        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        LOG.debug("Listing directory {} in bucket {}", prefix, bucket);

        return toListResult(s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .delimiter("/")
                .prefix(prefix)
                .build()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deletePath(String path) {
        LOG.debug("Deleting path {} in bucket {}", path, bucket);

        var keys = list(path).getObjects().stream()
                .map(ListResult.ObjectListing::getKey)
                .collect(Collectors.toList());

        deleteObjectsInternal(keys);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteObjects(Collection<String> objectPaths) {
        if (!objectPaths.isEmpty()) {
            var objectKeys = objectPaths.stream()
                    .map(keyBuilder::buildFromPath)
                    .collect(Collectors.toList());

            deleteObjectsInternal(objectKeys);
        }
    }

    private void deleteObjectsInternal(Collection<CloudObjectKey> objectKeys) {
        LOG.debug("Deleting objects in bucket {}: {}", bucket, objectKeys);

        if (!objectKeys.isEmpty()) {
            var objectIds = objectKeys.stream()
                    .map(key -> ObjectIdentifier.builder().key(key.getKey()).build())
                    .collect(Collectors.toList());

            s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder()
                            .objects(objectIds)
                            .build())
                    .build());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void safeDeleteObjects(String... objectPaths) {
        safeDeleteObjects(Arrays.asList(objectPaths));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void safeDeleteObjects(Collection<String> objectPaths) {
        try {
            deleteObjects(objectPaths);
        } catch (RuntimeException e) {
            LOG.error("Failed to cleanup objects in bucket {}: {}", bucket, objectPaths, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean bucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(bucket)
                    .build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        }
    }

    private String beginMultipartUpload(CloudObjectKey key, String contentType) {
        return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key.getKey())
                .contentType(contentType)
                .build()).uploadId();
    }

    private void completeMultipartUpload(String uploadId, CloudObjectKey key, List<CompletedPart> parts) {
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key.getKey())
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(parts)
                        .build())
                .build());
    }

    private void abortMultipartUpload(String uploadId, CloudObjectKey key) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key.getKey())
                    .uploadId(uploadId)
                    .build());
        } catch (RuntimeException e) {
            LOG.error("Failed to abort multipart upload. Bucket: {}; Key: {}; Upload Id: {}", bucket, key, uploadId, e);
        }
    }

    private String keyWithBucketName(String key) {
        return SdkHttpUtils.urlEncode(String.format("%s/%s", bucket, key));
    }

    private int determinePartSize(long fileSize) {
        var partSize = PART_SIZE_BYTES;
        var maxParts = MAX_PARTS;

        while (fileSize / partSize > maxParts) {
            partSize += PART_SIZE_INCREMENT;

            if (partSize > MAX_PART_BYTES) {
                maxParts += PARTS_INCREMENT;
                partSize /= 2;
            }
        }

        return partSize;
    }

    private ListResult toListResult(ListObjectsV2Response s3Result) {
        var prefixLength = s3Result.prefix() == null ? 0 : s3Result.prefix().length();
        var repoPrefixLength = repoPrefix.isBlank() ? 0 : repoPrefix.length() + 1;

        var objects = s3Result.contents().stream().map(o -> {
            var key = o.key();
            return new ListResult.ObjectListing()
                    .setKey(keyBuilder.buildFromKey(key))
                    .setKeySuffix(key.substring(prefixLength));
        }).collect(Collectors.toList());

        var dirs = s3Result.commonPrefixes().stream()
                .filter(p -> p.prefix() != null)
                .map(p -> {
                    var path = p.prefix();
                    return new ListResult.DirectoryListing()
                            .setPath(path.substring(repoPrefixLength));
                })
                .collect(Collectors.toList());

        return new ListResult()
                .setObjects(objects)
                .setDirectories(dirs);
    }

    public static class Builder {
        private S3Client s3Client;
        private String bucket;
        private String repoPrefix;

        /**
         * The AWS SDK s3 client. Required.
         *
         * @param s3Client s3 client
         * @return builder
         */
        public Builder s3Client(S3Client s3Client) {
            this.s3Client = Enforce.notNull(s3Client, "s3Client cannot be null");
            return this;
        }

        /**
         * The S3 bucket to use. Required.
         *
         * @param bucket s3 bucket
         * @return builder
         */
        public Builder bucket(String bucket) {
            this.bucket = Enforce.notBlank(bucket, "bucket cannot be blank");
            return this;
        }

        /**
         * The key prefix to use for the repository. Optional.
         *
         * @param repoPrefix key prefix
         * @return builder
         */
        public Builder repoPrefix(String repoPrefix) {
            this.repoPrefix = repoPrefix;
            return this;
        }

        /**
         * Constructs a new OcflS3Client. s3Client and bucket must be set.
         *
         * @return OcflS3Client
         */
        public OcflS3Client build() {
            var prefix = repoPrefix == null ? "" : repoPrefix;
            return new OcflS3Client(s3Client, bucket, prefix);
        }
    }

}
