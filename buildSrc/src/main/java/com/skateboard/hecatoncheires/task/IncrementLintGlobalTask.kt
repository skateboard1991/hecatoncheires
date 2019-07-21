package com.skateboard.hecatoncheires.task

import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.tasks.LintBaseTask
import com.skateboard.hecatoncheires.Constants.Companion.INCREMENT_LINT_PREFIX
import com.skateboard.hecatoncheires.checktools.IncrementLintGradleExecution
import com.skateboard.hecatoncheires.checktools.IncrementReflectiveLintRunner
import com.skateboard.hecatoncheires.util.GitUtil
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

open class IncrementLintGlobalTask : LintBaseTask() {

    private var variantInputMap: MutableMap<String, LintBaseTask.VariantInputs> = mutableMapOf()
    private var allInputs: ConfigurableFileCollection? = null

    @InputFiles
    @Optional
    fun getAllInputs(): FileCollection? {
        return allInputs
    }

    @TaskAction
    fun lint() {
        runLint(LintGlobalTaskDescriptor())
    }

    override fun runLint(descriptor: LintBaseTaskDescriptor) {
        val lintClassPath = lintClassPath
        if (lintClassPath != null) {
            IncrementReflectiveLintRunner().runLint(
                project.gradle,
                descriptor, lintClassPath.files
            )
        }
    }


    private inner class LintGlobalTaskDescriptor : LintBaseTask.LintBaseTaskDescriptor() {

        override val variantName: String?
            @Nullable
            get() = null

        @Nullable
        override fun getVariantInputs(@NonNull variantName: String): LintBaseTask.VariantInputs? {
            return variantInputMap[variantName]
        }
    }

    class GlobalConfigAction(
        @NonNull globalScope: GlobalScope, @param:NonNull private val variantScopes: Collection<VariantScope>
    ) : LintBaseTask.BaseConfigAction<IncrementLintGlobalTask>(globalScope) {

        @NonNull
        override fun getName(): String {
            return INCREMENT_LINT_PREFIX
        }

        @NonNull
        override fun getType(): Class<IncrementLintGlobalTask> {
            return IncrementLintGlobalTask::class.java
        }

        override fun execute(@NonNull lintTask: IncrementLintGlobalTask) {
            super.execute(lintTask)

            lintTask.description = "Runs lint on all variants."
            lintTask.variantName = ""

            lintTask.allInputs = globalScope.project.files()
            variantScopes.forEach {
                val inputs = VariantInputs(it)
                lintTask.allInputs?.from(inputs.allInputs)
                lintTask.variantInputMap[inputs.name] = inputs
            }
        }
    }
}
