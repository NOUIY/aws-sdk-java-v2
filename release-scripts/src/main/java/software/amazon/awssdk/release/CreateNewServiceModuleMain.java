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

package software.amazon.awssdk.release;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.internal.CodegenNamingUtils;

/**
 * A command line application to create a new, empty service. This *does not* add the new service to the shared pom.xmls, that
 * should be done via {@link FinalizeNewServiceModuleMain}.
 *
 * Example usage:
 * <pre>
 * mvn exec:java -pl :release-scripts \
 *     -Dexec.mainClass="software.amazon.awssdk.release.CreateNewServiceModuleMain" \
 *     -Dexec.args="--maven-project-root /path/to/root
 *                  --maven-project-version 2.1.4-SNAPSHOT
 *                  --service-id 'Service Id'
 *                  --service-module-name service-module-name
 *                  --service-protocol json"
 * </pre>
 *
 * <p>By default the service new pom will include a dependency to the http-auth-aws module, this is only needed if the service
 * has one or more operations signed by any of the aws algorithms, e.g., sigv4 or sigv4a, but not needed if the service uses,
 * say, bearer auth (e.g., codecatalyst at the moment). Excluding this can be done by adding the
 * {@code --exclude-internal-dependency http-auth-aws} switch. For example
 * <pre>
 * mvn exec:java -pl :release-scripts \
 *     -Dexec.mainClass="software.amazon.awssdk.release.CreateNewServiceModuleMain" \
 *     -Dexec.args="--maven-project-root /path/to/root
 *                  --maven-project-version 2.1.4-SNAPSHOT
 *                  --service-id 'Service Id'
 *                  --service-module-name service-module-name
 *                  --service-protocol json
 *                  --exclude-internal-dependency http-auth-aws"
 * </pre>
 */
public class CreateNewServiceModuleMain extends Cli {

    private static final Set<String> DEFAULT_INTERNAL_DEPENDENCIES = toSet("http-auth-aws");

    private static final Map<String, Set<String>> ADDITIONAL_INTERNAL_PROTOCOL_DEPENDENCIES;

    static {
        // Note, the protocol keys must match the values returned from transformSpecialProtocols
        ADDITIONAL_INTERNAL_PROTOCOL_DEPENDENCIES = new HashMap<>();
        ADDITIONAL_INTERNAL_PROTOCOL_DEPENDENCIES.put("smithy-rpcv2", toSet("aws-json-protocol"));
    }

    private CreateNewServiceModuleMain() {
        super(requiredOption("service-module-name", "The name of the service module to be created."),
              requiredOption("service-id", "The service ID of the service module to be created."),
              requiredOption("service-protocol", "The protocol of the service module to be created."),
              requiredOption("maven-project-root", "The root directory for the maven project."),
              requiredOption("maven-project-version", "The maven version of the service module to be created."),
              optionalMultiValueOption("include-internal-dependency", "Includes an internal dependency from new service pom."),
              optionalMultiValueOption("exclude-internal-dependency", "Excludes an internal dependency from new service pom."));
    }

    public static void main(String[] args) {
        new CreateNewServiceModuleMain().run(args);
    }

    static Set<String> toSet(String... args) {
        Set<String> result = new LinkedHashSet<>();
        for (String arg : args) {
            result.add(arg);
        }
        return Collections.unmodifiableSet(result);

    }

