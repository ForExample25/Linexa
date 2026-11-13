# Add project specific ProGuard rules here.
#
# §1.23: the previous contents of this file claimed "This is a UI-only demo app
# with no reflection-heavy libraries, so no custom rules are required yet."
# That is no longer true — the app now depends on JNI, annotation-processor
# codegen, and name-based reflection in several places.
#
# These rules are currently LATENT: both build types set
# isMinifyEnabled = false, so R8 does not shrink or obfuscate today. They are
# added now because enabling minification without them would silently
# reintroduce exactly the failure class that already shipped once — a
# java.lang.UnsatisfiedLinkError from zstd-jni on the first .tar.zst import.
# R8 does not touch .so files, but it does rename and strip the *Java* classes
# that JNI binds to by exact name, which breaks the binding just as hard.

# ---------------------------------------------------------------------------
# zstd-jni (.tar.zst rootfs import — §1.20/§3.4)
# ---------------------------------------------------------------------------
# Native methods bind by fully-qualified class name + method name at runtime
# (Java_com_github_luben_zstd_ZstdInputStreamNoFinalizer_decompressStream and
# 143 other symbols exported from libzstd-jni-1.5.6-5.so). Renaming any of
# these classes detaches them from their native implementation.
# Native.load() also builds a library name from a String constant and calls
# System.loadLibrary with it, so the loader itself must survive intact.
-keep class com.github.luben.zstd.** { *; }
-keepclasseswithmembernames class com.github.luben.zstd.** {
    native <methods>;
}

# ---------------------------------------------------------------------------
# XZ codec (.tar.xz rootfs import)
# ---------------------------------------------------------------------------
# commons-compress reaches org.tukaani.xz through XZCompressorInputStream.
# Pure Java (no JNI), but keep it so shrinking cannot drop it as
# apparently-unreachable — the entry point is selected at runtime from an
# archive's magic bytes, which R8 cannot see.
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# ---------------------------------------------------------------------------
# commons-compress
# ---------------------------------------------------------------------------
# RealArchiveValidator/StagedRootFsInstaller instantiate the concrete stream
# classes directly rather than via CompressorStreamFactory, so the
# ServiceLoader path is not currently used — but keep the service-discovered
# types anyway so that switching to the factory later does not fail at
# runtime with a confusing "no such compressor" error.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ---------------------------------------------------------------------------
# terminal-lib / nhterm (real-PTY backend, libneoterm.so — §1.20)
# ---------------------------------------------------------------------------
# Same JNI name-binding argument as zstd-jni. ExtraKeysView is additionally
# inflated from res/layout/extra_keys_view.xml by fully-qualified name, so it
# must keep both its name and its (Context, AttributeSet) constructor.
-keep class com.offsec.nhterm.** { *; }
-keepclasseswithmembernames class com.offsec.nhterm.** {
    native <methods>;
}
-keep class com.offsec.nhterm.frontend.session.view.extrakey.ExtraKeysView {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# The plugin generates $$serializer objects that are looked up by name.
# WorkspaceStatus is a sealed hierarchy persisted through a Room
# TypeConverter, so its subclasses must keep their serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.fem.linexa.**$$serializer { *; }
-keepclassmembers class com.fem.linexa.** {
    *** Companion;
}
-keepclasseswithmembers class com.fem.linexa.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# Room ships consumer rules in its AAR that cover the generated *_Impl
# classes, which are instantiated via Class.forName. This is belt-and-braces
# for the entity/DAO types this project actually declares.
-keep class com.fem.linexa.data.room.** { *; }
-keep class com.fem.linexa.domain.workspace.WorkspaceMetadata { *; }

# Hilt/Dagger intentionally omitted: hilt-android ships its own consumer
# ProGuard rules, and Dagger's generated code is statically referenced (no
# reflection), so no manual keeps are required.
