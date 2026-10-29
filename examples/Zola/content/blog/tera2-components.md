+++
title = "Tera 2 元件入門：把重複的區塊變成標籤"
date = 2025-04-11
description = "用 Tera 2 的元件取代 macro，語法更接近 HTML，參數有型別。"

[taxonomies]
tags = ["rust", "tera", "templates"]
categories = ["frontend", "backend"]

[extra]
series = "Rocket 實戰"
series_part = 2
+++

Tera 2 新增了**元件**：用像 HTML 的標籤呼叫，參數可以標型別。先看效果。

<!-- more -->

## 定義一個元件

{% raw %}
```tera
{% component ui.badge(status: string, tone: string = "neutral") %}
  <span class="badge badge-{{ tone }}">{{ status }}</span>
{% endcomponent %}
```
{% endraw %}

使用時像寫標籤：

{% raw %}
```tera
{{ <ui.badge status={request.status} tone="success"/> }}
```
{% endraw %}

## 和 macro 的差別

| | macro | component |
|---|---|---|
| 呼叫 | {% raw %}`{{ m::badge(status="ok") }}`{% endraw %} | `<ui.badge status="ok"/>` |
| 參數型別 | 無 | 可標註 |
| 內容傳入 | 不支援 | 支援（slot） |
| 適合 | 小片段 | 版面元件 |

{% <callout kind="note" title="遷移"> %}
從 Tera 1 遷移時，不必一次改完。macro 還能用，先把最常重複的區塊換成元件。
{% </callout> %}

## 推導式取代 `map` 與 `filter`

Tera 1 的 `| map(attribute="amount")` 在 Tera 2 變成推導式：

{% raw %}
```tera
{% set total = [d.amount for d in stats.by_department] | sort | last %}
```
{% endraw %}

中括號是推導式語法的一部分，而且 `d` 只存在於括號之中。

## 實作順序

1. 找出頁面裡出現三次以上的 HTML。
2. 抽成元件，先不標型別。
3. 補上型別，讓錯誤在編輯時就出現。
4. 最後處理 slot。

下一篇：[部署時的 `base_url`](@/blog/zola-base-url.md)。
