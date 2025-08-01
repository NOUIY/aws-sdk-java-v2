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

package software.amazon.awssdk.codegen.poet.client;

import static javax.lang.model.element.Modifier.PRIVATE;
import static software.amazon.awssdk.codegen.poet.PoetUtils.classNameFromFqcn;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.auth.signer.EventStreamAws4Signer;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.codegen.model.config.customization.S3ArnableFieldConfig;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.MemberModel;
import software.amazon.awssdk.codegen.model.intermediate.OperationModel;
import software.amazon.awssdk.codegen.model.intermediate.ShapeModel;
import software.amazon.awssdk.codegen.model.service.ClientContextParam;
import software.amazon.awssdk.codegen.model.service.HostPrefixProcessor;
import software.amazon.awssdk.codegen.poet.PoetExtension;
import software.amazon.awssdk.codegen.poet.PoetUtils;
import software.amazon.awssdk.codegen.poet.rules.EndpointRulesSpecUtils;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.awssdk.utils.CollectionUtils;
import software.amazon.awssdk.utils.HostnameValidator;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.awssdk.utils.Validate;

public final class ClientClassUtils {

    private ClientClassUtils() {
    }

    static MethodSpec consumerBuilderVariant(MethodSpec spec, String javadoc) {
        Validate.validState(spec.parameters.size() > 0, "A first parameter is required to generate a consumer-builder method.");
        Validate.validState(spec.parameters.get(0).type instanceof ClassName, "The first parameter must be a class.");

        ParameterSpec firstParameter = spec.parameters.get(0);
        ClassName firstParameterClass = (ClassName) firstParameter.type;
        TypeName consumer = ParameterizedTypeName.get(ClassName.get(Consumer.class), firstParameterClass.nestedClass("Builder"));

        MethodSpec.Builder result = MethodSpec.methodBuilder(spec.name)
                                              .returns(spec.returnType)
                                              .addExceptions(spec.exceptions)
                                              .addAnnotations(spec.annotations)
                                              .addJavadoc(javadoc)
                                              .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                                              .addTypeVariables(spec.typeVariables)
                                              .addParameter(ParameterSpec.builder(consumer, firstParameter.name).build());


        // Parameters
        StringBuilder methodBody = new StringBuilder("return $L($T.builder().applyMutation($L).build()");
        for (int i = 1; i < spec.parameters.size(); i++) {
            ParameterSpec parameter = spec.parameters.get(i);
            methodBody.append(", ").append(parameter.name);
            result.addParameter(parameter);
        }
        methodBody.append(")");

        result.addStatement(methodBody.toString(), spec.name, firstParameterClass, firstParameter.name);

        return result.build();
    }

    static MethodSpec applySignerOverrideMethod(PoetExtension poetExtensions, IntermediateModel model) {
        String signerOverrideVariable = "signerOverride";

        TypeVariableName typeVariableName =
            TypeVariableName.get("T", poetExtensions.getModelClass(model.getSdkRequestBaseClassName()));

        ParameterizedTypeName parameterizedTypeName = ParameterizedTypeName
            .get(ClassName.get(Consumer.class), ClassName.get(AwsRequestOverrideConfiguration.Builder.class));

        CodeBlock codeBlock = CodeBlock.builder()
                                       .beginControlFlow("if (request.overrideConfiguration().flatMap(c -> c.signer())"
                                                         + ".isPresent())")
                                       .addStatement("return request")
                                       .endControlFlow()
                                       .addStatement("$T $L = b -> b.signer(signer).build()",
                                                     parameterizedTypeName,
                                                     signerOverrideVariable)
                                       .addStatement("$1T overrideConfiguration =\n"
                                                     + "            request.overrideConfiguration().map(c -> c.toBuilder()"
                                                     + ".applyMutation($2L).build())\n"
                                                     + "            .orElse((AwsRequestOverrideConfiguration.builder()"
                                                     + ".applyMutation($2L).build()))",
                                                     AwsRequestOverrideConfiguration.class,
                                                     signerOverrideVariable)
                                       .addStatement("return (T) request.toBuilder().overrideConfiguration"
                                                     + "(overrideConfiguration).build()")
                                       .build();

        return MethodSpec.methodBuilder("applySignerOverride")
                         .addModifiers(Modifier.PRIVATE)
                         .addParameter(typeVariableName, "request")
                         .addParameter(Signer.class, "signer")
                         .addTypeVariable(typeVariableName)
                         .addCode(codeBlock)
                         .returns(typeVariableName)
                         .build();
    }

