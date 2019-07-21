package com.skateboard.hecatoncheires.util

import org.gradle.api.Project
import java.io.*

object GitUtil {

    fun getCommitFiles(): List<File> {

        val command =
            arrayOf("/bin/bash", "-c", "git diff --name-only --diff-filter=ACMRTUXB HEAD")

        val process = Runtime.getRuntime().exec(command)

        process.waitFor()

        val commitFileList = mutableListOf<File>()

        try {
            val inputReader = BufferedReader(InputStreamReader(process.inputStream))


            var fileName = inputReader.readLine()

            while (fileName != null) {

                commitFileList.add(File(fileName))
                fileName = inputReader.readLine()
            }

            inputReader.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return commitFileList
    }

    fun getCommitFilesPathForPMD(): List<String> {

        val pathList = mutableListOf<String>()
        getCommitFiles().forEach {
            pathList.add("**/${it.name}")
        }
        return pathList
    }


    fun preparePreCommitHook(project: Project, excludeTasks: String = "") {


        val hookFile = File(project.rootDir.absolutePath, ".git/hooks/pre-commit")
        try {
            if (hookFile.exists()) {
                hookFile.delete()
            }
            if (!hookFile.parentFile.exists()) {
                hookFile.parentFile.mkdirs()
            }
            hookFile.createNewFile()
            Runtime.getRuntime().exec("chmod 777 ${hookFile.absolutePath}")
            val rootProject = project.rootDir.absolutePath
            val hookFileContent = "#!/bin/sh\n" +
                    "TASKS=\$($rootProject${File.separator}gradlew tasks)\n" +
                    "if [[ \$TASKS =~ \"incrementlint\" ]]; then\n" +
                    "\techo start pmd check\n" +
                    "\tPMDOUTPUT=\$($rootProject${File.separator}gradlew pmdcheck)\n" +
                    "\tif [[ \$PMDOUTPUT =~ \"pmdcheck FAILED\" ]]; then\n" +
                    "\t\techo found pmd issue\n" +
                    "\t\texit 1\n" +
                    "\tfi\n" +
                    "\tif [[ \$PMDOUTPUT =~ \"Task 'pmdcheck' not found\" ]]; then\n" +
                    "\t\texit 0\n" +
                    "\tfi\n" +
                    "\techo start lint check\n" +
                    "\tOUTPUT=\$($rootProject${File.separator}gradlew incrementlint $excludeTasks)\n" +
                    "\tif [[ \$OUTPUT =~ \"lint no issues found\" ]]; then\n" +
                    "\t\texit 0\n" +
                    "\telse\n" +
                    "\t\techo \$OUTPUT\n" +
                    "\t\texit 1\n" +
                    "\tfi\n" +
                    "else\n" +
                    "\texit 0\n" +
                    "fi\n"

            val fileWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(hookFile)))

            fileWriter.write(hookFileContent)

            fileWriter.flush()

            fileWriter.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removePreCommitHook(project: Project) {

        val hookFile = File(project.rootDir.absolutePath, ".git/hooks/pre-commit")
        try {
            hookFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}