+++
title = "Zola 的 base_url：預覽、測試與正式環境"
date = 2025-05-06
description = "一份設定，三個環境：用 --base-url 覆寫，避免連結壞掉。"

[taxonomies]
tags = ["zola", "deploy"]
categories = ["tooling"]

[extra]
series = "Zola 筆記"
series_part = 1
+++

連結在本機正常、上線後全壞，通常是 `base_url` 的問題。

<!-- more -->

## 三個環境

- **本機**：`zola serve` 會自動使用本機網址。
- **預覽**：部署到暫時網址，需要覆寫 `base_url`。
- **正式**：使用 `zola.toml` 裡的值。

```bash
zola build --base-url "$PREVIEW_URL" --output-dir public
```

## 在模板中產生網址

不要手寫網址，用函式：

{% raw %}
```tera
<a href="{{ get_url(path="@/blog/_index.md") }}">Blog</a>
<img src="{{ get_url(path="img/logo.svg") }}" alt="logo">
```
{% endraw %}

`@/` 開頭是內容連結，檔案搬家時 Zola 會在建置時報錯，而不是上線後才 404。

## 檢查清單

1. `zola check` 檢查所有內部與外部連結。
2. 預覽網址與正式網址不同時，確認 canonical 標籤使用 `config.base_url`。
3. RSS 與 sitemap 都依 `base_url` 產生，覆寫後要重新建置。

看完這篇，可以回到 [Rocket 表單驗證](@/blog/rocket-form-context.md) 做一個完整的小專案。