    static List<String> toList(String[] optionValues) {
        if (optionValues == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(optionValues);
    }

    static Set<String> computeInternalDependencies(String serviceProtocol, List<String> includes, List<String> excludes) {
        Set<String> result = new LinkedHashSet<>(DEFAULT_INTERNAL_DEPENDENCIES);
        result.addAll(includes);
        result.addAll(ADDITIONAL_INTERNAL_PROTOCOL_DEPENDENCIES.getOrDefault(serviceProtocol, Collections.emptySet()));
        excludes.forEach(result::remove);
        return Collections.unmodifiableSet(result);
    }

    @Override
    protected void run(CommandLine commandLine) throws Exception {
        new NewServiceCreator(commandLine).run();
    }

    private static class NewServiceCreator {
        private final Path mavenProjectRoot;
        private final String mavenProjectVersion;
        private final String serviceModuleName;
        private final String serviceId;
        private final String serviceProtocol;
        private final Set<String> internalDependencies;

        private NewServiceCreator(CommandLine commandLine) {
            this.mavenProjectRoot = Paths.get(commandLine.getOptionValue("maven-project-root").trim());
            this.mavenProjectVersion = commandLine.getOptionValue("maven-project-version").trim();
            this.serviceModuleName = commandLine.getOptionValue("service-module-name").trim();
            this.serviceId = commandLine.getOptionValue("service-id").trim();
            this.serviceProtocol = transformSpecialProtocols(commandLine.getOptionValue("service-protocol").trim());
            this.internalDependencies = computeInternalDependencies(serviceProtocol,
                                                                    toList(commandLine
                                                                               .getOptionValues("include-internal-dependency")),
                                                                    toList(commandLine
                                                                               .getOptionValues("exclude-internal-dependency")));
            Validate.isTrue(Files.exists(mavenProjectRoot), "Project root does not exist: " + mavenProjectRoot);
        }

        private String transformSpecialProtocols(String protocol) {
            switch (protocol) {
                case "ec2": return "aws-query";
                case "rest-xml": return "aws-xml";
                case "rest-json": return "aws-json";
                case "smithy-rpc-v2-cbor": return "smithy-rpcv2";
                default: return "aws-" + protocol;
            }
        }

        public void run() throws Exception {
            Path servicesRoot = mavenProjectRoot.resolve("services");
            Path templateModulePath = servicesRoot.resolve("new-service-template");
            Path newServiceModulePath = servicesRoot.resolve(serviceModuleName);

            createNewModuleFromTemplate(templateModulePath, newServiceModulePath);
            replaceTemplatePlaceholders(newServiceModulePath);

            Path newServicePom = newServiceModulePath.resolve("pom.xml");
            new AddInternalDependenciesTransformer(internalDependencies).transform(newServicePom);
        }

        private void createNewModuleFromTemplate(Path templateModulePath, Path newServiceModule) throws IOException {
            FileUtils.copyDirectory(templateModulePath.toFile(), newServiceModule.toFile());
        }

        private void replaceTemplatePlaceholders(Path newServiceModule) throws IOException {
            Files.walkFileTree(newServiceModule, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    replacePlaceholdersInFile(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private void replacePlaceholdersInFile(Path file) throws IOException {
            String fileContents = new String(Files.readAllBytes(file), UTF_8);
            String newFileContents = replacePlaceholders(fileContents);
            Files.write(file, newFileContents.getBytes(UTF_8));
        }

        private String replacePlaceholders(String line) {
            String[] searchList = {
                "{{MVN_ARTIFACT_ID}}",
                "{{MVN_NAME}}",
                "{{MVN_VERSION}}",
                "{{PROTOCOL}}"
            };
            String[] replaceList = {
                serviceModuleName,
                mavenName(serviceId),
                mavenProjectVersion,
                serviceProtocol
            };
            return StringUtils.replaceEach(line, searchList, replaceList);
        }

        private String mavenName(String serviceId) {
            return Stream.of(CodegenNamingUtils.splitOnWordBoundaries(serviceId))
                         .map(StringUtils::capitalize)
                         .collect(Collectors.joining(" "));
        }
    }

    static class AddInternalDependenciesTransformer extends PomTransformer {
        private final Set<String> internalDependencies;

        AddInternalDependenciesTransformer(Set<String> internalDependencies) {
            this.internalDependencies = internalDependencies;
        }

        @Override
        protected void updateDocument(Document doc) {
            Node project = findChild(doc, "project");
            Node dependencies = findChild(project, "dependencies");
            for (String internalDependency : internalDependencies) {
                dependencies.appendChild(sdkDependencyElement(doc, internalDependency));
            }
        }
    }
}
