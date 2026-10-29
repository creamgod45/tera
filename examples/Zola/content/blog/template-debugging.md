+++
title = "模板除錯：從「找不到變數」到可重現"
date = 2025-07-22
description = "把模板錯誤變成可以寫成測試的東西。"

[taxonomies]
tags = ["tera", "testing", "templates"]
categories = ["tooling"]

[extra]
series = "Rocket 實戰"
series_part = 3
+++

模板錯誤常在執行時才出現，而且只在某個資料形狀下出現。

<!-- more -->

## 常見的三種錯誤

1. **變數不存在**：拼錯，或 handler 沒傳。
2. **型別不符**：把清單直接 {% raw %}`{{ items }}`{% endraw %} 印出來。
3. **區塊沒關**：{% raw %}`{% if %}`{% endraw %} 少一個 {% raw %}`{% endif %}`{% endraw %}。

三種都能在編輯時發現，不必等到執行。

## 把畫面寫成測試

```rust
#[test]
fn stats_page_shows_the_busiest_department() {
    let client = Client::tracked(rocket()).unwrap();
    let html = client.get("/stats").dispatch().into_string().unwrap();
    assert!(html.contains("Busiest department"));
}
```

測試要夠小，一個畫面一個斷言。

## 流程

1. 先用最小資料重現。
2. 寫成測試，確認會失敗。
3. 修正。
4. 保留測試。

在 [Rocket 表單驗證](@/blog/rocket-form-context.md) 提過，狀態碼也是測試的一部分。
