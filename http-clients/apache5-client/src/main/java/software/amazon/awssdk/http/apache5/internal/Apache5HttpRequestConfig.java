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

package software.amazon.awssdk.http.apache5.internal;

import java.time.Duration;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.http.apache5.ProxyConfiguration;

/**
 * Configuration needed when building an Apache request. Note that at this time, we only support client level configuration so
 * all of these settings are supplied when creating the client.
 */
@SdkInternalApi
public final class Apache5HttpRequestConfig {

    private final Duration socketTimeout;
    private final Duration connectionTimeout;
    private final Duration connectionAcquireTimeout;
    private final boolean expectContinueEnabled;
    private final ProxyConfiguration proxyConfiguration;

    private Apache5HttpRequestConfig(Builder builder) {
        this.socketTimeout = builder.socketTimeout;
        this.connectionTimeout = builder.connectionTimeout;
        this.connectionAcquireTimeout = builder.connectionAcquireTimeout;
        this.expectContinueEnabled = builder.expectContinueEnabled;
        this.proxyConfiguration = builder.proxyConfiguration;
    }

    public Duration socketTimeout() {
        return socketTimeout;
    }

    public Duration connectionTimeout() {
        return connectionTimeout;
    }

    public Duration connectionAcquireTimeout() {
        return connectionAcquireTimeout;
    }

    public boolean expectContinueEnabled() {
        return expectContinueEnabled;
    }

    public ProxyConfiguration proxyConfiguration() {
        return proxyConfiguration;
    }

    /**
     * @return Builder instance to construct a {@link Apache5HttpRequestConfig}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for a {@link Apache5HttpRequestConfig}.
     */
    public static final class Builder {

        private Duration socketTimeout;
        private Duration connectionTimeout;
        private Duration connectionAcquireTimeout;
        private boolean expectContinueEnabled;
        private ProxyConfiguration proxyConfiguration;

        private Builder() {
        }

        public Builder socketTimeout(Duration socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }

        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder connectionAcquireTimeout(Duration connectionAcquireTimeout) {
            this.connectionAcquireTimeout = connectionAcquireTimeout;
            return this;
        }

        public Builder expectContinueEnabled(boolean expectContinueEnabled) {
            this.expectContinueEnabled = expectContinueEnabled;
            return this;
        }

        public Builder proxyConfiguration(ProxyConfiguration proxyConfiguration) {
            this.proxyConfiguration = proxyConfiguration;
            return this;
        }

        /**
         * @return An immutable {@link Apache5HttpRequestConfig} object.
         */
        public Apache5HttpRequestConfig build() {
            return new Apache5HttpRequestConfig(this);
        }
    }

}
