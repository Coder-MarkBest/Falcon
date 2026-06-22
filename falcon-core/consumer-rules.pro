# ============================================================================
# Falcon IPC — consumer R8/ProGuard rules (shipped in the AAR, auto-applied).
#
# Why these are required: a Falcon service is keyed by its interface's runtime
# qualifiedName. In a minified build R8 renames classes, and two *independently*
# minified APKs (cross-app) would obfuscate the same shared interface to DIFFERENT
# names — the discovery keys would no longer match and getService() would fail.
# Keeping the interface names stable fixes this. methodId and the schema hash are
# compile-time constants and are unaffected by obfuscation.
# ============================================================================

# Service-interface names ARE the cross-process service key — never rename them.
-keepnames interface * extends com.falcon.ipc.service.IpcService

# Parcelable wire types: keep the CREATOR field so unmarshalling works, AND keep the
# class NAME — Bundle.putParcelable writes the class name into the wire, so two
# independently minified APKs (cross-app) must obfuscate a shared Parcelable to the
# same (i.e. original) name or readParcelable throws BadParcelableException.
-keepnames class * implements android.os.Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enum wire values are encoded by name (BundleCodec.putEnum/getEnum -> valueOf).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Framework protocol Parcelable + AIDL stubs cross the Binder boundary by type.
-keep class com.falcon.ipc.protocol.IpcEnvelope { *; }
-keep class com.falcon.ipc.aidl.** { *; }

# KSP-generated aggregated registry is referenced by the app via generated(...).
-keep class com.falcon.ipc.generated.** { *; }
