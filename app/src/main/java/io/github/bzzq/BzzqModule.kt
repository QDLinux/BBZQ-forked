package io.github.bzzq

import android.util.Log
import io.github.bzzq.hooks.HookRegistry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class BzzqModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        val frameworkName = frameworkName
        val frameworkVersionCode = frameworkVersionCode
        val knownFramework = isKnownFramework(frameworkName)
        log(
            Log.INFO,
            LOG_TAG,
            "Loaded in ${param.getProcessName()} on $frameworkName($frameworkVersionCode); knownFramework=$knownFramework",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        HookRegistry.handlePackageReady(this, param) { message, throwable ->
            if (throwable == null) {
                log(Log.INFO, LOG_TAG, message)
            } else {
                log(Log.WARN, LOG_TAG, message, throwable)
            }
        }
    }

    private fun isKnownFramework(frameworkName: String): Boolean {
        return frameworkName.equals(NPATCH_FRAMEWORK_NAME, ignoreCase = true) ||
            frameworkName.equals(VECTOR_FRAMEWORK_NAME, ignoreCase = true) ||
            frameworkName.equals(LSPOSED_FRAMEWORK_NAME, ignoreCase = true)
    }

    private companion object {
        private const val LOG_TAG = "bzzq"
        private const val NPATCH_FRAMEWORK_NAME = "NPatch"
        private const val VECTOR_FRAMEWORK_NAME = "Vector"
        private const val LSPOSED_FRAMEWORK_NAME = "LSPosed"
    }
}
