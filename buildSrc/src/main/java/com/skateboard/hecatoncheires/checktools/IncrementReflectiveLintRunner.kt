package com.skateboard.hecatoncheires.checktools

import com.android.tools.lint.gradle.api.DelegatingClassLoader
import com.android.tools.lint.gradle.api.ExtractAnnotationRequest
import com.android.tools.lint.gradle.api.LintExecutionRequest
import com.google.common.base.Throwables
import org.gradle.api.GradleException
import org.gradle.api.invocation.Gradle
import org.gradle.initialization.BuildCompletionListener
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.net.URL
import java.net.URLClassLoader

class IncrementReflectiveLintRunner {

    fun runLint(gradle: Gradle, request: LintExecutionRequest, lintClassPath: Set<File>) {
        try {
            val loader = getLintClassLoader(gradle, lintClassPath)
            val cls = loader.loadClass("com.skateboard.hecatoncheires.checktools.IncrementLintGradleExecution")
            val constructor = cls.getConstructor(LintExecutionRequest::class.java)
            val driver = constructor.newInstance(request)
            val analyzeMethod = driver.javaClass.getDeclaredMethod("analyze")
            analyzeMethod.invoke(driver)
        } catch (e: InvocationTargetException) {
            if (e.targetException is GradleException) {
                // Build error from lint -- pass it on
                throw e.targetException
            }
            throw wrapExceptionAsString(e)
        } catch (t: Throwable) {
            // Reflection problem
            throw wrapExceptionAsString(t)
        }
    }

    fun extractAnnotations(
        gradle: Gradle,
        request: ExtractAnnotationRequest,
        lintClassPath: Set<File>) {
        try {
            val loader = getLintClassLoader(gradle, lintClassPath)
            val cls = loader.loadClass("com.android.tools.lint.gradle.LintExtractAnnotations")
            val driver = cls.newInstance()
            val analyzeMethod = driver.javaClass.getDeclaredMethod("extractAnnotations",
                ExtractAnnotationRequest::class.java)
            analyzeMethod.invoke(driver, request)
        } catch (e: InvocationTargetException) {
            if (e.targetException is GradleException) {
                // Build error from lint -- pass it on
                throw e.targetException
            }
            throw wrapExceptionAsString(e)
        } catch (t: Throwable) {
            throw wrapExceptionAsString(t)
        }
    }

    private fun wrapExceptionAsString(t: Throwable) = RuntimeException(
        "Lint infrastructure error\nCaused by: ${Throwables.getStackTraceAsString(t)}\n")

    companion object {
        var loader: DelegatingClassLoader? = null

        private fun getLintClassLoader(gradle: Gradle, lintClassPath: Set<File>): ClassLoader {
            if (loader == null) {
                val listener = BuildCompletionListener {
                    val l = loader
                    if (l != null) {
                        loader = null
                        val cls = l.loadClass("com.android.tools.lint.LintCoreApplicationEnvironment")
                        val disposeMethod = cls.getDeclaredMethod("disposeApplicationEnvironment")
                        disposeMethod.invoke(null)
                    }
                }
                gradle.addListener(listener)

                val urls = computeUrlsFromClassLoaderDelta(lintClassPath) ?:
                computeUrlsFallback(lintClassPath)
                loader = DelegatingClassLoader(urls.toTypedArray())

            }
            return loader!!
        }

        /**
         * Computes the class loader based on looking at the given [lintClassPath] and
         * subtracting out classes already loaded by the Gradle plugin directly.
         * This may fail if the class loader isn't a URL class loader, or if
         * after some diagnostics we discover that things aren't the way they should be.
         */
        private fun computeUrlsFromClassLoaderDelta(lintClassPath: Set<File>): List<URL>? {
            // Operating on URIs rather than URLs here since URL.equals is a blocking (host name
            // resolving) operation.
            // We map to library names since sometimes the Gradle plugin and the lint class path
            // vary in where they locate things, e.g. builder-model in lintClassPath could be
            //  file:out/repo/com/<truncated>/builder-model/3.1.0-dev/builder-model-3.1.0-dev.jar
            // vs the current class loader pointing to
            //  file:~/.gradle/caches/jars-3/a6fbe15f1a0e37da0962349725f641cc/builder-3.1.0-dev.jar
            val uriMap = HashMap<String, URI>(2 * lintClassPath.size)
            lintClassPath.forEach {
                val uri = it.toURI()
                val name = getLibrary(uri) ?: return null
                uriMap[name] = uri
            }

            val gradleClassLoader = this::class.java.classLoader as? URLClassLoader ?: return null
            for (url in gradleClassLoader.urLs) {
                val uri = url.toURI()
                val name = getLibrary(uri) ?: return null
                uriMap.remove(name)
            }

            // Convert to URLs (and sanity check the result)
            val urls = ArrayList<URL>(uriMap.size)
            var seenLint = false
            for ((name, uri) in uriMap) {
                if (name.startsWith("lint-api-")) {
                    seenLint = true
                } else if (name.startsWith("builder-model-")) {
                    // This should never be on our class path, something is wrong
                    return null
                }
                urls.add(uri.toURL())
            }

            if (!seenLint) {
                // Something is wrong; fall back to heuristics
                return null
            }

            return urls
        }

        private fun getLibrary(uri: URI): String? {
            val path = uri.path
            val index = uri.path.lastIndexOf('/')
            if (index == -1) {
                return null
            }
            return path.substring(index + 1)
        }

        /**
         * Computes the exact set of URLs that we should load into our own
         * class loader. This needs to include all the classes lint depends on,
         * but NOT the classes that are already defined by the gradle plugin,
         * since we'll be passing in data (like Gradle projects, builder model
         * classes, sdklib classes like BuildInfo and so on) and these need
         * to be using the existing class loader.
         *
         * This is based on hardcoded heuristics instead of deltaing class loaders.
         */
        private fun computeUrlsFallback(lintClassPath: Set<File>): List<URL> {
            val urls = mutableListOf<URL>()

            for (file in lintClassPath) {
                val name = file.name

                // The set of jars that lint needs that *aren't* already used/loaded by gradle-core
                if (name.startsWith("uast-") ||
                    name.startsWith("intellij-core-") ||
                    name.startsWith("kotlin-compiler-") ||
                    name.startsWith("asm-") ||
                    name.startsWith("kxml2-") ||
                    name.startsWith("trove4j-") ||
                    name.startsWith("groovy-all-") ||
                    name.startsWith("hecatoncheires") ||
                    // All the lint jars, except lint-gradle-api jar (self)
                    name.startsWith("lint-")
                    // Do *not* load this class in a new class loader; we need to
                    // share the same class as the one already loaded by the Gradle
                    // plugin
                    && !name.startsWith("lint-gradle-api-")) {
                    urls.add(file.toURI().toURL())
                }
            }

            return urls
        }
    }
}