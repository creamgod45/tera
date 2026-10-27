package com.tera.tera

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.templateLanguages.OuterLanguageElementImpl
import com.intellij.psi.tree.IElementType

class TeraAstFactory : ASTFactory() {
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement? =
        if (type == TeraFileViewProvider.OUTER) OuterLanguageElementImpl(type, text)
        else if (type == TeraTokenTypes.IDENTIFIER) TeraNameLeaf(type, text)
        else if (type == TeraTokenTypes.STRING) TeraStringLeaf(type, text)
        else super.createLeaf(type, text)
}
