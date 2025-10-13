package com.bloodsugar.app

object ImportInvoker {
    // Will be set by the Activity (holds file-picker launcher invocation)
    var launcher: (() -> Unit)? = null
}
