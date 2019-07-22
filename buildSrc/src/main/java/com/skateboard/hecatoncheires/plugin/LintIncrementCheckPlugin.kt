package com.skateboard.hecatoncheires.plugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.TaskFactoryImpl
import com.android.build.gradle.internal.api.BaseVariantImpl
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.tasks.LintBaseTask
import com.skateboard.hecatoncheires.Constants
import com.skateboard.hecatoncheires.Constants.Companion.ALIRULESETS
import com.skateboard.hecatoncheires.Constants.Companion.GOUP_NAME
import com.skateboard.hecatoncheires.Constants.Companion.HECATONCHEIRESEXTENSION_NAME
import com.skateboard.hecatoncheires.Constants.Companion.INCREMENT_LINT_PREFIX
import com.skateboard.hecatoncheires.Constants.Companion.P3C_PMD_DEPENDENCY
import com.skateboard.hecatoncheires.Constants.Companion.PMD
import com.skateboard.hecatoncheires.Constants.Companion.PMDTASK
import com.skateboard.hecatoncheires.Constants.Companion.PMD_CONFIGURATION
import com.skateboard.hecatoncheires.Constants.Companion.PMD_DEPENDENCY
import com.skateboard.hecatoncheires.extension.HecatoncheiresExtension
import com.skateboard.hecatoncheires.task.IncrementLintGlobalTask
import com.skateboard.hecatoncheires.task.IncrementLintPerVariantTask
import com.skateboard.hecatoncheires.util.GitUtil
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import java.io.File

class LintIncrementCheckPlugin : Plugin<Project> {


    private val excludeTasks = StringBuilder()

    override fun apply(project: Project) {
        configPmdCheck(project)
        configLintTask(project)
        prepareGitHook(project)

    }

    private fun prepareGitHook(project: Project) {
        val hecatoncheiresExtension =
            project.extensions.create(HECATONCHEIRESEXTENSION_NAME, HecatoncheiresExtension::class.java)
        project.afterEvaluate {
            if (hecatoncheiresExtension.enable) {
                GitUtil.preparePreCommitHook(
                    project,
                    if (!hecatoncheiresExtension.preCompile)
                        excludeTasks.toString()
                    else ""
                )
            } else {
                GitUtil.removePreCommitHook(
                    project
                )
            }
        }
    }


    private fun configPmdCheck(project: Project) {

        configPmdDependency(project)

        configPmdTask(project)
    }

    private fun configPmdDependency(project: Project) {
        project.plugins.apply(PMD)
        val pmdConfig = project.configurations.getByName(PMD_CONFIGURATION)
        pmdConfig.dependencies.add(project.dependencies.create(P3C_PMD_DEPENDENCY))
        pmdConfig.dependencies.add(project.dependencies.create(PMD_DEPENDENCY))
    }

    private fun configPmdTask(project: Project) {
        project.afterEvaluate {
            val pmdExtension = project.extensions.findByName(PMD) as PmdExtension
            val pmdTask = project.tasks.create(PMDTASK, Pmd::class.java)
            pmdTask.targetJdk = pmdExtension.targetJdk
            pmdTask.ignoreFailures = pmdExtension.isIgnoreFailures
            ALIRULESETS.addAll(pmdExtension.ruleSets)
            pmdTask.ruleSets = ALIRULESETS
            pmdTask.ruleSetFiles = pmdExtension.ruleSetFiles
            pmdTask.source(project.rootDir)
            pmdTask.isConsoleOutput = pmdExtension.isConsoleOutput
            pmdTask.rulePriority = pmdExtension.rulePriority
            pmdTask.reports {
                it.xml.isEnabled = true
                it.xml.destination = File(pmdExtension.reportsDir, "report.xml")
                it.html.isEnabled = true
                it.html.destination = File(pmdExtension.reportsDir, "report.html")
            }
            pmdTask.group = GOUP_NAME
            pmdTask.include(GitUtil.getCommitFilesPathForPMD())
            pmdTask.exclude("**/build/**", "**/res/**", "**/*.xml", "**/*.gradle", "**/*.kt")
        }
    }

    private fun configLintTask(project: Project) {
        addLintClassPath(project)
        val taskFactory = TaskFactoryImpl(project.tasks)
        project.afterEvaluate {
            addLintClassPath(project)
            val variantDataList = getVariantDataList(project)
            var globalScope: GlobalScope? = null
            var variantScopeList = mutableListOf<VariantScope>()
            variantDataList?.forEach {
                variantScopeList.add(it.scope)
                globalScope = it.scope.globalScope
                taskFactory.create(IncrementLintPerVariantTask.ConfigAction(it.scope))
                val variantTask = taskFactory.findByName(it.scope.getTaskName(INCREMENT_LINT_PREFIX))
                variantTask?.group = GOUP_NAME
            }
            taskFactory.create(IncrementLintGlobalTask.GlobalConfigAction(globalScope!!, variantScopeList))
            val globalTask = project.tasks.findByName(INCREMENT_LINT_PREFIX)
            globalTask?.group = GOUP_NAME
            globalTask?.taskDependencies?.getDependencies(globalTask)?.forEach { it ->
                if (it.name.startsWith("compile")) {
                    excludeTasks.append("-x ${it.name} ")
                }
            }
        }
    }


    private fun addLintClassPath(project: Project) {
        project.gradle.rootProject.configurations
        val classPathConfiguration = project.gradle.rootProject.buildscript.configurations.getByName("classpath")
        var hecatoncheiresDependency: Dependency? = null
        classPathConfiguration.dependencies.forEach {
            if (it.name.contains(Constants.HECATONCHEIRESEXTENSION_NAME)) {
                hecatoncheiresDependency = it
                return@forEach
            }
        }
        val lintConfiguration = project.configurations.getByName(LintBaseTask.LINT_CLASS_PATH)
        project.dependencies.add(
            lintConfiguration.name,
            hecatoncheiresDependency
        )
    }

    private fun getVariantDataList(project: Project): List<BaseVariantData>? {

        val variantDataList = mutableListOf<BaseVariantData>()

        val variantArray = createVariants(project)

        variantArray?.forEach {
            val variantData = checkVariantData(it)
            if (variantData != null) {
                variantDataList.add(variantData)
            }
        }
        return variantDataList
    }


    private fun createVariants(project: Project): Array<BaseVariant>? {

        // 变种列表
        var variantImplList: Array<BaseVariant>? = null

        if (project.plugins.hasPlugin(AppPlugin::class.java)) {
            val appExtension = project.extensions.findByType(AppExtension::class.java)
            appExtension?.let {
                variantImplList = it.applicationVariants.toTypedArray()
            }
        } else if (project.plugins.hasPlugin(LibraryPlugin::class.java)) {
            val libraryExtension = project.extensions.findByType(LibraryExtension::class.java)
            libraryExtension?.let {
                variantImplList = it.libraryVariants.toTypedArray()
            }
        }

        return variantImplList
    }

    private fun checkVariantData(variant: BaseVariant): BaseVariantData? {

        try {
            val baseVariantImplClazz = Class.forName(BaseVariantImpl::class.java.name)
            val method = baseVariantImplClazz.getDeclaredMethod("getVariantData")
            if (method != null) {
                method.isAccessible = true
                return method.invoke(variant) as BaseVariantData
            }
        } catch (e: Exception) {


        }
        return null
    }


}

