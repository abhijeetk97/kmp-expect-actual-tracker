package io.github.abhijeetk97.kmpexpectactual

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.name.FqName

data class ExpectEntry(
    val fqName: FqName,
    val displayName: String,
    val kind: String,
    val pointer: SmartPsiElementPointer<*>,
)
