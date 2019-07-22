package com.skateboard.hecatoncheires.task

import com.android.build.gradle.internal.scope.VariantScope
import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.gradle.tasks.LintBaseTask
import com.android.utils.StringHelper
import com.skateboard.hecatoncheires.Constants.Companion.INCREMENT_LINT_PREFIX
import com.skateboard.hecatoncheires.checktools.IncrementLintGradleExecution
import com.skateboard.hecatoncheires.checktools.IncrementReflectiveLintRunner
import com.skateboard.hecatoncheires.util.GitUtil
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

open class IncrementLintPerVariantTask: LintBaseTask() {

    private var variantInputs: LintBaseTask.VariantInputs? = null
    private var fatalOnly: Boolean = false

    @InputFiles
    @Optional
    fun getVariantInputs(): FileCollection {
        return variantInputs!!.allInputs
    }

    @TaskAction
    fun lint() {
        runLint(LintPerVariantTaskDescriptor())
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

    private inner class LintPerVariantTaskDescriptor : LintBaseTask.LintBaseTaskDescriptor() {
        override val variantName: String?
            @Nullable
            get() = this@IncrementLintPerVariantTask.variantName

        override val isFatalOnly: Boolean
            get() = fatalOnly

        @Nullable
        override fun getVariantInputs(@NonNull variantName: String): LintBaseTask.VariantInputs? {
            assert(variantName == variantName)
            return variantInputs
        }
    }

    class ConfigAction(@param:NonNull private val scope: VariantScope) :
        LintBaseTask.BaseConfigAction<IncrementLintPerVariantTask>(scope.globalScope) {

        @NonNull
        override fun getName(): String {
            return scope.getTaskName(INCREMENT_LINT_PREFIX)
        }

        @NonNull
        override fun getType(): Class<IncrementLintPerVariantTask> {
            return IncrementLintPerVariantTask::class.java
        }

        override fun execute(@NonNull lint: IncrementLintPerVariantTask) {
            super.execute(lint)

            lint.variantName = scope.variantConfiguration.fullName

            lint.variantInputs = LintBaseTask.VariantInputs(scope)

            lint.description = StringHelper.appendCapitalized(
                "Runs lint on the ",
                scope.variantConfiguration.fullName,
                " build."
            )
        }
    }

}
