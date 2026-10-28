# Manual check in RustRover

The automated tests drive the plugin through the IDE's test fixtures; this list is for the things only a person looking at
the real editor can judge (feel, popups, icons). Install the build (`bash ./gradlew buildPlugin`, then Settings → Plugins →
⚙ → Install Plugin from Disk → `build/distributions/tera-<version>.zip`), open `examples/review-poc` as a project, and go
through the list. Write down anything that differs from "expected" in the last column.

## Editor basics (`templates/pages/new.html.tera`)

| Do | Expected |
|---|---|
| Type `{{` in the HTML part | `{{  }}` with the caret in the middle; Backspace twice removes the whole pair |
| Type `{%` and `{#` | `{%  %}` and `{#  #}` the same way |
| Type `{{` inside the `<script>` of `base.html.tera` | one pair, no `}}}` |
| Put the caret next to `{% for … %}` | the matching `{% endfor %}` is highlighted |
| Cmd+/ on a line with a Tera tag | wrapped in `{# … #}` |
| Fold `{% block content %}` | collapses to `{% block content %}…`; nested folds work |
| Type `forloop` then Tab; `comp` then Tab | the loop; a component call (Tera 2 project only); `macro` does nothing here |
| Cmd+Alt+L on a badly indented file | only the indentation of lines changes; `<script>` and `<pre>` bodies stay |

## Navigation

| Do | Expected |
|---|---|
| Cmd+click `{% block content %}` in `pages/detail.html.tera` | goes to `layouts/section.html.tera`; the gutter shows "Overrides block content" |
| Cmd+click `content` in `layouts/section.html.tera` (the topmost) | a list of the six pages |
| Gutter icon on `{% component ui.card %}` in `components.html.tera` | "N usages of ui.card", a list to pick from |
| Cmd+click a `<ui.card …/>` call | the definition |
| Cmd+click `"pages/detail.html"` in `src/controller/detail.rs` | opens the template; gutter icon beside the string |
| Type `show("pa` style: put the caret in a render string and Ctrl+Space | template names, without `.html` or with, as the project writes them |

## Variables and Rust types

| Do | Expected |
|---|---|
| In `layouts/app.html.tera`, type `{{ us` | `user` with its Rust type; hover shows the struct |
| Type `{{ cate` in `pages/new.html.tera` | `categories`, and below it the loop and join variants with a loop icon |
| Alt+Enter on `{{ categories }}` | Tera: for category in categories / … else / join |
| Same after `| sort`; after `| join(sep=", ")` | loop offered; nothing offered |
| `{{ categories }}` | a weak warning "is a list: it is printed as JSON" with the same fixes |
| `{{ categories | values | }}` | error "A filter name is expected after '|'" with "Remove the trailing |" |

## Refactoring

| Do | Expected |
|---|---|
| Shift+F6 on a `block` name | renames it in every template of the chain, `endblock` included |
| Shift+F6 on a `{% set x %}` / `for` variable | its uses in scope change, not another `x` outside |
| Shift+F6 on a component name | a dialog; every definition, call and closing tag changes; a taken name is refused |
| Rename `layouts/app.html.tera` in the project view | `extends "layouts/app.html"` in the pages and the Rust strings follow |
| Alt+F7 on a block or variable | the usages tool window lists them |

## Errors in the Run window

Break a template (e.g. `{{ nope }}` in `pages/stats.html.tera`), `cargo run`, open that page: the
` --> pages/stats.html:L:C` line in the Run window is a link that opens the template at that place.

## Settings (Settings → Languages & Frameworks → Tera)

Version, migration hints, the print warning, template roots: change one of each and see the effect (e.g. a root
`examples/review-poc/templates` lets a template outside it extend `layouts/app.html`).

## What the automated tests cannot tell you

Whether the popups and icons are in the right place and readable, whether Backspace/Tab feel natural, how it behaves
while typing fast in a very large template, and the look in the dark theme.