    static CodeBlock callApplySignerOverrideMethod(OperationModel opModel) {
        CodeBlock.Builder code = CodeBlock.builder();
        ShapeModel inputShape = opModel.getInputShape();

        if (inputShape.getRequestSignerClassFqcn() != null) {
            code.addStatement("$1L = applySignerOverride($1L, $2T.create())",
                              opModel.getInput().getVariableName(),
                              PoetUtils.classNameFromFqcn(inputShape.getRequestSignerClassFqcn()));
        } else if (opModel.hasEventStreamInput()) {
            code.addStatement("$1L = applySignerOverride($1L, $2T.create())",
                              opModel.getInput().getVariableName(), EventStreamAws4Signer.class);
        }

        return code.build();
    }

    static CodeBlock addEndpointTraitCode(OperationModel opModel) {
        CodeBlock.Builder builder = CodeBlock.builder();

        if (opModel.getEndpointTrait() != null && !StringUtils.isEmpty(opModel.getEndpointTrait().getHostPrefix())) {
            String hostPrefix = opModel.getEndpointTrait().getHostPrefix();
            HostPrefixProcessor processor = new HostPrefixProcessor(hostPrefix);

            builder.addStatement("String hostPrefix = $S", hostPrefix);

            if (processor.c2jNames().isEmpty()) {
                builder.addStatement("String resolvedHostExpression = $S", processor.hostWithStringSpecifier());
            } else {
                processor.c2jNames()
                         .forEach(name -> builder.addStatement("$T.validateHostnameCompliant($L, $S, $S)",
                                                               HostnameValidator.class,
                                                               inputShapeMemberGetter(opModel, name),
                                                               name, opModel.getInput().getVariableName()));

                builder.addStatement("String resolvedHostExpression = String.format($S, $L)",
                                     processor.hostWithStringSpecifier(),
                                     processor.c2jNames().stream()
                                              .map(n -> inputShapeMemberGetter(opModel, n))
                                              .collect(Collectors.joining(",")));
            }
        }

        return builder.build();
    }

    static MethodSpec updateSdkClientConfigurationMethod(
        TypeName serviceClientConfigurationBuilderClassName, IntermediateModel model) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("updateSdkClientConfiguration")
                                               .addModifiers(PRIVATE)
                                               .addParameter(SdkRequest.class, "request")
                                               .addParameter(SdkClientConfiguration.class, "clientConfiguration")
                                               .returns(SdkClientConfiguration.class);

        builder.addStatement("$T plugins = request.overrideConfiguration()\n"
                             + ".map(c -> c.plugins()).orElse(Collections.emptyList())",
                             ParameterizedTypeName.get(List.class, SdkPlugin.class));

        builder.beginControlFlow("if (plugins.isEmpty())")
               .addStatement("return clientConfiguration")
               .endControlFlow();

        builder.addStatement("$T configuration = clientConfiguration.toBuilder()", SdkClientConfiguration.Builder.class)
               .addStatement("$1T serviceConfigBuilder = new $1T(configuration)", serviceClientConfigurationBuilderClassName)
               .beginControlFlow("for ($T plugin : plugins)", SdkPlugin.class)
               .addStatement("plugin.configureClient(serviceConfigBuilder)")
               .endControlFlow();
        EndpointRulesSpecUtils endpointRulesSpecUtils = new EndpointRulesSpecUtils(model);

        if (model.getCustomizationConfig() == null ||
            CollectionUtils.isNullOrEmpty(model.getCustomizationConfig().getCustomClientContextParams())) {
            builder.addStatement("updateRetryStrategyClientConfiguration(configuration)");
            builder.addStatement("return configuration.build()");
            return builder.build();
        }

