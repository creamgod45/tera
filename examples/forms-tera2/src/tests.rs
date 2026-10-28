use rocket::http::{Header, Status};
use rocket::local::blocking::Client;

use super::rocket;

fn client() -> Client {
    Client::tracked(rocket()).expect("valid rocket")
}

/// Collapses whitespace so assertions do not depend on the indentation of the templates.
fn squash(html: &str) -> String {
    html.split_whitespace().collect::<Vec<_>>().join(" ")
}

const BOUNDARY: &str = "X-TERA2-BOUNDARY";

/// A `multipart/form-data` body: text fields plus the `submission.file` upload.
fn multipart(fields: &[(&str, &str)], pdf: bool) -> (Header<'static>, String) {
    let mut body = String::new();
    for (name, value) in fields {
        body.push_str(&format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n"));
    }
    let (file_name, file_type) = if pdf { ("paper.pdf", "application/pdf") } else { ("paper.txt", "text/plain") };
    body.push_str(&format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"submission.file\"; filename=\"{file_name}\"\r\n\
         Content-Type: {file_type}\r\n\r\n%PDF-1.4 test\r\n--{BOUNDARY}--\r\n"
    ));
    let content_type = Header::new("Content-Type", format!("multipart/form-data; boundary={BOUNDARY}"));
    (content_type, body)
}

fn valid_fields() -> Vec<(&'static str, &'static str)> {
    vec![
        ("account.name", "Ada Lovelace"),
        ("account.password.first", "analytical"),
        ("account.password.second", "analytical"),
        ("account.email", "ada@example.com"),
        ("submission.title", "Notes on the Analytical Engine"),
        ("submission.date", "1843-10-01"),
        ("submission.abstract", "A translation with notes."),
        ("submission.category", "Physics"),
        ("submission.category", "CS"),
        ("submission.rights", "Public"),
        ("submission.ready", "yes"),
    ]
}

#[test]
fn the_form_renders_with_its_components() {
    let client = client();
    let res = client.get("/").dispatch();
    assert_eq!(res.status(), Status::Ok);
    let html = squash(&res.into_string().unwrap());
    assert!(html.contains("Form Example"));
    // `<input .../>`, `<checkbox .../>`, `<select .../>` and `<textarea .../>` components of macros.html.tera
    for field in ["account.name", "account.email", "submission.title", "submission.date", "submission.rights",
                  "submission.category", "submission.abstract", "submission.file", "submission.ready"] {
        assert!(html.contains(&format!("name=\"{field}\"")), "missing field {field}");
    }
    assert!(html.contains("<option value=\"Exclusive\""));
    assert!(!html.contains("failed to validate"), "no errors on a fresh form");
}

#[test]
fn a_valid_submission_shows_the_success_page() {
    let (content_type, body) = multipart(&valid_fields(), true);
    let client = client();
    let res = client.post("/").header(content_type).body(body).dispatch();
    assert_eq!(res.status(), Status::Ok);
    let html = squash(&res.into_string().unwrap());
    assert!(html.contains("Success!"));
    assert!(html.contains("<strong>account.name</strong>"));
    assert!(html.contains("Ada Lovelace"));
    assert!(html.contains("Notes on the Analytical Engine"));
}

#[test]
fn invalid_input_rerenders_the_form_with_errors_and_the_submitted_values() {
    let mut fields = valid_fields();
    fields.retain(|(n, _)| *n != "account.email" && *n != "account.password.second");
    fields.push(("account.email", "not-an-email"));
    fields.push(("account.password.second", "different"));
    let (content_type, body) = multipart(&fields, true);
    let client = client();
    let res = client.post("/").header(content_type).body(body).dispatch();
    assert_eq!(res.status(), Status::UnprocessableEntity);
    let html = squash(&res.into_string().unwrap());
    // `errors | length | pluralize` and the `errors_for` component
    assert!(html.contains("failed to validate"), "{html}");
    assert!(html.contains("invalid email address"), "{html}");
    assert!(html.contains("class=\"error\""), "{html}");
    // the `value_for` component puts the submitted value back
    assert!(html.contains("value='Ada Lovelace'"), "{html}");
    assert!(html.contains("value='not-an-email'"), "{html}");
}

#[test]
fn the_error_count_is_pluralised() {
    let (content_type, body) = multipart(&[("submission.title", "x")], false);
    let client = client();
    let res = client.post("/").header(content_type).body(body).dispatch();
    let html = squash(&res.into_string().unwrap());
    assert!(html.contains("fields failed to validate"), "{html}");
}

#[test]
fn checkboxes_come_back_checked() {
    let mut fields = valid_fields();
    fields.retain(|(n, _)| *n != "account.email");
    let (content_type, body) = multipart(&fields, true);
    let client = client();
    let res = client.post("/").header(content_type).body(body).dispatch();
    let html = squash(&res.into_string().unwrap());
    assert!(html.contains("value=Physics checked"), "{html}");
    assert!(html.contains("value=CS checked"), "{html}");
    assert!(!html.contains("value=Biology checked"), "{html}");
}

#[test]
fn submitted_html_is_escaped_exactly_once() {
    let mut fields = valid_fields();
    fields.retain(|(n, _)| *n != "account.email");
    fields.push(("account.email", "<script>alert(1)</script>"));
    let (content_type, body) = multipart(&fields, true);
    let client = client();
    let res = client.post("/").header(content_type).body(body).dispatch();
    let html = res.into_string().unwrap();
    assert!(!html.contains("<script>alert(1)"), "raw script tag in the page");
    assert!(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "escaped once");
    assert!(!html.contains("&amp;lt;"), "not escaped twice");
}

#[test]
fn the_page_route_uses_a_macro_generated_context_and_a_tera_contrib_function() {
    let client = client();
    let res = client.get("/page").dispatch();
    assert_eq!(res.status(), Status::Ok);
    let body = res.into_string().unwrap();
    let lines: Vec<&str> = body.lines().filter(|l| !l.trim().is_empty()).collect();
    assert_eq!(lines[0], "hello world!!");
    assert_eq!(lines[1], "1");
    // now() from tera-contrib: an RFC 3339 timestamp
    assert!(lines[2].contains('T') && lines[2].len() >= 19, "{}", lines[2]);
}

#[test]
fn the_stylesheet_is_served() {
    let client = client();
    assert_eq!(client.get("/chota.min.css").dispatch().status(), Status::Ok);
}
