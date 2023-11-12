/*
 * Copyright (c) 2019, 2022, Gluon
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gluonhq.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.MalformedParameterizedTypeException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.gluonhq.gradle.GluonFXPlugin;
import com.gluonhq.substrate.target.WebTargetConfiguration;
import groovy.lang.Closure;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import com.gluonhq.gradle.ClientExtension;
import com.gluonhq.substrate.Constants;
import com.gluonhq.substrate.ProjectConfiguration;
import com.gluonhq.substrate.SubstrateDispatcher;
import com.gluonhq.substrate.model.Triplet;

import static com.gluonhq.gradle.GluonFXPlugin.CONFIGURATION_WEB_CLASSPATH_EXTRAS;

class ConfigBuild {

    private final Project project;
    private final ClientExtension clientExtension;

    ConfigBuild(Project project) {
        this.project = project;

        clientExtension = project.getExtensions().getByType(ClientExtension.class);
    }

    public SubstrateDispatcher createSubstrateDispatcher() throws IOException {
        Path clientPath = project
                .getLayout()
                .getBuildDirectory()
                .dir(Constants.GLUONFX_PATH)
                .get()
                .getAsFile()
                .toPath();
        project.getLogger().debug(" in directory {}", clientPath);

        return new SubstrateDispatcher(clientPath, createSubstrateConfiguration());
    }

    public void build() {
        ProjectConfiguration clientConfig = createSubstrateConfiguration();

        boolean result;
        try {
            String mainClassName = clientConfig.getMainClassName();
            String name = clientConfig.getAppName();
            for (org.gradle.api.artifacts.Configuration configuration : project.getBuildscript().getConfigurations()) {
                project.getLogger().debug("Configuration = " + configuration);
                DependencySet deps = configuration.getAllDependencies();
                project.getLogger().debug("Dependencies = " + deps);
                deps.forEach(dep -> project.getLogger().debug("Dependency = " + dep));
            }
            project.getLogger().debug("mainClassName = " + mainClassName + " and app name = " + name);

            Path buildRootPath = project
                    .getLayout()
                    .getBuildDirectory()
                    .dir(Constants.GLUONFX_PATH)
                    .get()
                    .getAsFile()
                    .toPath();
            project.getLogger().debug("BuildRoot: " + buildRootPath);

            SubstrateDispatcher dispatcher = new SubstrateDispatcher(buildRootPath, clientConfig);
            result = dispatcher.nativeCompile();
        } catch (Exception e) {
            throw new GradleException("Failed to compile", e);
        }

        if (!result) {
            throw new IllegalStateException("Compilation failed");
        }
    }

    private ProjectConfiguration createSubstrateConfiguration() {
        String mainClass = null;
        JavaApplication application = project.getExtensions().findByType(JavaApplication.class);
        if (application != null) {
            mainClass = application.getMainClass().get();
        }
        if (mainClass == null) {
            mainClass = (String) project.getProperties().get("mainClassName");
        }
        ProjectConfiguration clientConfig = new ProjectConfiguration(mainClass, getClassPath());
        clientConfig.setJavaStaticSdkVersion(clientExtension.getJavaStaticSdkVersion());
        clientConfig.setJavafxStaticSdkVersion(clientExtension.getJavafxStaticSdkVersion());

        Triplet targetTriplet;
        String target = clientExtension.getTarget().toLowerCase(Locale.ROOT);
        switch (target) {
            case Constants.PROFILE_HOST:
                targetTriplet = Triplet.fromCurrentOS();
                break;
            case Constants.PROFILE_IOS:
                targetTriplet = new Triplet(Constants.Profile.IOS);
                break;
            case Constants.PROFILE_IOS_SIM:
                targetTriplet = new Triplet(Constants.Profile.IOS_SIM);
                break;
            case Constants.PROFILE_ANDROID:
                targetTriplet = new Triplet(Constants.Profile.ANDROID);
                break;
            case Constants.PROFILE_LINUX_AARCH64:
                targetTriplet = new Triplet(Constants.Profile.LINUX_AARCH64);
                break;
            case Constants.PROFILE_WEB:
                targetTriplet = new Triplet(Constants.Profile.WEB);
                break;
            default:
                throw new RuntimeException("No valid target found for " + target);
        }
        clientConfig.setTarget(targetTriplet);

        clientConfig.setBundlesList(clientExtension.getBundlesList());
        clientConfig.setResourcesList(clientExtension.getResourcesList());
        clientConfig.setJniList(clientExtension.getJniList());
        clientConfig.setCompilerArgs(clientExtension.getCompilerArgs());
        clientConfig.setLinkerArgs(clientExtension.getLinkerArgs());
        clientConfig.setRuntimeArgs(clientExtension.getRuntimeArgs());
        clientConfig.setReflectionList(clientExtension.getReflectionList());
        String appId = clientExtension.getAppIdentifier();
        clientConfig.setAppId(appId != null ? appId : project.getGroup() + "." + project.getName());
        clientConfig.setAppName(project.getName());

        clientConfig.setGraalPath(getGraalHome());

        clientConfig.setUsePrismSW(clientExtension.isEnableSwRendering());
        clientConfig.setVerbose(clientExtension.isVerbose());

        clientConfig.setRemoteHostName(clientExtension.getRemoteHostName());
        clientConfig.setRemoteDir(clientExtension.getRemoteDir());

        clientConfig.setReleaseConfiguration(clientExtension.getReleaseConfiguration().toSubstrate());

        return clientConfig;
    }

    private String getClassPath() {
        List<Path> classPath = getClassPathFromSourceSets();
        project.getLogger().debug("Runtime classPath = " + classPath);
        return classPath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private List<Path> getClassPathFromSourceSets() {
        List<Path> classPath = new ArrayList<>();
        SourceSetContainer sourceSetContainer = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet mainSourceSet = sourceSetContainer.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (mainSourceSet != null) {
            classPath.addAll(mainSourceSet
                    .getRuntimeClasspath()
                    .getFiles()
                    .stream()
                    .filter(File::exists)
                    .map(File::toPath)
                    .collect(Collectors.toList()));
            if (Constants.PROFILE_WEB.equals(clientExtension.getTarget())) {

                Configuration runtimeClasspath = project
                        .getConfigurations()
                        .getByName(mainSourceSet.getRuntimeClasspathConfigurationName());

                classPath.addAll(resolve(runtimeClasspath, runtimeClasspath
                        .getResolvedConfiguration()
                        .getResolvedArtifacts()
                        .stream()
                        .filter(a -> a.getModuleVersion().getId().getGroup().equals("org.openjfx"))
                        .map(it -> {
                            String group = it.getModuleVersion().getId().getGroup();
                            String name = it.getName();
                            String version = Constants.DEFAULT_JAVAFX_JS_SDK_VERSION;
                            String classifier = Constants.WEB_AOT_CLASSIFIER;
                            return project
                                    .getDependencies()
                                    .create(Map.of("group", group, "name", name, "version", version, "classifier", classifier));
                        })
                        .collect(Collectors.toList()))
                        .filter(p -> !p.getFileName().toString().endsWith("-bck2brwsr.jar"))
                        .collect(Collectors.toList()));

                classPath.addAll(resolve(runtimeClasspath, WebTargetConfiguration.WEB_AOT_DEPENDENCIES
                        .stream()
                        .map(notation -> project.getDependencies().create(notation))
                        .collect(Collectors.toList())).collect(Collectors.toList()));
            }
        }
        return classPath;
    }

    private Stream<Path> resolve(Configuration runtimeClasspath, Collection<Dependency> dependencies) {
        Configuration configuration = project.getConfigurations().findByName(CONFIGURATION_WEB_CLASSPATH_EXTRAS);
        if (configuration == null) {
            configuration = project
                    .getConfigurations()
                    .create(CONFIGURATION_WEB_CLASSPATH_EXTRAS, conf -> conf.setCanBeResolved(true));
            configuration.attributes(attributes -> {
                for (Attribute<?> attribute : runtimeClasspath.getAttributes().keySet()) {
                    attribute(attributes, attribute, runtimeClasspath.getAttributes());
                }
            });
        }
        if (configuration.getState() != Configuration.State.UNRESOLVED)
            throw new IllegalStateException(configuration.getState().toString());
        dependencies.stream().distinct().forEach(configuration.getDependencies()::add);
        ArtifactView view = configuration.getIncoming().artifactView(viewConfiguration -> {
            viewConfiguration.lenient(true);
        });
        Stream<Path> stream = view.getArtifacts().getArtifactFiles().getFiles().stream().map(File::toPath);
        project.getConfigurations().remove(configuration);
        return stream;
    }

    private <T> void attribute(AttributeContainer attributes, Attribute<T> attribute, AttributeContainer source) {
        attributes.attribute(attribute, Objects.requireNonNull(source.getAttribute(attribute)));
    }

    private Path getGraalHome() {
        String graalvmHome = clientExtension.getGraalvmHome();
        if (graalvmHome == null) {
            graalvmHome = System.getenv("GRAALVM_HOME");
        }
        if (graalvmHome == null) {
            throw new GradleException("GraalVM installation directory not found." + " Either set GRAALVM_HOME as an environment variable or" + " set graalvmHome in the gluonfx-plugin configuration");
        }
        return Path.of(graalvmHome);
    }
}
