package org.jetbrains.plugins.ruby.ruby.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.ruby.ancestorsextractor.AncestorsExtractorBase
import org.jetbrains.plugins.ruby.ancestorsextractor.RailsConsoleRunner
import org.jetbrains.plugins.ruby.ruby.RModuleUtil

/**
 * Base class representing file export action with "save to" dialog
 * @param whatToExport Will be shown in "save to" dialog
 * @param defaultFileName Function which by given [Project] creates default file name in "save to" dialog
 * @param extensions Array of available extensions for exported file
 * @param description Description in "save to" dialog
 */
abstract class ExportFileActionBase(
        private val whatToExport: String,
        private val defaultFileName: (Project) -> String,
        private val extensions: Array<String>,
        private val description: String = "",
        private val numberOfProgressBarFractions: Int? = null
) : DumbAwareAction() {
    protected data class DataForErrorDialog(val title: String, val message: String)

    /**
     * This method is called in [actionPerformed] before any other things. In this method you can check that
     * action can be performed and return `null` in this case. Or determine that action can't be performed
     * for some reason and return [DataForErrorDialog] which contains error msg.
     */
    protected open fun showErrorDialog(): DataForErrorDialog? = null

    final override fun actionPerformed(e: AnActionEvent) {
        showErrorDialog()?.let {
            Messages.showErrorDialog(it.message, it.title)
            return
        }

        val project = e.project ?: return

        val dialog = FileSaverDialogImpl(FileSaverDescriptor(
                "Export $whatToExport",
                description,
                *extensions), project)
        val fileWrapper = dialog.save(null, defaultFileName(project)) ?: return

        val module = RModuleUtil.getInstance().getModule(e.dataContext)
        val sdk = RModuleUtil.getInstance().findRubySdkForModule(module)

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { backgroundProcess(fileWrapper.file.absolutePath, project, module, sdk) },
                "Exporting $whatToExport", false, e.project)
    }

    protected fun Sdk?.requireSdkNotNull(): Sdk {
        return this ?: throw IllegalStateException("Ruby SDK is not set")
    }

    /**
     * In this method implementation you can do you job needed for file export and then file exporting itself.
     * @param absoluteFilePath Absolute file path which user have chosen to save file to.
     */
    protected abstract fun backgroundProcess(absoluteFilePath: String, project: Project, module: Module?, sdk: Sdk?)

    @Throws(IllegalStateException::class)
    protected fun moveProgressBarForward() {
        if (numberOfProgressBarFractions == null) throw IllegalStateException("You cannot call moveProgressBarForward() " +
                "method when progressBarFractions property is null")
        val progressIndicator = ProgressManager.getInstance().progressIndicator
        if (progressIndicator is ProgressWindow) {
            progressIndicator.fraction = minOf(1.0, progressIndicator.fraction + 1.0/numberOfProgressBarFractions)
        }
    }

    /**
     * You can use to set as [AncestorsExtractorBase.listener] because every [ProgressListener]
     * method call just calls [moveProgressBarForward]
     */
    protected inner class ProgressListener : RailsConsoleRunner.Listener {
        override fun irbConsoleExecuted() {
            moveProgressBarForward()
        }

        override fun informationWasExtractedFromIRB() {
            moveProgressBarForward()
        }
    }
}
