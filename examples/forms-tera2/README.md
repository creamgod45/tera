# Rocket's `examples/forms`, on Tera 2

A standalone Rocket 0.5 application: Rocket's form example with its templates migrated from Tera 1 to **Tera 2.4**.

```
cargo run      # http://localhost:8000
cargo test     # 8 tests: the real form Context, real multipart uploads, escaping, components
```

`rocket_dyn_templates` still uses Tera 1, so `src/main.rs` loads the templates into a `tera::Tera` itself (about 30 lines, the
`Templates` type) and keeps it in Rocket's managed state. The routes, the form types and the validation are Rocket's own.

## What changed against `examples/forms`

| | Tera 1 (Rocket) | Tera 2 (here) |
|---|---|---|
| dependency | `rocket_dyn_templates` with feature `tera` | `tera = "2.4"` (+ `preserve_order`), `tera-contrib` |
| rendering | `Template::render("index", &ctx)` | `templates.render("index.html", &ctx)` on a managed `Templates` |
| `{% import "macros" as m %}` | needed | gone: components are global |
| `{% macro input(...) %}` / `m::input(...)` | macros | `{% component input(..., @errors) %}` / `<input .../>` |
| `values` / `errors` read inside a macro | macros fall back to the render context | components do not: `@values`, `@errors` implicit parameters |
| `{{ now() }}` (`page.html.tera`) | built in | `tera.register_function("now", tera_contrib::dates::now)` (feature `date`) |
| auto-escaping | by file extension | templates are named `index.html`, `autoescape_on([".html"])` |

`templates/` was produced by the plugin's **Migrate this template to Tera 2**; `macros.html.tera`, `index.html.tera` and
`success.html.tera` are the same files as the golden ones in the plugin's tests (`src/test/resources/rocket-tera2`), which are
also compared against the Tera 1 output by `../../tools/verify-migration`.

## What the tests would catch

They fail if a component loses an implicit parameter (the forms render without errors and values), if auto-escaping is off or
applied twice, if the form `Context` is no longer understood, or if `now()` is not registered.

`/page` is the `macro_rules!` generated context (`page_ctx!`) the plugin reads for completion.
