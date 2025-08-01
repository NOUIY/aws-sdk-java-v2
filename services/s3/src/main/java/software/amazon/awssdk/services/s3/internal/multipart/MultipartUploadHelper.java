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


import static software.amazon.awssdk.services.s3.internal.multipart.SdkPojoConversionUtils.toAbortMultipartUploadRequest;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.listener.PublisherListener;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.utils.CompletableFutureUtils;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Pair;

/**
 * A base class contains common logic used by {@link UploadWithUnknownContentLengthHelper} and
 * {@link UploadWithKnownContentLengthHelper}.
 */
@SdkInternalApi
public final class MultipartUploadHelper {
    private static final Logger log = Logger.loggerFor(MultipartUploadHelper.class);

    private final S3AsyncClient s3AsyncClient;
    private final GenericMultipartHelper<PutObjectRequest, PutObjectResponse> genericMultipartHelper;

    private final long maxMemoryUsageInBytes;
    private final long multipartUploadThresholdInBytes;

    public MultipartUploadHelper(S3AsyncClient s3AsyncClient,
                                 long multipartUploadThresholdInBytes,
                                 long maxMemoryUsageInBytes) {
        this.s3AsyncClient = s3AsyncClient;
        this.genericMultipartHelper = new GenericMultipartHelper<>(s3AsyncClient,
                                                                   SdkPojoConversionUtils::toAbortMultipartUploadRequest,
                                                                   SdkPojoConversionUtils::toPutObjectResponse);
        this.maxMemoryUsageInBytes = maxMemoryUsageInBytes;
        this.multipartUploadThresholdInBytes = multipartUploadThresholdInBytes;
    }

    CompletableFuture<CreateMultipartUploadResponse> createMultipartUpload(PutObjectRequest putObjectRequest,
                                                                           CompletableFuture<PutObjectResponse> returnFuture) {
        CreateMultipartUploadRequest request = SdkPojoConversionUtils.toCreateMultipartUploadRequest(putObjectRequest);
        CompletableFuture<CreateMultipartUploadResponse> createMultipartUploadFuture =
            s3AsyncClient.createMultipartUpload(request);

        // Ensure cancellations are forwarded to the createMultipartUploadFuture future
        CompletableFutureUtils.forwardExceptionTo(returnFuture, createMultipartUploadFuture);
        return createMultipartUploadFuture;
    }

    CompletableFuture<CompleteMultipartUploadResponse> completeMultipartUpload(CompletableFuture<PutObjectResponse> returnFuture,
                                 String uploadId,
                                 CompletedPart[] completedParts,
                                 PutObjectRequest putObjectRequest,
                                 long contentLength) {
        CompletableFuture<CompleteMultipartUploadResponse> future =
            genericMultipartHelper.completeMultipartUpload(putObjectRequest, uploadId, completedParts, contentLength);

        future.handle(genericMultipartHelper.handleExceptionOrResponse(putObjectRequest, returnFuture, uploadId))
              .exceptionally(throwable -> {
                  genericMultipartHelper.handleException(returnFuture, () -> "Unexpected exception occurred", throwable);
                  return null;
              });

        return future;
    }

    CompletableFuture<CompletedPart> sendIndividualUploadPartRequest(String uploadId,
                                                                     Consumer<CompletedPart> completedPartsConsumer,
                                                                     Collection<CompletableFuture<CompletedPart>> futures,
                                                                     Pair<UploadPartRequest, AsyncRequestBody> requestPair,
                                                                     PublisherListener<Long> progressListener) {
        UploadPartRequest uploadPartRequest = requestPair.left();
        Integer partNumber = uploadPartRequest.partNumber();
        Optional<Long> contentLength = requestPair.right().contentLength();
        log.debug(() -> "Sending uploadPartRequest: " + uploadPartRequest.partNumber() + " uploadId: " + uploadId + " "
                        + "contentLength " + contentLength);

        CompletableFuture<UploadPartResponse> uploadPartFuture = s3AsyncClient.uploadPart(uploadPartRequest,
                                                                                          requestPair.right());

        CompletableFuture<CompletedPart> convertFuture =
            uploadPartFuture.thenApply(uploadPartResponse -> {
                contentLength.ifPresent(progressListener::subscriberOnNext);
                return convertUploadPartResponse(completedPartsConsumer, partNumber, uploadPartResponse);
            });
        futures.add(convertFuture);
        CompletableFutureUtils.forwardExceptionTo(convertFuture, uploadPartFuture);
        return convertFuture;
    }

    void failRequestsElegantly(Collection<CompletableFuture<CompletedPart>> futures,
                               Throwable t,
                               String uploadId,
                               CompletableFuture<PutObjectResponse> returnFuture,
                               PutObjectRequest putObjectRequest) {

        try {
            genericMultipartHelper.handleException(returnFuture, () -> "Failed to send multipart upload requests", t);
            if (uploadId != null) {
                genericMultipartHelper.cleanUpParts(uploadId, toAbortMultipartUploadRequest(putObjectRequest));
            }
            cancelingOtherOngoingRequests(futures, t);
        } catch (Throwable throwable) {
            returnFuture.completeExceptionally(SdkClientException.create("Unexpected error occurred while handling the upstream "
                                                                         + "exception.", throwable));
        }

    }

    static void cancelingOtherOngoingRequests(Collection<CompletableFuture<CompletedPart>> futures, Throwable t) {
        log.trace(() -> "cancelling other ongoing requests " + futures.size());
        futures.forEach(f -> f.completeExceptionally(t));
    }

    static CompletedPart convertUploadPartResponse(Consumer<CompletedPart> consumer,
                                                   Integer partNumber,
                                                   UploadPartResponse uploadPartResponse) {
        CompletedPart completedPart = SdkPojoConversionUtils.toCompletedPart(uploadPartResponse, partNumber);

        consumer.accept(completedPart);
        return completedPart;
    }

    void uploadInOneChunk(PutObjectRequest putObjectRequest,
                          AsyncRequestBody asyncRequestBody,
                          CompletableFuture<PutObjectResponse> returnFuture) {
        CompletableFuture<PutObjectResponse> putObjectResponseCompletableFuture = s3AsyncClient.putObject(putObjectRequest,
                                                                                                          asyncRequestBody);
        CompletableFutureUtils.forwardExceptionTo(returnFuture, putObjectResponseCompletableFuture);
        CompletableFutureUtils.forwardResultTo(putObjectResponseCompletableFuture, returnFuture);
    }

    static SdkClientException contentLengthMissingForPart(int currentPartNum) {
        return SdkClientException.create("Content length is missing on the AsyncRequestBody for part number " + currentPartNum);
    }

    static SdkClientException contentLengthMismatchForPart(long expected, long actual) {
        return SdkClientException.create(String.format("Content length must not be greater than "
                                                       + "part size. Expected: %d, Actual: %d",
                                                       expected,
                                                       actual));
    }

    static SdkClientException partNumMismatch(int expectedNumParts, int actualNumParts) {
        return SdkClientException.create(String.format("The number of parts divided is "
                                                       + "not equal to the expected number of "
                                                       + "parts. Expected: %d, Actual: %d",
                                                       expectedNumParts, actualNumParts));
    }
}
