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

package software.amazon.awssdk.codegen.poet.rules;

import static org.hamcrest.MatcherAssert.assertThat;
import static software.amazon.awssdk.codegen.poet.PoetMatchers.generatesTo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.codegen.poet.ClassSpec;
import software.amazon.awssdk.codegen.poet.ClientTestModels;

public class EndpointRulesClientTestSpecTest {
    @Test
    public void endpointRulesTestClass() {
        ClassSpec endpointProviderSpec = new EndpointRulesClientTestSpec(ClientTestModels.queryServiceModels());
        assertThat(endpointProviderSpec, generatesTo("endpoint-rules-test-class.java"));
    }

    @Test
    public void endpointProviderTestClassWithStringArray() {
        ClassSpec endpointProviderSpec = new EndpointProviderTestSpec(ClientTestModels.stringArrayServiceModels());
        assertThat(endpointProviderSpec, generatesTo("endpoint-rules-stringarray-test-class.java"));
    }

    @Test
    public void endpointProviderTestClassWithUnknownProperties() {
        ClassSpec endpointProviderSpec = new EndpointProviderTestSpec(ClientTestModels.queryServiceModelsWithUnknownEndpointProperties());
        assertThat(endpointProviderSpec, generatesTo("endpoint-rules-unknown-property-test-class.java"));
    }

    @Test
    public void endpointProviderTestClassWithMetricValues() {
        ClassSpec endpointProviderSpec = new EndpointProviderTestSpec(ClientTestModels.queryServiceModelsWithUnknownEndpointMetricValues());
        assertThat(endpointProviderSpec, generatesTo("endpoint-rules-metric-values-test-class.java"));
    }
}
