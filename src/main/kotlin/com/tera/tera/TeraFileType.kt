package com.tera.tera

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object TeraFileType : LanguageFileType(TeraLanguage) {
    override fun getName(): String = "Tera Template"
    override fun getDescription(): String = "Tera template file"
    override fun getDefaultExtension(): String = "tera"
    override fun getIcon(): Icon = TeraIcons.FILE
}
