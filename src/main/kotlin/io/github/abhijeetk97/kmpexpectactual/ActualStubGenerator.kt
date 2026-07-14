package io.github.abhijeetk97.kmpexpectactual

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Builds the source text of an `actual` counterpart for an `expect` declaration.
 * Used by [GenerateActualStubFix] (issue #16).
 *
 * BEST-EFFORT BY DESIGN
 * ---------------------
 * The generated stub compiles for the common cases — top-level functions and
 * properties, classes/objects/interfaces with function and property members,
 * and enums. Exotic shapes (secondary constructors, context receivers,
 * supertypes needing constructor calls) may need a manual touch-up after
 * generation; the stub is a starting point, not a guarantee.
 *
 * Everything here is a pure PSI-to-text transformation: signatures are copied
 * verbatim from the expect's PSI (type references, parameter lists, type
 * parameters), bodies become TODO()s.
 *
 * Must be called inside a read action (reads PSI).
 */
object ActualStubGenerator {

    private const val TODO_BODY = "TODO(\"Not yet implemented\")"

    // Modifiers carried over from the expect declaration onto the stub.
    // `expect` itself is replaced by `actual`; everything not listed here
    // (e.g. `external`) is dropped rather than risk an invalid combination.
    private val KEPT_MODIFIERS = listOf(
        KtTokens.PRIVATE_KEYWORD,
        KtTokens.PROTECTED_KEYWORD,
        KtTokens.INTERNAL_KEYWORD,
        KtTokens.SUSPEND_KEYWORD,
        KtTokens.OPERATOR_KEYWORD,
        KtTokens.INFIX_KEYWORD,
        KtTokens.OPEN_KEYWORD,
        KtTokens.ABSTRACT_KEYWORD,
    )

    fun generate(declaration: KtNamedDeclaration): String = stubFor(declaration, indent = "")

    private fun stubFor(decl: KtNamedDeclaration, indent: String, insideInterface: Boolean = false): String =
        when (decl) {
            is KtClassOrObject -> classStub(decl, indent)
            is KtNamedFunction -> functionStub(decl, indent, insideInterface)
            is KtProperty      -> propertyStub(decl, indent, insideInterface)
            else               -> "$indent// TODO: write the actual declaration for '${decl.name}'"
        }

    private fun functionStub(f: KtNamedFunction, indent: String, insideInterface: Boolean): String {
        val mods = keptModifiers(f)
        val typeParams = f.typeParameterList?.text?.plus(" ") ?: ""
        val receiver = f.receiverTypeReference?.text?.plus(".") ?: ""
        val params = f.valueParameterList?.text ?: "()"
        val returnType = f.typeReference?.text?.let { ": $it" } ?: ""
        // Interface members and abstract members must not have bodies.
        val body = if (insideInterface || f.hasModifier(KtTokens.ABSTRACT_KEYWORD)) "" else " = $TODO_BODY"
        return "${indent}actual ${mods}fun $typeParams$receiver${f.name}$params$returnType$body"
    }

    private fun propertyStub(p: KtProperty, indent: String, insideInterface: Boolean): String {
        val mods = keptModifiers(p)
        val keyword = if (p.isVar) "var" else "val"
        val type = p.typeReference?.text?.let { ": $it" } ?: ""
        val initializer = if (insideInterface || p.hasModifier(KtTokens.ABSTRACT_KEYWORD)) "" else " = $TODO_BODY"
        return "${indent}actual $mods$keyword ${p.name}$type$initializer"
    }

    private fun classStub(c: KtClassOrObject, indent: String): String {
        val mods = keptModifiers(c)
        val isInterface = (c as? KtClass)?.isInterface() == true
        val isEnum = (c as? KtClass)?.isEnum() == true
        val keyword = when {
            c is KtObjectDeclaration -> "object"
            isInterface              -> "interface"
            isEnum                   -> "enum class"
            else                     -> "class"
        }
        val typeParams = c.typeParameterList?.text ?: ""
        // Expect constructors must be re-declared with the `actual` keyword.
        val ctor = c.primaryConstructor?.valueParameterList?.text
            ?.let { " actual constructor$it" } ?: ""
        val supertypes = c.getSuperTypeList()?.text?.let { " : $it" } ?: ""
        val header = "${indent}actual $mods$keyword ${c.name}$typeParams$ctor$supertypes"

        val memberIndent = "$indent    "
        val body = if (isEnum) {
            // An actual enum must re-declare exactly the expect's entries.
            c.declarations.filterIsInstance<KtEnumEntry>()
                .mapNotNull { it.name }
                .joinToString(",\n") { "$memberIndent$it" }
        } else {
            c.declarations
                .filterIsInstance<KtNamedDeclaration>()
                .filter { it !is KtEnumEntry }
                .joinToString("\n\n") { stubFor(it, memberIndent, insideInterface = isInterface) }
        }

        return if (body.isEmpty()) header else "$header {\n$body\n$indent}"
    }

    private fun keptModifiers(d: KtModifierListOwner): String =
        KEPT_MODIFIERS.filter { d.hasModifier(it) }
            .joinToString("") { "${it.value} " }
}
