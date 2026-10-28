# Verifying the Tera 1 → 2 migration against the real engines

`cargo run` in this directory renders Rocket's `examples/forms` templates

* with **Tera 1.20.1**, the original templates (`src/test/resources/rocket/examples/forms/templates`), and
* with **Tera 2.4.0**, the templates as migrated by the plugin (`src/test/resources/rocket-tera2/forms/templates`),

for two render contexts shaped like Rocket's `form::Context` (empty, and with errors and submitted values), and compares
the output after collapsing whitespace.

Expected result:

```
OK    index    [default (Context::default())] identical after whitespace normalisation
OK    success  [default (Context::default())] identical after whitespace normalisation
OK    index    [with errors and values] identical after whitespace normalisation
OK*   success  [with errors and values] identical except that Tera 2 prints arrays as JSON (["a"] instead of [a])
```

`OK*` is a real behaviour change of the engine, not of the migration: `{{ value }}` on an array prints `[Bob]` in Tera 1
and `["Bob"]` in Tera 2. Use `{{ value | join(sep=", ") }}` if you want plain text.

Both engines are built with their `preserve_order` feature. Without it Tera 2 iterates maps in an arbitrary order, while
Rocket's context is an `IndexMap` and Tera 1 (with `preserve_order`) keeps its order.

The crate needs network access once, to fetch both versions of `tera`.
