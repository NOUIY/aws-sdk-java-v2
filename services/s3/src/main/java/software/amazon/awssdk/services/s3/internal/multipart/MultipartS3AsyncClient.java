/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services.s3.internal.multipart;


import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.ApiName;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.DelegatingS3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.internal.UserAgentUtils;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Request;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.utils.Validate;

/**
 * An {@link S3AsyncClient} that automatically converts PUT, COPY, and GET requests to their respective multipart call. CRC32
 * will be enabled for the requests, unless the checksum is specified or checksum validation is disabled.
 *
 * @see MultipartConfiguration
 */
@SdkInternalApi
public final class MultipartS3AsyncClient extends DelegatingS3AsyncClient {

    private static final ApiName USER_AGENT_API_NAME = ApiName.builder().name("hll").version("s3Multipart").build();

    private final UploadObjectHelper mpuHelper;
    private final CopyObjectHelper copyObjectHelper;
    private final DownloadObjectHelper downloadObjectHelper;
    private final boolean checksumEnabled;

    private MultipartS3AsyncClient(S3AsyncClient delegate, MultipartConfiguration multipartConfiguration,
                                   boolean checksumEnabled) {
        super(delegate);
        MultipartConfiguration validConfiguration = Validate.getOrDefault(multipartConfiguration,
                                                                          MultipartConfiguration.builder()::build);
        MultipartConfigurationResolver resolver = new MultipartConfigurationResolver(validConfiguration);
        long minPartSizeInBytes = resolver.minimalPartSizeInBytes();
        long threshold = resolver.thresholdInBytes();
        long apiCallBufferSize = resolver.apiCallBufferSize();
        mpuHelper = new UploadObjectHelper(delegate, resolver);
        copyObjectHelper = new CopyObjectHelper(delegate, minPartSizeInBytes, threshold);
        downloadObjectHelper = new DownloadObjectHelper(delegate, apiCallBufferSize);
        this.checksumEnabled = checksumEnabled;
    }

    @Override
    public CompletableFuture<PutObjectResponse> putObject(PutObjectRequest putObjectRequest, AsyncRequestBody requestBody) {

        Optional<ChecksumAlgorithm> checksumAlgorithm = S3ChecksumUtils.checksumAlgorithmFromPutObjectRequest(putObjectRequest);

        if (checksumAlgorithm.isPresent()) {
            putObjectRequest = putObjectRequest.toBuilder().checksumAlgorithm(checksumAlgorithm.get()).build();
        } else if (checksumEnabled) {
            putObjectRequest = putObjectRequest.toBuilder().checksumAlgorithm(ChecksumAlgorithm.CRC32).build();
        }

        return mpuHelper.uploadObject(putObjectRequest, requestBody);
    }

    @Override
    public CompletableFuture<CopyObjectResponse> copyObject(CopyObjectRequest copyObjectRequest) {
        return copyObjectHelper.copyObject(copyObjectRequest);
    }

    @Override
    public <ReturnT> CompletableFuture<ReturnT> getObject(
        GetObjectRequest getObjectRequest, AsyncResponseTransformer<GetObjectResponse, ReturnT> asyncResponseTransformer) {
        return downloadObjectHelper.downloadObject(getObjectRequest, asyncResponseTransformer);
    }

    @Override
    public void close() {
        delegate().close();
    }

    public static MultipartS3AsyncClient create(S3AsyncClient client, MultipartConfiguration multipartConfiguration,
                                                boolean checksumEnabled) {
        S3AsyncClient clientWithUserAgent = new DelegatingS3AsyncClient(client) {
            @Override
            protected <T extends S3Request, ReturnT> CompletableFuture<ReturnT> invokeOperation(T request, Function<T,
                CompletableFuture<ReturnT>> operation) {
                T requestWithUserAgent = UserAgentUtils.applyUserAgentInfo(request, c -> c.addApiName(USER_AGENT_API_NAME));
                return operation.apply(requestWithUserAgent);
            }
        };
        return new MultipartS3AsyncClient(clientWithUserAgent, multipartConfiguration, checksumEnabled);
    }
}
