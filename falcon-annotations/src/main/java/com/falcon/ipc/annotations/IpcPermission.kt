package com.falcon.ipc.annotations

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IpcPermission(val callerProcess: Array<String>)