        Map<String, ClientContextParam> customClientConfigParams = model.getCustomizationConfig().getCustomClientContextParams();

        builder.addCode("$1T newContextParams = configuration.option($2T.CLIENT_CONTEXT_PARAMS);\n"
                        + "$1T originalContextParams = clientConfiguration.option($2T.CLIENT_CONTEXT_PARAMS);",
                        AttributeMap.class, SdkClientOption.class);

        builder.addCode("newContextParams = (newContextParams != null) ? newContextParams : $1T.empty();\n"
                        + "originalContextParams = originalContextParams != null ? originalContextParams : $1T.empty();",
                        AttributeMap.class);

        customClientConfigParams.forEach((n, m) -> {
            String keyName = model.getNamingStrategy().getEnumValueName(n);
            builder.addStatement("$1T.validState($2T.equals(originalContextParams.get($3T.$4N), newContextParams.get($3T.$4N)),"
                                 + " $5S)",
                                 Validate.class, Objects.class, endpointRulesSpecUtils.clientContextParamsName(), keyName,
                                 keyName + " cannot be modified by request level plugins");
        });
        builder.addStatement("updateRetryStrategyClientConfiguration(configuration)");
        builder.addStatement("return configuration.build()");
        return builder.build();
    }

    static Optional<CodeBlock> addS3ArnableFieldCode(OperationModel opModel, IntermediateModel model) {
        CodeBlock.Builder builder = CodeBlock.builder();
        Map<String, S3ArnableFieldConfig> s3ArnableFields = model.getCustomizationConfig().getS3ArnableFields();

        if (s3ArnableFields != null &&
            s3ArnableFields.containsKey(opModel.getInputShape().getShapeName())) {
            S3ArnableFieldConfig s3ArnableField = s3ArnableFields.get(opModel.getInputShape().getShapeName());
            String fieldName = s3ArnableField.getField();
            MemberModel arnableMember = opModel.getInputShape().tryFindMemberModelByC2jName(fieldName, true);
            ClassName arnResourceFqcn = classNameFromFqcn(s3ArnableField.getArnResourceFqcn());

            builder.addStatement("String $N = $N.$N()", fieldName,
                                 opModel.getInput().getVariableName(), arnableMember.getFluentGetterMethodName());
            builder.addStatement("$T arn = null", Arn.class);
            builder.beginControlFlow("if ($N != null && $N.startsWith(\"arn:\"))", fieldName, fieldName)
                   .addStatement("arn = $T.fromString($N)", Arn.class, fieldName)
                   .addStatement("$T s3Resource = $T.getInstance().convertArn(arn)",
                                 classNameFromFqcn(s3ArnableField.getBaseArnResourceFqcn()),
                                 classNameFromFqcn(s3ArnableField.getArnConverterFqcn()))
                   .beginControlFlow("if (!(s3Resource instanceof $T))", arnResourceFqcn)
                   .addStatement("throw new $T(String.format(\"Unsupported ARN type: %s\", s3Resource.type()))",
                                 IllegalArgumentException.class)
                   .endControlFlow()
                   .addStatement("$T resource = ($T) s3Resource", arnResourceFqcn, arnResourceFqcn);

            Map<String, String> otherFieldsToPopulate = s3ArnableField.getOtherFieldsToPopulate();

            for (Map.Entry<String, String> entry : otherFieldsToPopulate.entrySet()) {
                MemberModel memberModel = opModel.getInputShape().tryFindMemberModelByC2jName(entry.getKey(), true);
                String variableName = memberModel.getVariable().getVariableName();
                String arnVariableName = variableName + "InArn";
                builder.addStatement("String $N = $N.$N()", variableName,
                                     opModel.getInput().getVariableName(),
                                     memberModel.getFluentGetterMethodName());
                builder.addStatement("String $N = resource.$N",
                                     arnVariableName,
                                     entry.getValue());
                builder.beginControlFlow("if ($N != null && !$N.equals($N))",
                                         variableName,
                                         variableName,
                                         arnVariableName)
                       .addStatement("throw new $T(String.format(\"%s field provided from the request (%s) is different from "
                                     + "the one in the ARN (%s)\", $S, $N, $N))",
                                     IllegalArgumentException.class,
                                     variableName,
                                     variableName, arnVariableName)
                       .endControlFlow();
            }

            builder.add("$N = $N.toBuilder().$N(resource.$N())",
                        opModel.getInput().getVariableName(),
                        opModel.getInput().getVariableName(),
                        arnableMember.getFluentSetterMethodName(),
                        s3ArnableField.getArnResourceSubstitutionGetter());

            for (Map.Entry<String, String> entry : otherFieldsToPopulate.entrySet()) {
                MemberModel memberModel = opModel.getInputShape().tryFindMemberModelByC2jName(entry.getKey(), true);
                String variableName = memberModel.getVariable().getVariableName();
                String arnVariableName = variableName + "InArn";
                builder.add(".$N($N)", memberModel.getFluentSetterMethodName(), arnVariableName);
            }

            return Optional.of(builder.addStatement(".build()").endControlFlow().build());
        }
        return Optional.empty();
    }

    /**
     * Given operation and c2j name, returns the String that represents calling the
     * c2j member's getter method in the opmodel input shape.
     *
     * For example, Operation is CreateConnection and c2j name is CatalogId,
     * returns "createConnectionRequest.catalogId()"
     */
    private static String inputShapeMemberGetter(OperationModel opModel, String c2jName) {
        return opModel.getInput().getVariableName() + "." +
               opModel.getInputShape().getMemberByC2jName(c2jName).getFluentGetterMethodName() + "()";
    }

    public static MethodSpec updateRetryStrategyClientConfigurationMethod() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("updateRetryStrategyClientConfiguration")
                                               .addModifiers(Modifier.PRIVATE)
                                               .addParameter(SdkClientConfiguration.Builder.class, "configuration");
        builder.addStatement("$T builder = configuration.asOverrideConfigurationBuilder()",
                             ClientOverrideConfiguration.Builder.class);
        builder.addStatement("$T retryMode = builder.retryMode()", RetryMode.class);
        builder.beginControlFlow("if (retryMode != null)")
               .addStatement("configuration.option($T.RETRY_STRATEGY, $T.forRetryMode(retryMode))", SdkClientOption.class,
                             AwsRetryStrategy.class);
        builder.nextControlFlow("else");
        builder.addStatement("$T<$T<?, ?>> configurator = builder.retryStrategyConfigurator()", Consumer.class,
                             RetryStrategy.Builder.class);
        builder.beginControlFlow("if (configurator != null)")
               .addStatement("$T<?, ?>  defaultBuilder = $T.defaultRetryStrategy().toBuilder()", RetryStrategy.Builder.class,
                             AwsRetryStrategy.class)
               .addStatement("configurator.accept(defaultBuilder)")
               .addStatement("configuration.option($T.RETRY_STRATEGY, defaultBuilder.build())", SdkClientOption.class);
        builder.nextControlFlow("else");
        builder.addStatement("$T retryStrategy = builder.retryStrategy()", RetryStrategy.class);
        builder.beginControlFlow("if (retryStrategy != null)")
               .addStatement("configuration.option($T.RETRY_STRATEGY, retryStrategy)", SdkClientOption.class)
               .endControlFlow();
        builder.endControlFlow();
        builder.endControlFlow();
        builder.addStatement("configuration.option($T.CONFIGURED_RETRY_MODE, null)", SdkClientOption.class);
        builder.addStatement("configuration.option($T.CONFIGURED_RETRY_STRATEGY, null)", SdkClientOption.class);
        builder.addStatement("configuration.option($T.CONFIGURED_RETRY_CONFIGURATOR, null)", SdkClientOption.class);
        return builder.build();
    }

    // According to User Agent 2.0 spec, replace spaces with underscores
    static String transformServiceId(String serviceId) {
        return serviceId.replace(" ", "_");
    }
}
