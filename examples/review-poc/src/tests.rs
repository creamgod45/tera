use rocket::http::{ContentType, Status};
use rocket::local::blocking::{Client, LocalResponse};

use super::rocket;

fn client() -> Client {
    Client::tracked(rocket()).expect("valid rocket")
}

fn body(res: LocalResponse<'_>) -> String {
    res.into_string().expect("a body")
}

fn get(client: &Client, uri: &str) -> String {
    let res = client.get(uri.to_string()).dispatch();
    assert_eq!(res.status(), Status::Ok, "GET {uri}");
    body(res)
}

fn post(client: &Client, uri: &str, form: &str) -> (Status, Option<String>, String) {
    let res = client.post(uri.to_string()).header(ContentType::Form).body(form.to_string()).dispatch();
    let (status, location) = (res.status(), res.headers().get_one("Location").map(String::from));
    (status, location, res.into_string().unwrap_or_default())
}

fn squash(html: &str) -> String {
    html.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// `attr="value"` of the first element carrying `marker`, e.g. `data-pct` of `data-bar="sales"`.
fn attr_of(html: &str, marker: &str, attr: &str) -> String {
    let at = html.find(marker).unwrap_or_else(|| panic!("no {marker}"));
    let rest = &html[at..];
    let start = rest.find(&format!("{attr}=\"")).unwrap_or_else(|| panic!("no {attr} after {marker}")) + attr.len() + 2;
    rest[start..].split('"').next().unwrap().to_string()
}

/// Visible text only: tags removed, whitespace collapsed.
fn text(html: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for c in html.chars() {
        match c { '<' => { in_tag = true; out.push(' ') } '>' => in_tag = false, c if !in_tag => out.push(c), _ => {} }
    }
    squash(&out)
}

fn count(html: &str, needle: &str) -> usize {
    html.matches(needle).count()
}

const VALID: &str = "applicant=Zed+Zhang&email=zed%40example.com&department=Sales&category=Travel&amount=120&date=2026-10-27\
                     &reason=Train+tickets+to+the+customer+meeting&urgent=on";

// ── the three-level layout ───────────────────────────────────────────────────

#[test]
fn every_page_is_assembled_from_four_layers_in_order() {
    let client = client();
    for uri in ["/", "/requests", "/requests/new", "/requests/1", "/stats"] {
        let html = get(&client, uri);
        let at = |m: &str| html.find(m).unwrap_or_else(|| panic!("{uri}: no {m}"));
        let (base, app, section, page) = (at("data-layer=\"base\""), at("data-layer=\"app\""), at("data-layer=\"section\""), at("data-layer=\"page\""));
        assert!(base < app && app < section && section < page, "{uri}: layers out of order");
        assert_eq!(count(&html, "<html"), 1, "{uri}: one document");
        assert!(html.contains("cdn.tailwindcss.com"), "{uri}");
        assert!(html.contains("ExpenseFlow"), "{uri}");
    }
}

#[test]
fn the_title_block_and_the_active_navigation_follow_the_page() {
    let client = client();
    let html = get(&client, "/stats");
    assert!(html.contains("<title>Statistics · ExpenseFlow</title>"));
    let nav = |html: &str, name: &str| attr_of(html, &format!("data-nav=\"{name}\""), "class");
    assert!(nav(&html, "statistics").contains("bg-indigo-50"));
    assert!(nav(&html, "dashboard").contains("text-slate-600"));
    let new = get(&client, "/requests/new");
    assert!(nav(&new, "new request").contains("bg-indigo-50"));
}

#[test]
fn unknown_pages_get_the_same_layout() {
    let client = client();
    for uri in ["/nope", "/requests/999"] {
        let res = client.get(uri).dispatch();
        assert_eq!(res.status(), Status::NotFound, "{uri}");
        let html = body(res);
        assert!(html.contains("Page not found"), "{uri}");
        assert!(html.contains("data-layer=\"app\"") && html.contains("data-layer=\"section\""), "{uri}");
    }
}

// ── dashboard ────────────────────────────────────────────────────────────────

#[test]
fn the_dashboard_numbers_match_the_seed_data() {
    let html = squash(&get(&client(), "/"));
    for expected in ["$23255 requested", "$12795 waiting", "$8620 paid out", "77%", "5 requests waiting for review"] {
        assert!(html.contains(expected), "missing {expected:?}");
    }
    assert_eq!(attr_of(&html, "data-stat=\"total requests\"", "data-stat"), "total requests");
    assert!(html.contains("data-bar=\"approved\" data-pct=\"50\""), "7 of 14 approved");
}

#[test]
fn the_review_queue_lists_the_largest_request_first() {
    let html = get(&client(), "/");
    let waiting = &html[html.find("data-list=\"waiting\"").unwrap()..];
    let order: Vec<usize> = ["Tara Singh", "Quentin Ward", "Vera Novak", "Noor Haddad", "Wen Zhao"].iter()
        .map(|n| waiting.find(n).unwrap_or_else(|| panic!("{n} missing"))).collect();
    assert!(order.windows(2).all(|w| w[0] < w[1]), "not sorted by amount: {order:?}");
    assert!(waiting.contains("Urgent"), "Quentin's request is urgent");
}

// ── the list ─────────────────────────────────────────────────────────────────

#[test]
fn the_list_shows_everything_and_counts_per_status() {
    let raw = get(&client(), "/requests");
    let html = text(&raw);
    assert_eq!(count(&raw, "data-row=\""), 14);
    assert!(html.contains("All 14") && html.contains("Pending 5") && html.contains("Approved 7") && html.contains("Rejected 2"), "{html}");
    assert!(raw.contains("data-total=\"amount\">$23255"));
}

#[test]
fn filters_narrow_the_rows_and_the_total() {
    let client = client();
    let pending = squash(&get(&client, "/requests?status=pending"));
    assert_eq!(count(&pending, "data-row=\""), 5);
    assert!(pending.contains("data-total=\"amount\">$12795") && pending.contains("(filtered)"));
    let design = squash(&get(&client, "/requests?department=Design"));
    assert_eq!(count(&design, "data-row=\""), 3);
    assert!(design.contains("Mina Cho") && design.contains("Noor Haddad") && design.contains("Wen Zhao") && !design.contains("Ada Lovelace"));
    let both = squash(&get(&client, "/requests?status=approved&department=Sales"));
    assert_eq!(count(&both, "data-row=\""), 2);
    assert!(both.contains("data-total=\"amount\">$3410"));
}

#[test]
fn an_empty_result_shows_the_empty_state_component() {
    let html = get(&client(), "/requests?status=rejected&department=Design");
    assert_eq!(count(&html, "data-row=\""), 0);
    assert!(html.contains("data-ui=\"empty\"") && html.contains("No request matches these filters."));
}

#[test]
fn rows_are_newest_first_and_striped() {
    let html = get(&client(), "/requests");
    let first = html.find("data-row=\"14\"").unwrap();
    let last = html.find("data-row=\"1\"").unwrap();
    assert!(first < last);
    assert!(html.contains("bg-slate-50/60\" data-row=\"14\""), "loop.index 1 is odd");
    assert!(html.contains("bg-white\" data-row=\"13\""));
}

// ── submitting ───────────────────────────────────────────────────────────────

#[test]
fn the_form_is_built_from_form_components() {
    let html = get(&client(), "/requests/new");
    for field in ["applicant", "email", "department", "category", "amount", "date", "reason", "urgent"] {
        assert!(html.contains(&format!("data-field=\"{field}\"")), "missing {field}");
    }
    assert!(html.contains("<option value=\"Finance\""));
    assert!(!html.contains("data-error="), "a fresh form has no errors");
    assert!(attr_of(&html, "id=\"date\"", "value").len() == 10, "today's date is the default");
}

#[test]
fn a_valid_submission_is_stored_and_confirmed() {
    let client = client();
    let (status, location, _) = post(&client, "/requests", VALID);
    assert_eq!(status, Status::SeeOther);
    assert_eq!(location.as_deref(), Some("/requests/15"));
    let page = squash(&get(&client, "/requests/15"));
    assert!(page.contains("data-flash=\"success\">Request #15 was submitted and is waiting for review."));
    assert!(page.contains("Zed Zhang") && page.contains("$120") && page.contains("Sales") && page.contains("data-status=\"pending\""));
    assert!(squash(&get(&client, "/")).contains("6 requests waiting for review"));
    assert_eq!(count(&get(&client, "/requests"), "data-row=\""), 15);
}

#[test]
fn an_invalid_submission_shows_every_error_and_keeps_what_was_typed() {
    let client = client();
    let (status, _, html) = post(&client, "/requests",
        "applicant=Zed&email=nope&department=Sales&category=Travel&amount=0&date=2026-10-27&reason=short");
    assert_eq!(status, Status::UnprocessableEntity);
    for field in ["email", "amount", "reason"] {
        assert!(html.contains(&format!("data-error=\"{field}\"")), "no error shown for {field}");
    }
    assert!(html.contains("invalid email address"));
    assert!(!html.contains("data-error=\"applicant\""), "applicant was fine");
    assert!(attr_of(&html, "id=\"email\"", "value") == "nope", "the typed email comes back");
    assert!(attr_of(&html, "id=\"applicant\"", "value") == "Zed");
    assert!(html.contains("<option value=\"Sales\" selected>"), "the chosen department is kept");
    assert!(html.contains(">short</textarea>"));
    assert_eq!(count(&get(&client, "/requests"), "data-row=\""), 14, "nothing was stored");
}

#[test]
fn what_users_type_is_escaped_exactly_once() {
    let client = client();
    let (status, _, html) = post(&client, "/requests",
        "applicant=%3Cb%3Ex%3C%2Fb%3E&email=nope&department=Sales&category=Travel&amount=5&date=2026-10-27&reason=%3Cscript%3Ealert(1)%3C%2Fscript%3E");
    assert_eq!(status, Status::UnprocessableEntity);
    assert!(!html.contains("<b>x</b>") && !html.contains("<script>alert(1)"));
    assert!(html.contains("&lt;b&gt;x&lt;&#x2F;b&gt;") || html.contains("&lt;b&gt;x&lt;/b&gt;"), "escaped once");
    assert!(!html.contains("&amp;lt;"));

    let (_, location, _) = post(&client, "/requests",
        "applicant=Eve&email=eve%40example.com&department=Sales&category=Travel&amount=5&date=2026-10-27&reason=%3Cscript%3Ealert(1)%3C%2Fscript%3E+and+more");
    let detail = get(&client, location.as_deref().unwrap());
    assert!(!detail.contains("<script>alert(1)") && detail.contains("&lt;script&gt;alert(1)"));
}

// ── reviewing ────────────────────────────────────────────────────────────────

#[test]
fn a_pending_request_offers_the_review_form_and_a_decided_one_shows_the_decision() {
    let client = client();
    let pending = get(&client, "/requests/5");
    assert!(pending.contains("data-slot=\"review\"") && pending.contains("name=\"decision\" value=\"approve\""));
    let decided = get(&client, "/requests/1");
    assert!(!decided.contains("data-slot=\"review\""));
    let decided = squash(&decided);
    assert!(decided.contains("data-slot=\"decision\"") && decided.contains("Morgan Lee") && decided.contains("Within policy."));
    assert!(decided.contains("Approved by Morgan Lee") || decided.contains("Approved <span"), "history has the decision step");
}

#[test]
fn approving_moves_the_numbers() {
    let client = client();
    let before = squash(&get(&client, "/"));
    assert!(before.contains("5 requests waiting"));
    let (status, location, _) = post(&client, "/requests/5/review", "decision=approve&comment=");
    assert_eq!(status, Status::SeeOther);
    let page = squash(&get(&client, location.as_deref().unwrap()));
    assert!(page.contains("data-flash=\"success\">Request #5 was approved."));
    assert!(page.contains("data-status=\"approved\"") && !page.contains("data-slot=\"review\""));
    let after = squash(&get(&client, "/"));
    assert!(after.contains("4 requests waiting") && after.contains("$11295 waiting") && after.contains("$10120 paid out"));
    assert!(after.contains("80%"), "8 of 10 decided");
}

#[test]
fn rejecting_needs_a_comment() {
    let client = client();
    let (status, _, html) = post(&client, "/requests/5/review", "decision=reject&comment=no");
    assert_eq!(status, Status::UnprocessableEntity);
    assert!(html.contains("a rejection needs a comment of at least 5 characters"));
    assert!(html.contains("data-status=\"pending\""), "still pending");
    assert!(html.contains(">no</textarea>"), "the short comment is kept");
    let (status, location, _) = post(&client, "/requests/5/review", "decision=reject&comment=Over+the+team+budget");
    assert_eq!(status, Status::SeeOther);
    let page = squash(&get(&client, location.as_deref().unwrap()));
    assert!(page.contains("data-status=\"rejected\"") && page.contains("Over the team budget") && page.contains("Rejected <span"));
}

#[test]
fn a_request_can_only_be_reviewed_once() {
    let client = client();
    assert_eq!(post(&client, "/requests/6/review", "decision=approve&comment=").0, Status::SeeOther);
    let (status, location, _) = post(&client, "/requests/6/review", "decision=reject&comment=Changed+my+mind");
    assert_eq!(status, Status::SeeOther);
    let page = squash(&get(&client, location.as_deref().unwrap()));
    assert!(page.contains("data-flash=\"error\">That request was already reviewed."));
    assert!(page.contains("data-status=\"approved\""));
}

// ── statistics ───────────────────────────────────────────────────────────────

#[test]
fn department_bars_are_scaled_to_the_largest() {
    let html = get(&client(), "/stats");
    // Sales 7510 is the largest; the others as a percentage of it, computed in the template with `//`.
    for (dept, pct) in [("sales", 100), ("engineering", 84), ("operations", 79), ("design", 28), ("finance", 17)] {
        assert_eq!(attr_of(&html, &format!("data-bar=\"{dept}\""), "data-pct"), pct.to_string(), "{dept}");
    }
}

#[test]
fn the_monthly_chart_has_one_column_per_month() {
    let html = get(&client(), "/stats");
    let chart = &html[html.find("data-chart=\"months\"").unwrap()..];
    let heights: Vec<(String, String)> = ["2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"].iter()
        .map(|m| (m.to_string(), attr_of(chart, &format!("data-month=\"{m}\""), "data-height"))).collect();
    let expected = [15, 30, 17, 61, 68, 100];
    for ((m, got), want) in heights.iter().zip(expected) {
        assert_eq!(got, &want.to_string(), "{m}");
    }
    assert!(chart.contains(">05/26<") && chart.contains(">10/26<"), "labels are sliced from the month string");
}

#[test]
fn headline_figures_are_derived_in_the_template() {
    let html = squash(&get(&client(), "/stats"));
    assert!(html.contains("$1661"), "average request, 23255 // 14");
    assert!(html.contains(">Engineering</p>") || html.contains("Engineering</p>"), "busiest department by request count");
    assert!(html.contains("4 requests"));
    assert!(html.contains("$12795") && html.contains("5 pending"));
}

#[test]
fn the_scorecard_is_ordered_by_amount_with_approval_rates() {
    let html = get(&client(), "/stats");
    let table = &html[html.find("data-table=\"scorecard\"").unwrap()..];
    let order: Vec<usize> = ["Sales", "Engineering", "Operations", "Design", "Finance"].iter().map(|d| table.find(&format!(">{d}<")).unwrap()).collect();
    assert!(order.windows(2).all(|w| w[0] < w[1]), "{order:?}");
    assert!(text(table).contains("Engineering 4 2 50% $6360"), "{}", text(table));
}

#[test]
fn statistics_follow_new_data() {
    let client = client();
    post(&client, "/requests", VALID);
    post(&client, "/requests/15/review", "decision=approve&comment=");
    let html = get(&client, "/stats");
    assert_eq!(attr_of(&html, "data-bar=\"sales\"", "data-pct"), "100");
    assert!(squash(&html).contains("$23375 in total"), "23255 + 120");
    assert!(squash(&html).contains("8 approved, 2 rejected"));
}

// ── the components are reused ────────────────────────────────────────────────

#[test]
fn components_are_reused_across_pages() {
    let client = client();
    let dashboard = get(&client, "/");
    assert_eq!(count(&dashboard, "data-ui=\"stat\""), 4);
    assert_eq!(count(&dashboard, "data-ui=\"card\""), 3);
    assert_eq!(count(&dashboard, "data-ui=\"bar\""), 3);
    let stats = get(&client, "/stats");
    assert_eq!(count(&stats, "data-ui=\"bar\""), 10, "5 departments and 5 categories");
    assert_eq!(count(&stats, "data-ui=\"stat\""), 4);
}
