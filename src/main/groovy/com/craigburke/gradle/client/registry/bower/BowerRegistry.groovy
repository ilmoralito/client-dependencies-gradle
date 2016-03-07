/*
 * Copyright 2016 Craig Burke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.craigburke.gradle.client.registry.bower

import static groovyx.gpars.GParsPool.withExistingPool

import com.craigburke.gradle.client.registry.core.CircularDependencyException
import com.craigburke.gradle.client.registry.core.Registry
import com.craigburke.gradle.client.registry.core.RegistryBase
import org.gradle.api.logging.Logger
import com.craigburke.gradle.client.dependency.Dependency
import com.craigburke.gradle.client.dependency.Version
import com.craigburke.gradle.client.dependency.VersionResolver
import groovy.json.JsonSlurper

/**
 *
 * Registry to resolves Bower Dependencies
 *
 * @author Craig Burke
 */
class BowerRegistry extends RegistryBase implements Registry {

    static final String DEFAULT_URL = 'https://bower.herokuapp.com'

    BowerRegistry(String url, Logger log) {
        super(url, log)
        resolvers = [new GithubResolver(log), new GitResolver(log)]
    }

    private String getGitUrl(String dependencyName) {
        URL dependencyUrl = new URL("${url}/packages/${dependencyName}")
        new JsonSlurper().parse(dependencyUrl).url
    }

    private List<Dependency> loadChildDependencies(Dependency dependency, List<String> exclusions) {
        File bowerConfigFile = new File("${dependency.sourceFolder.absolutePath}/bower.json")
        def bowerConfigJson = new JsonSlurper().parse(bowerConfigFile)
        withExistingPool(RegistryBase.pool) {
            bowerConfigJson.dependencies
                    .findAll { String name, String versionExpression -> !exclusions.contains(name) }
                    .collectParallel { String name, String versionExpression ->
                if (dependency.ancestorsAndSelf*.name.contains(name)) {
                    String message = "Circular dependency created by dependency ${name}@${versionExpression}"
                    throw new CircularDependencyException(message)
                }

                Dependency childDependency = new Dependency(name: name, versionExpression: versionExpression)
                loadDependency(childDependency, dependency)
            } ?: []
        } as List<Dependency>
    }

    List<Version> getVersionList(Dependency dependency) {
        getResolver(dependency).getVersionList(dependency)
    }

    Dependency loadDependency(Dependency declaredDependency, Dependency parent) {
        log.info "Loading dependency: ${declaredDependency}"

        Dependency dependency = declaredDependency.clone()
        dependency.registry = this
        dependency.parent = parent
        dependency.url = dependency.url ?: getGitUrl(dependency.name)
        dependency.sourceFolder = new File("${cachePath}/${dependency.name}/source/")
        dependency.version = VersionResolver.resolve(declaredDependency.versionExpression, getVersionList(dependency))

        getResolver(dependency).downloadDependency(dependency)

        if (declaredDependency.transitive) {
            dependency.children = loadChildDependencies(dependency, declaredDependency.exclude)
        }

        dependency
    }

}
