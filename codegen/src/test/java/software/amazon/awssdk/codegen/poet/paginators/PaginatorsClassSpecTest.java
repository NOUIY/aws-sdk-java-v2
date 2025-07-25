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

package software.amazon.awssdk.codegen.poet.paginators;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.service.PaginatorDefinition;
import software.amazon.awssdk.codegen.poet.ClientTestModels;
import software.amazon.awssdk.codegen.validation.ModelInvalidException;

public class PaginatorsClassSpecTest {
    @Test
    public void constructor_unknownOperationName_throws() {
        assertThatThrownBy(() -> new TestPaginatorSpec(ClientTestModels.awsJsonServiceModels(),
                                                       "~~DoesNotExist",
                                                       new PaginatorDefinition()))
            .isInstanceOf(ModelInvalidException.class)
            .hasMessageContaining("Invalid paginator definition - "
                                  + "The service model does not model the referenced operation '~~DoesNotExist'");
    }

    private static class TestPaginatorSpec extends PaginatorsClassSpec {
        TestPaginatorSpec(IntermediateModel model, String c2jOperationName, PaginatorDefinition paginatorDefinition) {
            super(model, c2jOperationName, paginatorDefinition);
        }

        @Override
        public TypeSpec poetSpec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassName className() {
            throw new UnsupportedOperationException();
        }
    }
}
