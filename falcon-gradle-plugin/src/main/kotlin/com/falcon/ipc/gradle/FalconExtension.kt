package com.falcon.ipc.gradle

import org.gradle.api.provider.SetProperty

/**
 * `falcon { peerPackages("com.oem.nav", "com.oem.media") }`
 *
 * The packages declared here are injected into the merged manifest's `<queries>` so the
 * app can see and reach those peers' `IpcRegistryProvider`s (Android 11+ visibility).
 * Keep this list in sync with the runtime `Falcon.init { peerPackages(...) }`.
 */
abstract class FalconExtension {
    abstract val peerPackages: SetProperty<String>

    fun peerPackages(vararg packages: String) {
        peerPackages.addAll(*packages)
    }
}
