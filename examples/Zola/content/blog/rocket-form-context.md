+++
title = "Rocket 表單驗證：從 Context 到錯誤訊息"
date = 2025-03-02
updated = 2025-03-20
description = "用 Rocket 的 Form 與 Context 做表單驗證，並讓錯誤顯示在對應欄位旁邊。"
aliases = ["/rocket-forms/"]

[taxonomies]
tags = ["rust", "rocket", "forms"]
categories = ["backend"]

[extra]
series = "Rocket 實戰"
series_part = 1
cover_note = "表單驗證的三個層次"
+++

表單驗證最容易寫成一團。這篇把它拆成三層：**解析**、**驗證**、**顯示**，每一層只做一件事。

<!-- more -->

## 解析：交給 `FromForm`

Rocket 的 `#[derive(FromForm)]` 會把請求內容轉成結構。轉換失敗時，會把每個欄位的錯誤收集在 `Context` 裡，而不是直接回傳 422。

```rust
#[derive(Debug, FromForm)]
struct Submit<'v> {
    #[field(validate = len(1..=80))]
    title: &'v str,
    #[field(validate = range(1..=10_000))]
    amount: u32,
    category: Category,
}

#[post("/", data = "<form>")]
fn submit<'r>(form: Form<Contextual<'r, Submit<'r>>>) -> (Status, Template) {
    let template = match form.value {
        Some(ref s) => Template::render("success", context! { title: s.title }),
        None => Template::render("index", &form.context),
    };
    (form.context.status(), template)
}
```

{% <callout kind="tip" title="為什麼回傳 Status"> %}
`form.context.status()` 在驗證失敗時是 422，瀏覽器和測試都能依狀態碼判斷，而不是去比對頁面文字。
{% </callout> %}

## 驗證：欄位規則放在型別旁邊

規則寫在欄位上，比寫在 handler 裡更容易維護。常用的幾種：

| 規則 | 用途 | 範例 |
|------|------|------|
| `len(a..=b)` | 字串長度 | `len(1..=80)` |
| `range(a..=b)` | 數字範圍 | `range(1..=10_000)` |
| `eq(x)` | 必須等於 | 條款同意 |
| `contains(x)` | 集合包含 | 選項限制 |

多個欄位彼此依賴時（例如「結束日期晚於開始日期」），用自訂驗證函式，回傳 `form::Result<'_, ()>`。

## 顯示：在欄位旁邊顯示錯誤

模板只需要拿 `context.field_errors`：

{% raw %}
```html
{% for error in errors %}
  <p class="error">{{ error.msg }}</p>
{% endfor %}
```
{% endraw %}

下一篇會把這個重複的區塊整理成元件，見 [Tera 2 元件入門](@/blog/tera2-components.md)。

## 小結

- 解析失敗不等於程式失敗，它是要顯示給使用者的資料。
- 規則靠近型別，顯示靠近欄位。
- 狀態碼要誠實[^status]。

[^status]: 有些人習慣驗證失敗也回 200，之後測試時會很難區分成功與失敗。
