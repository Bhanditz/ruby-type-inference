package org.jetbrains.plugins.ruby.ruby.actions

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.plugins.ruby.ruby.persistent.TypeInferenceDirectory
import org.jetbrains.ruby.runtime.signature.server.SignatureServer
import java.io.File
import java.nio.file.Paths

class ExportContractsAction : ExportFileActionBase(
        whatToExport = "Type Contracts",
        defaultFileName = { "${it.name}-type-tracker-contracts" },
        extensions = arrayOf(".mv.db"),
        description = "The selected file will be populated with H2 DB containing type contracts which might be imported later"
) {
    override fun showErrorDialog(): DataForErrorDialog? {
        if (SignatureServer.runningServers.any { it.isProcessingRequests() }) {
            return DataForErrorDialog("Cannot export contracts",
                    "Cannot export contracts while have some programs running under type tracker")
        }
        return null
    }

    override fun backgroundProcess(absoluteFilePath: String, project: Project, module: Module?, sdk: Sdk?) {
        exportDB(absoluteFilePath, project)
    }

    companion object {
        fun exportDB(destPath: String, project: Project) {
            val sourcePath = Paths.get(TypeInferenceDirectory.RUBY_TYPE_INFERENCE_DIRECTORY.toString(), project.name).toString()
            File(sourcePath).copyTo(File(destPath), overwrite = true)
        }
    }
}
