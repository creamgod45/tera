+++
title = "Rust 錯誤處理的實際分工"
date = 2025-06-18
description = "thiserror 給函式庫，anyhow 給應用程式，邊界上做一次轉換。"

[taxonomies]
tags = ["rust", "errors"]
categories = ["backend"]

[extra]
series = "Rust 日常"
series_part = 1
+++

「什麼時候用 `thiserror`、什麼時候用 `anyhow`」其實不需要想太久。

<!-- more -->

## 分工

```rust
// 函式庫：呼叫端要能分辨錯誤種類
#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("request {0} not found")]
    NotFound(u32),
    #[error("storage is full")]
    Full,
}

// 應用程式：只需要帶上下文往上傳
fn load(id: u32) -> anyhow::Result<Request> {
    store::get(id).with_context(|| format!("loading request {id}"))
}
```

## 在邊界轉成回應

Web 應用的邊界是 handler。這裡把錯誤轉成狀態碼：

```rust
impl<'r> Responder<'r, 'static> for AppError {
    fn respond_to(self, _: &'r Request<'_>) -> response::Result<'static> {
        let status = match self {
            AppError::NotFound(_) => Status::NotFound,
            AppError::Invalid(_) => Status::UnprocessableEntity,
            AppError::Other(_) => Status::InternalServerError,
        };
        Response::build().status(status).ok()
    }
}
```

{% <callout kind="warning" title="別把內部錯誤顯示給使用者"> %}
`Other` 的訊息可能含有路徑或 SQL。記錄起來，回給使用者的只有「發生錯誤」與一個追蹤編號。
{% </callout> %}

## 取捨

- 多一層轉換，換來清楚的邊界。
- 錯誤種類少時，一個 enum 就夠了，不要先設計十種。
- 測試錯誤時，比對種類，不比對文字。
