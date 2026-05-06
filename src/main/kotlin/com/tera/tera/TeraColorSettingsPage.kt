package com.tera.tera

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class TeraColorSettingsPage : ColorSettingsPage {

    private val descriptors = arrayOf(
        AttributesDescriptor("Delimiters ({{ }}, {% %}, {# #})", TeraHighlighterColors.DELIMITER),
        AttributesDescriptor("Keywords (for, if, block…)",       TeraHighlighterColors.KEYWORD),
        AttributesDescriptor("Identifiers",                       TeraHighlighterColors.IDENTIFIER),
        AttributesDescriptor("Strings",                           TeraHighlighterColors.STRING),
        AttributesDescriptor("Numbers",                           TeraHighlighterColors.NUMBER),
        AttributesDescriptor("Operators",                         TeraHighlighterColors.OPERATOR),
        AttributesDescriptor("Comments",                          TeraHighlighterColors.COMMENT),
    )

    override fun getDisplayName()          = "Tera Template"
    override fun getIcon(): Icon           = AllIcons.FileTypes.Text
    override fun getHighlighter(): SyntaxHighlighter = TeraSyntaxHighlighter()
    override fun getAttributeDescriptors() = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    // 预览文本，展示所有 token 类型
    override fun getDemoText() = """
        {# 这是一条注释 #}
        {% extends "base.html" %}

        {% block content %}
        <h1>{{ title }}</h1>

        {% for item in items %}
            {%- if item.featured -%}
                <b>{{ item.name | upper }}</b>: {{ item.price * 1.1 | round(precision=2) }}
            {% elif item.stock > 0 %}
                {{ item.name }}: {{ item.price }}
            {% else %}
                {{ item.name }} (sold out)
            {% endif %}
        {% endfor %}

        {% macro input(label, value="", required=false) %}
            <label>{{ label }}</label>
            <input value="{{ value }}" {% if required %}required{% endif %}>
        {% endmacro %}

        {{ input(label="Username", required=true) }}

        {% set count = items | length %}
        <p>Total: {{ count }}</p>

        {% raw %}
            {{ this will not be processed by Tera }}
        {% endraw %}
        {% endblock %}
    """.trimIndent()
}
