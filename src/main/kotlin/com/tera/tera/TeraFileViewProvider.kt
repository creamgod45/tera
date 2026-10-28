package com.tera.tera

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.templateLanguages.ConfigurableTemplateLanguageFileViewProvider
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.psi.tree.IElementType

internal fun templateDataLanguage(project: Project?, file: VirtualFile?): Language {
    if (project != null && file != null) {
        val mapped = TemplateDataLanguageMappings.getInstance(project).getMapping(file)
        if (mapped != null && mapped != TeraLanguage) return mapped
    }
    val name = file?.name?.removeSuffix(".tera")
    val type = name?.let { FileTypeManager.getInstance().getFileTypeByFileName(it) }
    val language = (type as? LanguageFileType)?.language
    return language?.takeIf { it != TeraLanguage } ?: HTMLLanguage.INSTANCE
}

class TeraFileViewProvider(
    manager: PsiManager,
    file: VirtualFile,
    physical: Boolean,
    private val dataLanguage: Language = templateDataLanguage(manager.project, file),
) : MultiplePsiFilesPerDocumentFileViewProvider(manager, file, physical),
    ConfigurableTemplateLanguageFileViewProvider {

    companion object {
        val OUTER = IElementType("TERA_OUTER", TeraLanguage)
        private val TEMPLATE_DATA = TemplateDataElementType(
            "TERA_TEMPLATE_DATA", TeraLanguage, TeraTokenTypes.OUTER_TEXT,
            OUTER,
        )
    }

    override fun getBaseLanguage(): Language = TeraLanguage
    override fun getTemplateDataLanguage(): Language = dataLanguage
    override fun getLanguages(): Set<Language> = setOf(baseLanguage, dataLanguage)
    override fun supportsIncrementalReparse(rootLanguage: Language): Boolean = false

    override fun createFile(lang: Language): PsiFile? {
        if (lang !in languages) return null
        val file = LanguageParserDefinitions.INSTANCE.forLanguage(lang)?.createFile(this) ?: return null
        if (lang == dataLanguage && file is PsiFileImpl) file.setContentElementType(TEMPLATE_DATA)
        return file
    }

    override fun getContentElementType(language: Language): IElementType? =
        if (language == dataLanguage) TEMPLATE_DATA else null

    override fun cloneInner(fileCopy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider =
        TeraFileViewProvider(manager, fileCopy, false, dataLanguage)
}

class TeraFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(
        file: VirtualFile, language: Language?, manager: PsiManager, eventSystemEnabled: Boolean,
    ): FileViewProvider = TeraFileViewProvider(manager, file, eventSystemEnabled)
}
