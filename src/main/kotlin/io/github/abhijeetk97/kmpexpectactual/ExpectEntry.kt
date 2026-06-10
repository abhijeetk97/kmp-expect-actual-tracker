package io.github.abhijeetk97.kmpexpectactual

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.name.FqName

/**
 * A snapshot of one `expect` declaration found in the project.
 *
 * WHY THIS EXISTS
 * ---------------
 * The scanner (ExpectActualScanner) needs to hand results to the UI thread. We can't
 * pass raw PSI elements across that boundary because PSI is a live, mutable tree that
 * the IDE can invalidate at any moment (when the user edits a file, saves, or the
 * indexer reruns). Holding a stale PSI element and later calling .navigate() on it
 * would either crash or silently do nothing.
 *
 * So instead we store two things:
 *   1. Plain data we derived FROM the PSI (fqName, displayName, kind) — these are
 *      just strings/value objects; they survive forever.
 *   2. A SmartPsiElementPointer — a handle the platform keeps alive and automatically
 *      updates to point at the same element even after the file is edited. When we
 *      actually need to navigate, we dereference the pointer INSIDE a read action to
 *      get the current, valid PsiElement.
 */
data class ExpectEntry(

    // The fully-qualified name of the declaration, e.g. "com.example.Platform.name".
    // FqName is an IntelliJ value type — basically a glorified dot-separated string that
    // the platform uses everywhere to identify declarations uniquely across the project.
    val fqName: FqName,

    // Human-readable label shown in the tree: the short name plus "()" for functions.
    // e.g. "platformName()" or "Platform" or "userAgent"
    val displayName: String,

    // One of "class", "function", "property", or "declaration".
    // Used by the tree renderer to show a [kind] badge in grey.
    val kind: String,

    // A STABLE HANDLE to the PSI element, not the element itself.
    //
    // SmartPsiElementPointer<*> is a platform type that wraps a PsiElement and keeps
    // a reference that survives document edits and incremental re-parses. If the file
    // hasn't changed, .element returns the original PsiElement. If it has changed,
    // it returns the updated one (or null if the declaration was deleted).
    //
    // Rule: always dereference (.element) inside a ReadAction. Never store .element
    // directly into a field — that's how you get "read access" crashes.
    val pointer: SmartPsiElementPointer<*>,
)
