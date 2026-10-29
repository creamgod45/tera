# Tera for JetBrains IDEs 使用者手冊

[English](USER-GUIDE.md) | 繁體中文

本手冊說明撰寫 [Tera](https://github.com/Keats/tera) 模板（`.tera`）時，這個外掛能幫你做什麼，以及怎麼使用每項功能。
技術細節與變更清單請見 `README.md` 和 `CHANGELOG.md`。

## 1. 安裝

1. 自行建置外掛（`./gradlew buildPlugin`，需要 JDK 21），或使用發行版的 `tera-<版本>.zip`。
2. 在 IDE 裡：**Settings → Plugins → ⚙ → Install Plugin from Disk…**，選擇 ZIP，然後重啟 IDE。
3. 本機建置的外掛會覆蓋同 ID（`com.tera.tera`）的已安裝外掛。兩次本機建置的版本號可能相同，所以安裝後請完整重啟 IDE。

**另一個 Tera 外掛**：本外掛宣告與原本的 `Tera` 外掛（`com.tera.tera`）不相容，因為兩者都註冊 `.tera` 檔案類型。兩者同時安裝時，IDE 會在 **Settings → Plugins** 回報衝突；請移除或停用其中一個，然後重啟。

**適用的 IDE**：任何有 HTML 支援的 JetBrains IDE。凡是牽涉 Rust 的功能（從結構體取得變數、Rust 字串中的模板名稱、在 Rust
程式碼裡重新命名模板）都需要 RustRover，或安裝了 Rust 外掛的 CLion／IntelliJ IDEA。

## 2. 五分鐘上手

打開一個 Rocket 或 Tera 專案，再開一個像 `templates/pages/detail.html.tera` 的模板。

| 想做的事 | 做法 |
|---|---|
| 看有哪些變數可用 | 輸入 `{{ ` 看補全清單；輸入 `task.` 看欄位 |
| 找變數從哪裡來 | Cmd/Ctrl+點擊變數，會跳到 Rust 欄位 |
| 從 Rust 字串跳到模板 | 在 `render(..)` 裡 Cmd/Ctrl+點擊 `"pages/detail"` |
| 跳到 block 的上層定義 | Cmd/Ctrl+點擊 block 名稱 |
| 找誰使用了某個元件 | 點該 `{% component %}` 行的邊欄圖示 |
| 重新命名 block、巨集、變數、元件或檔案 | Shift+F6 |
| 把 `{{ list }}` 變成迴圈 | 在變數上按 Alt+Enter，選 *Tera: for … in …* |
| 整理縮排 | Reformat Code（Cmd/Ctrl+Alt+L） |
| 跳到模板錯誤位置 | 點 Run 視窗裡的連結 |

## 3. 設定

**Settings → Languages & Frameworks → Tera**

- **Tera version（版本）**：自動、1.x 或 2.x。自動模式會讀最近的 `Cargo.toml`（`tera`，或代表 Tera 1 的
  `rocket_dyn_templates`），再讀 `Cargo.lock`，都沒有就用 2.x。版本決定可用的 filter、test、function 和標籤。
- **Template roots（模板根目錄）**：一行一個目錄，相對於專案或絕對路徑。解析模板名稱時（`extends`、`include`、`import`，以及
  Rust 裡的 `render("…")`）最先採用；其次是外掛從 `Rocket.toml`（`template_dir`）、`Tera::new("…")`、`relative!`、
  `set_current_dir` 偵測到的目錄；最後才是 `templates`。
- **In Tera 1.x projects, mark constructs that Tera 2 removed or renamed**（遷移提示）：在 Tera 1 專案裡，用刪除線標示 Tera 2 移除或改名的項目。
- **Mark a list or map that {{ }} prints as it is, with a quick fix to loop over it**（直接輸出清單或 map 的警告）：第 8 節的警告開關。

其他位置：**Settings → Languages & Frameworks → Template Data Languages**（指定模板的 HTML／CSS／JS 語言）、
**Editor → Color Scheme → Tera Template**（配色）。

## 4. 撰寫模板

**語法上色與結構**：Tera 標籤會上色（含空白控制 `{%-`、`-%}`），括號未成對或結尾標籤錯誤會被回報。模板內的 HTML、CSS、
JavaScript 使用 IDE 本身的支援：`.html.tera` 是 HTML，`.css.tera` 是 CSS，`.js.tera` 是 JavaScript。

**輸入輔助**：Cmd/Ctrl+/ 用 `{# #}` 註解。輸入 `{{`、`{%` 或 `{#` 會自動補上結尾並把游標放在中間（在空的成對符號上按
Backspace 會一起刪除）。`{{ }}`、`{% %}`、`{# #}` 可互相配對。區塊可摺疊（`block`、`for`、`if`、`macro`、`component`、
`filter`、`raw`、捕捉用的 `set`）。

**Live Template**（輸入縮寫後按 Tab）。語句型模板只在模板文字區提供；在尚未關閉的 `{{ }}` 或 `{% %}` 裡面，只會出現表達式型模板。

| 縮寫 | 結果 |
|---|---|
| `xx` | `{{ expr }}` |
| `block`、`inblock` | `{% block name %}…{% endblock name %}` |
| `if`、`ifi`、`ifelse`、`elif`、`else` | 條件式 |
| `forloop`、`forkv`、`forelse` | 迴圈、走訪 map（`key, value`）、含 `else` 的迴圈 |
| `extend`、`include`、`super` | `extends`、`include`、`{{ super() }}` |
| `filter`、`raw`、`comment`、`set`、`setglobal` | 對應的標籤 |
| `macro`、`import` | 僅 Tera 1 專案 |
| `component`、`comp`、`compbody`、`setblock`、`ternary`、`break`、`continue` | 僅 Tera 2 專案 |
| `comprehension` | Tera 2，僅在 `{{ }}`／`{% %}` 裡 |

**Reformat Code** 依 HTML 與 Tera 標籤的巢狀深度重新縮排，不改變其他任何東西：不會合併或拆開行，`pre`、`textarea`、
`script`、`style`、`{% raw %}` 和註解的內容保持原本的縮排。

## 5. 補全、懸停、文件

- 輸入 `{{ ` 會補全渲染情境的變數及其 Rust 型別；輸入 `task.` 補全欄位；迴圈變數（`{% for task in tasks %}`、
  `loop.index`）、`set` 變數和巨集參數都認得。
- `|` 補全 filter，`is` 補全 test；在 Tera 2 裡符合該值型別的排在前面。function、filter、test 的參數也會補全；
  滑鼠懸停會顯示簽章、預設值和範例。
- **巨集**（Tera 1）：`ns::` 之後會列出巨集並帶入必填參數；呼叫時會檢查未知或缺少的參數以及無法解析的 import。在巨集上方用
  `{# … #}` 註解寫說明，並可加 `@param name {type} 說明`，懸停時會顯示。
- **元件**（Tera 2）：`<` 之後補全元件名稱，標籤內補全屬性，也支援 `{% <ui.card> %}…{% </ui.card> %}` 的本體寫法。
- `extends`、`include`、`import` 的字串裡會補全模板名稱。

## 6. 變數從哪裡來（Rust）

外掛會找出渲染這個模板的 Rust 程式碼：任何「把模板路徑當字串傳入，並帶一個可序列化的值」的呼叫（`Serialize`、`tera::Context`、
`serde_json::Value`、`context!`），不限於 Rocket 的 `Template::render`。它能跟隨泛型的頁面外殼（`Page<T>` 加
`#[serde(flatten)]`）、`let ctx = …` 區域變數和 Rocket 的表單 `Context`。版面（layout）和局部模板會取得所有使用它們的頁面的變數。

- 只有在所有渲染呼叫都被讀懂時，才會回報未知變數（弱警告）和未知欄位。其他 crate 的型別不會被猜測；此時情境標示為不完整，
  不會回報任何東西。
- **你自己的 function、filter、test**：用 `register_function`、`register_filter`、`register_tester`（Tera 2 是
  `register_test`）註冊的，會連同參數和文件（Rust 的文件註解）一起提供。要告訴外掛某個 filter 回傳什麼，加上
  `/// @returns array<string>`（也可用 `map<T>`、`string`、`number`、`bool`、`T[]`）。
- **沒有 Rust 型別？** 用 JSON Schema 檔描述變數：放在模板旁的 `detail.html.schema.json`，或在模板目錄放
  `tera-context.json` 並用 `mappings` 對照表（glob → schema）。與 Rust 推得的變數衝突時，以 Rust 為準。格式見 `README.md`。

## 7. 導覽

- **跳到定義**（Cmd/Ctrl+點擊或 Cmd/Ctrl+B）：變數 → Rust 欄位；巨集、別名、參數名稱 → 定義；`extends`／`include`／
  `import` 的路徑 → 檔案；自訂 function／filter／test → Rust 程式碼。有多個來源時會開啟選擇清單，每一列顯示模板那一行的
  內容和 `檔名:行號`。
- **Block**：Cmd/Ctrl+點擊 `{% block x %}` 的名稱（或 `endblock`、`{{ super() }}`），會沿整條 `extends` 鏈跳到它所覆寫的
  上層 block；在最上層的定義上則列出覆寫它的模板。邊欄圖示會標示 *Overrides block x* 與 *Overridden in N templates*。
- **元件**：定義處的邊欄圖示顯示使用次數並開啟呼叫處清單；呼叫處的圖示跳到定義（同一行呼叫多個元件時只有一個圖示）。
- **從 Rust 到模板**：在 `render(..)` 的路徑字串上 Cmd/Ctrl+點擊，或使用旁邊的邊欄圖示。路徑的解讀方式與程式實際執行一致
  （`Rocket.toml`、`Tera::new`、`relative!`、`set_current_dir`、你設定的 Template roots）。在 Rust 裡輸入路徑時，會依專案
  既有的寫法列出所有模板。

## 8. 清單、map 與迴圈

直接輸出清單或 map（`{{ categories }}`）會得到使用者不想看的內容（Tera 2 是 `["Travel", "Meals"]`，Tera 1 的物件是
`[object]`）。外掛會標示出來並提供修正。把游標放在變數上，按 Alt+Enter：

- *Tera: for category in categories* → `{% for category in categories %}{{ category }}{% endfor %}`
- *for … else (when empty)* → 加上 `{% else %}` 分支的同一段
- *join* → `{{ categories | join(sep=", ") }}`（純值清單）
- map：`{% for key, value in map %}`；記錄清單則輸出第一個文字欄位

在保留或產生清單的 filter 之後也能用（`| sort`、`| reverse`、`| unique`、`| slice(..)`、map 的 `| values` 和 `| keys`），
以及回傳型別已知的自訂 filter 之後。例如游標在 `{{ categories | values | }}` 上用迴圈修正，整段會變成
`{% for value in categories | values %}{{ value }}{% endfor %}`。停在 `|` 之後的未完成鏈會報錯，並提供移除該 `|` 的修正。

## 9. 重構

**Shift+F6** 重新命名，**Alt+F7** 尋找使用處，適用於：

- **block**（最上層的定義是宣告；覆寫處與每個 `endblock x` 會跟著改，跨檔案）；
- **巨集**（Tera 1：定義、`ns::name(` 呼叫、`endmacro`）；
- **變數**：由 `set`、`set_global`、`for` 和巨集參數宣告的；
- **元件**：定義、兩種呼叫寫法與結尾標籤一次改完且可一次復原；已被使用的名稱會拒絕。
  （帶點的元件名稱如 `ui.card`，請用邊欄圖示、Cmd/Ctrl+點擊和重新命名；目前沒有 Alt+F7。）
- **模板檔案**：重新命名或移動檔案時，會更新 `extends`、`include`、`import` 以及 Rust 裡指到它的字串
  （`render("pages/detail", …)`，在 RustRover 中）。

## 10. Tera 1 與 Tera 2

- 在 Tera 1 專案裡，Tera 2 移除或改名的項目會以刪除線加弱警告標示，並提供快速修正（`as_str` → `str`、`macro` →
  `component`、位置參數 → 關鍵字參數……）。
- **Alt+Enter → Migrate this template to Tera 2** 會改寫整個檔案；**Code → Migrate Tera Templates to 2.x…** 會一次處理專案所有
  模板（可一次復原），並列出仍需人工處理的項目（例如 `date` 和 `slugify` 已移到 `tera-contrib`）。
- Tera 2 有型別，並在渲染時強制檢查。外掛會回報未知的參數型別、與型別不符的預設值、型別錯誤的屬性值，以及套用在錯誤種類
  值上的 filter／test，並在適用時提供 *Convert with `| str` first* 修正。
- 遷移無法改變引擎本身的行為：Tera 2 輸出陣列是 JSON、map 的順序、未定義變數會報錯等（詳見 `README.md`）。

## 11. Run 視窗中的錯誤

Rust 程式印出模板錯誤時，位置會變成連結：Tera 2 的 ` --> pages/detail.html:30:49`（包含 Rocket 的
`template 'x': error:` 之後），以及 Tera 1 的 `Failed to parse 'x'` 加 ` --> 3:10`。檔案會依模板根目錄尋找。
涵蓋 Run 工具視窗；Terminal 工具視窗不涵蓋。

## 12. 疑難排解

| 現象 | 可能原因與處理 |
|---|---|
| 補全沒有變數 | 找不到渲染呼叫，或型別未知。確認模板路徑是以字串傳入，且旁邊有 `Serialize` 的值；或改用 schema 檔描述資料。 |
| `extends` 或 Rust 字串找不到模板 | 把目錄加到 *Template roots*。 |
| filter 不對，或出現「Tera 2 已移除」提示 | 明確設定 **Tera version**。 |
| 使用清單仍只顯示一個單字 | 安裝的是舊版；重新安裝 ZIP 並完整重啟 IDE。 |
| Rust 相關功能沒反應 | 缺少或停用 Rust 外掛（需要 RustRover，或 CLion／IDEA 的 Rust 外掛）。 |
| Live Template 沒出現 | 語句型模板不會在未關閉的 `{{ }}` 或 `{% %}` 內提供；請在外面輸入，或改用 Alt+Enter。 |

## 13. 限制

診斷是結構檢查，不是完整的 Tera 執行環境。Rust 探索是靜態的：執行期才建立的值（在迴圈裡填的 map、`serde_json::Value`）
是未知的，用 `add_raw_template` 加入的模板沒有檔案。尚未完成：block 層級的懸停說明、帶點元件名稱的 Alt+F7、
使用清單中命中名稱的粗體標示、由 `macro_rules!` 產生的模板路徑。`MANUAL-CHECK.md` 列出建議在 IDE 裡親手試的項目。

## 14. Zola 網站

[Zola](https://www.getzola.org/) 用 Tera 渲染 `templates/*.html`，但外掛預設只開啟 `.tera` 檔。請告訴 IDE 你的 `.html` 檔是
Tera 模板：

1. **Settings → Editor → File Types → Tera Template → +**（Registered Patterns），加入 `*.html`。
2. 開啟 `templates/` 下的模板。`{{ }}` 與 `{% %}` 會上色，周圍的 HTML、CSS、JavaScript 照常運作。
3. Zola 網站沒有 `Cargo.toml`，Tera 版本會退回 2.x，這對 Zola 0.23 以後是正確的。舊版 Zola（Tera 1）請把 **Settings →
   Languages & Frameworks → Tera → Tera version** 設為 1.x。

這個樣式會套用到整個專案的所有 `.html` 檔，不只 `templates/`，適合純 Zola 的網站。若專案裡還有一般 HTML 頁面，請另開一個
IDE 專案，或接受它們也被當成 Tera 上色。

專案裡有 `zola.toml`（或舊版放在 `content/` 與 `templates/` 旁的 `config.toml`）時，外掛會多提供：

- **`.html` 裡的元件**：`macros.html` 裡的 `{% component post_card(page) %}` 在所有模板都找得到，有補全、懸停、跳到定義與
  重新命名。
- **Zola 函式與 filter**：`get_url`、`get_page`、`get_section`、`get_taxonomy`、`get_taxonomy_term`、`get_taxonomy_url`、
  `get_hash`、`get_image_metadata`、`resize_image`、`load_data`、`trans`，以及 filter `markdown`、`regex_replace`、
  `num_format`、`base64_encode`、`base64_decode`。
- **Zola 仍然提供的 filter**：`date`、`striptags`、`slugify`、`urlencode`、`json_encode`、`filesizeformat`、`spaceless`、
  `matching`、`now`、`get_random` 不會被標成 Tera 2 已移除。
- **變數**，依模板種類：`page.html` 有 `page`；`section.html` 與 `index.html` 有 `section`、`paginator`；
  `<分類>/list.html` 與 `single.html` 有 `taxonomy`、`terms`、`term`（分類必須寫在 `zola.toml`）；所有模板都有 `config`、
  `lang`、`zola_version`；除了 `404.html`，都有 `current_path`、`current_url`。有補全、懸停，欄位拼錯會警告（`page.titl`）。
  `extra` 與 `config.extra` 可以接任何欄位。
- **自訂模板**：Markdown front matter 用 `template = "project.html"`（或 `page_template`）指定的模板，會拿到頁面的變數
  （`_index.md` 則是區段）。`base.html` 與 partial 取用 extends 或 include 它們的模板的變數。無法判斷用途的模板不做檢查。

Zola 0.23 移除了 shortcode；請改寫成元件，用 `{{ <callout kind="note"/> }}` 呼叫。Markdown 內容裡的 Tera 語法要用
`{% raw %}` 包起來。`examples/Zola` 是用到上述所有功能的完整範例站。
