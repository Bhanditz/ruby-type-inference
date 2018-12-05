package org.jetbrains.plugins.ruby.ruby.actions

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.plugins.ruby.ancestorsextractor.*
import java.io.PrintWriter

/**
 * Base class representation for exporting Ruby on Rails project's modules' ancestors
 */
abstract class ExportAncestorsActionBase(
        whatToExport: String,
        defaultFileName: (Project) -> String,
        private val extractor: AncestorsExtractorBase
) : ExportFileActionBase(whatToExport, defaultFileName, extensions = arrayOf("txt"),
        numberOfProgressBarFractions = 5) {

    override fun backgroundProcess(absoluteFilePath: String, project: Project, module: Module?, sdk: Sdk?) {
        moveProgressBarForward()
        extractor.listener = ProgressListener()
        val ancestors: List<RubyModule> = try {
            extractor.extractAncestors(project, sdk.requireSdkNotNull())
        } catch(ex: Throwable) {
            PrintWriter(absoluteFilePath).use {
                it.println(ex.message)
            }
            return
        }
        moveProgressBarForward()
        PrintWriter(absoluteFilePath).use { printWriter ->
            ancestors.forEach {
                printWriter.println("Module: ${it.name}")
                printWriter.print("Ancestors: ")
                if (it.ancestors.isEmpty()) printWriter.print("Nothing found")
                it.ancestors.forEach { printWriter.print("$it ") }
                printWriter.print("\n\n")
            }
        }
        moveProgressBarForward()
    }
}

class ExportAncestorsByObjectSpaceAction : ExportAncestorsActionBase(
        whatToExport = "ancestors by ObjectSpace",
        defaultFileName = { "${it.name}-ancestors-by-objectspace" },
        extractor = AncestorsExtractorByObjectSpace()
)

class ExportAncestorsByRubymineAction : ExportAncestorsActionBase(
        whatToExport = "ancestors by RubyMine",
        defaultFileName = { "${it.name}ancestors-by-rubymine" },
        extractor = AncestorsExtractorByRubyMine()
)