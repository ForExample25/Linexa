package com.fem.linexa.ui.screens.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.domain.attribution.LicenseRef
import com.fem.linexa.domain.attribution.LicenseTextLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * §1.130 — state for the About / Open source licenses destination.
 *
 * The component list itself needs no state: [AboutContent] is derived from a compile-time
 * constant, so the screen builds it once in a `remember` and this ViewModel exists for
 * the one thing that genuinely is I/O — reading a ~41 KB license document off the asset
 * manager, which must not happen on the main thread and must not be re-read on every
 * recomposition of a scrolling text.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val licenseTextLoader: LicenseTextLoader
) : ViewModel() {

    /**
     * The license document currently being shown, or [LicenseTextState.None] when the
     * user is on the component list.
     *
     * Modelled as a state machine rather than a nullable string because all four states
     * are distinct and the user must be able to tell them apart: nothing open, loading,
     * a text, and a license that has no text to show — and the last one splits further
     * into "there is no document" (public domain — correct) versus "the document could
     * not be read" (a packaging fault). Collapsing those two would report a real defect
     * as a normal state.
     */
    private val _licenseText = MutableStateFlow<LicenseTextState>(LicenseTextState.None)
    val licenseText: StateFlow<LicenseTextState> = _licenseText.asStateFlow()

    /**
     * Open [ref]'s verbatim text.
     *
     * The [LicenseTextState.Loading] hop is emitted synchronously so the transition is
     * never a frozen list — on a cold asset manager the read is fast but not free, and
     * the alternative is a tap with no visible effect.
     */
    fun openLicense(ref: LicenseRef) {
        if (ref.assetName == null) {
            // Not an error: some components are genuinely in the public domain, so there
            // is no document to reproduce. Reported as its own state so the screen can
            // say that rather than showing an empty page.
            _licenseText.value = LicenseTextState.NoDocument(ref)
            return
        }
        _licenseText.value = LicenseTextState.Loading(ref)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { licenseTextLoader.load(ref) }
            // Re-check what the user is looking at: they can dismiss (or open another
            // license) while a read is in flight, and clobbering that with a stale
            // result would reopen a screen they just closed.
            val current = _licenseText.value
            if (current !is LicenseTextState.Loading || current.ref != ref) return@launch
            _licenseText.value = if (text != null) {
                LicenseTextState.Loaded(ref, text)
            } else {
                // The asset name is non-null, so a null read means the document is
                // missing or unreadable — a packaging fault, reported as one.
                LicenseTextState.Unavailable(ref)
            }
        }
    }

    /** Return to the component list. */
    fun closeLicense() {
        _licenseText.value = LicenseTextState.None
    }
}

/** The four readings of "which license document is on screen". */
sealed interface LicenseTextState {

    /** The component list is showing; no document is open. */
    data object None : LicenseTextState

    /** [ref]'s document is being read. */
    data class Loading(val ref: LicenseRef) : LicenseTextState

    /** [ref]'s verbatim [text]. */
    data class Loaded(val ref: LicenseRef, val text: String) : LicenseTextState

    /** [ref] has no document to reproduce (public domain). A normal state. */
    data class NoDocument(val ref: LicenseRef) : LicenseTextState

    /** [ref] names a document that could not be read. A fault, stated as one. */
    data class Unavailable(val ref: LicenseRef) : LicenseTextState

    /**
     * The license this state is about, or null for [None].
     *
     * Named `currentRef` rather than `ref`: an interface property called `ref` would
     * collide with the four subclasses' own constructor properties, which would have to
     * `override` it — and then a new state could satisfy the interface while meaning
     * something different by it.
     */
    val currentRef: LicenseRef?
        get() = when (this) {
            None -> null
            is Loading -> ref
            is Loaded -> ref
            is NoDocument -> ref
            is Unavailable -> ref
        }
}
