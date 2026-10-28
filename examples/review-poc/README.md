# review-poc: ExpenseFlow

A quick proof of concept: an expense **request → review → statistics** app on Rocket 0.5 and **Tera 2.4**, styled
with Tailwind (CDN). It exists to show what the template layer buys you, so it is deliberately small: in-memory
store, one fixed reviewer, no login.

```sh
cd examples/review-poc
cargo run      # http://127.0.0.1:8001
cargo test     # 23 tests through Rocket's local client
```

## What is in it

| Page | Shows |
|------|-------|
| `/` dashboard | stat cards, approval split bars, the review queue (largest first) |
| `/requests` | status tabs with counts, department filter, striped table, filtered total, empty state |
| `/requests/new` | validated form: Rocket `Contextual` errors and typed values flow back into the fields |
| `/requests/<id>` | details, history timeline, review form (reject needs a comment) or the recorded decision |
| `/stats` | department and category bars, a monthly column chart, a ranked scorecard |

## Rust side

```
src/main.rs         wiring only: manage state, mount controller::routes()
src/controller/     one module per area: dashboard, requests, submit, detail, stats, errors
src/forms.rs        input forms and validation
src/view.rs         the page shell every template receives (user, flash, active nav)
src/templates.rs    the Tera 2 engine
src/store.rs        in-memory data and statistics
```

## The layout is four levels deep

```
base.html          <html>, Tailwind, blocks: title / head / body
 └ layouts/app     sidebar, topbar, flash message         block: main
    └ layouts/section   page header (eyebrow, heading, subtitle, actions)   block: content
       └ pages/*        the page itself
```

Each layer stamps `data-layer="…"`, and a test asserts all four appear in that order on every page.

## Where Tera pays off (measured on this project)

| Measure | Value |
|---------|-------|
| Template lines, all 11 files | 461 |
| Rust lines (app, without tests): controllers 240, forms/view/templates 136, store 187, `main.rs` 29 | 592 |
| Components defined once (`components.html.tera`) | 11 (`ui.*`, `form.*`) |
| Component call sites across the pages | 50 |
| Layout levels / `{% block %}` tags | 4 / 30 |
| Rust code that builds HTML | none: handlers return data, templates decide markup |

What that looks like in practice:

- **Forms are three lines per field.** `<form.field name="amount" type="number"/>` reads `errors` and `values` from the
  caller (`@errors`, `@values` implicit parameters), so no handler ever copies values back into the template.
- **Statistics math lives in the template where the numbers are drawn.** `value*100//max` for bar widths,
  `m.month[5:]` slicing for labels, list comprehensions, `sort(attribute=)`, ternaries: no view-model code per page.
- **A design change is one edit.** Change `ui.card` or `ui.badge` once and every page follows; a new page is a
  `{% extends %}` plus its blocks.
- **Mistakes surface before running.** The plugin knows each component's signature: a missing required parameter, an
  unknown component or a Tera 1 leftover is flagged in the editor, and `TeraReviewPocExampleTest` asserts the whole
  template set has no error or warning and that every component is both defined and used.

Honest limits: this is a PoC measured in lines and call sites, not a timed study; the Tailwind CDN script is for demos,
not production; and `rocket_dyn_templates` is Tera 1 only, so the app keeps its own small `Templates` type in `main.rs`.

## Tera 2 features used

components with typed/default/implicit/rest parameters, `{% <name> %}` body calls, map and list literals, spread
`[...a, b]`, list comprehensions, slicing, ternary `a if c else b`, `?.` and `?[`, `{% set_global %}`,
`tera.autoescape_on([".html"])`.
