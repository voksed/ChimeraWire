package com.happproxy.util

internal class ErrorCodeJNIWrapper {
    companion object {
        private var loaded = false
        private var loadError: String? = null

        fun tryLoad(): Boolean {
            if (loaded) return true
            if (loadError != null) return false
            return try {
                System.loadLibrary("error-code")
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message
                false
            }
        }
    }

    private external fun jniGetErrorMessageFromString(errorString: String, errorType: Int): ByteArray?

    fun decrypt(payload: String, type: Int): String? {
        if (!tryLoad()) return null
        return try {
            val bytes = jniGetErrorMessageFromString(payload, type)
            if (bytes == null || bytes.isEmpty()) null
            else String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            null
        }
    }
}
