package com.skateboard.hecatoncheires.checktools

import com.android.builder.model.Variant
import com.android.sdklib.BuildToolInfo
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.LintRequest
import com.android.tools.lint.gradle.LintGradleClient
import com.android.tools.lint.gradle.api.VariantInputs
import com.skateboard.hecatoncheires.util.GitUtil
import java.io.File

class IncrementLintGradleClient(
    version: String,
    issueRegistry: IssueRegistry,
    lintFlags: LintCliFlags,
    gradleProject: org.gradle.api.Project,
    sdkHome: File?,
    variant: Variant?,
    variantInputs: VariantInputs?,
    buildToolInfo: BuildToolInfo?,
    isAndroid: Boolean
) : LintGradleClient(
    version,
    issueRegistry,
    lintFlags,
    gradleProject,
    sdkHome,
    variant,
    variantInputs,
    buildToolInfo,
    isAndroid
) {

    override fun createLintRequest(files: MutableList<File>?): LintRequest {
        val lintRequest = super.createLintRequest(files)
        val commitFiles = GitUtil.getCommitFiles()
        lintRequest.getProjects()?.forEach { project ->

            commitFiles.forEach {
                project.addFile(it)
            }
        }
        return lintRequest
    }




}