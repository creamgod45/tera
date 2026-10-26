# Rocket regression fixtures

These eight templates are copied verbatim from the local Rocket checkout at commit
`58a3554a5001a9228b09bdefa87adc8939d818dc`. See `manifest.json` for source paths,
SHA-256 checksums and expected Tera diagnostic counts. Distributed under Rocket's
MIT license (included as `LICENSE-MIT`). Do not repair or simplify these fixtures.

All templates must produce zero Tera diagnostics. This does not mean their HTML
is error-free: for example, forms/templates/success.html.tera has an unclosed div.
HTML assertions check preserved source text, real tag PSI and source offsets;
HTML diagnostics should remain the responsibility of the native HTML service.
The original fixtures include CSS but no inline JavaScript. Separate integration
fixtures cover JavaScript and direct CSS/JS templates without altering Rocket files.
