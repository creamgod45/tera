+++
title = "關於我"
template = "about.html"
description = "這個網站和它的作者。"

[extra]
skills = [
    { name = "Rust", level = "daily", note = "Rocket、Tokio、serde" },
    { name = "Templates", level = "daily", note = "Tera 1 / 2、Zola" },
    { name = "TypeScript", level = "often", note = "Vite、Astro" },
    { name = "Kotlin", level = "sometimes", note = "IntelliJ 外掛" },
]
timeline = [
    { year = "2023", text = "第一個 Rocket 專案上線。" },
    { year = "2024", text = "開始把模板檢查做成 IDE 外掛。" },
    { year = "2025", text = "外掛支援 Tera 2 與元件。" },
]
+++

我平常用 Rust 寫後端，模板用 Tera。這個網站用 [Zola](https://www.getzola.org/) 產生，樣式是自己寫的，沒有用任何前端框架。

## 這個站的原則

1. 內容是 Markdown，放進 Git。
2. 頁面能在沒有 JavaScript 時閱讀；JavaScript 只用來切換主題。
3. 樣式用一份 Sass，圓角和色彩都是變數。

想看我最近在做什麼，到 [專案](@/projects/_index.md) 或 [部落格](@/blog/_index.md)。
