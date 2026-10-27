//! ExpenseFlow: submit an expense request, review it, watch the statistics move.
//!
//! A proof of concept for what Tera 2 buys: this file is the application (routes, forms, validation, state); every pixel of
//! the interface comes from `templates/`, a three level layout and a component library. Nothing here builds HTML.

#[macro_use] extern crate rocket;

mod store;
#[cfg(test)] mod tests;

use std::path::Path;

use rocket::State;
use rocket::form::{self, Contextual, Context, Form, FromForm, FromFormField};
use rocket::http::Status;
use rocket::request::FlashMessage;
use rocket::response::{content::RawHtml, Flash, Redirect};
use rocket::serde::Serialize;
use rocket::time::{Date, OffsetDateTime};
use tera::Tera;

use store::{Request, Stats, Store, CATEGORIES, DEPARTMENTS};

// ── Tera ────────────────────────────────────────────────────────────────────

/// Every `*.html.tera` below `templates/`, named by its path without `.tera` (`layouts/app.html`), so `.html` auto-escapes.
struct Templates(Tera);

impl Templates {
    fn load(dir: &Path) -> Result<Self, tera::Error> {
        fn walk(dir: &Path, root: &Path, out: &mut Vec<(String, String)>) {
            for entry in std::fs::read_dir(dir).expect("template directory") {
                let path = entry.expect("template entry").path();
                if path.is_dir() {
                    walk(&path, root, out);
                } else if let Some(name) = path.strip_prefix(root).ok().and_then(|p| p.to_str()).and_then(|p| p.strip_suffix(".tera")) {
                    out.push((name.replace('\\', "/"), std::fs::read_to_string(&path).expect("readable template")));
                }
            }
        }
        let mut files = vec![];
        walk(dir, dir, &mut files);
        let mut tera = Tera::default();
        tera.autoescape_on([".html"]);
        tera.add_raw_templates(files.iter().map(|(n, c)| (n.as_str(), c.as_str())))?;
        Ok(Templates(tera))
    }

    fn render<C: Serialize>(&self, name: &str, context: &C) -> Result<RawHtml<String>, Status> {
        let context = tera::Context::from_serialize(context).map_err(|e| {
            error_!("context for '{}': {}", name, e);
            Status::InternalServerError
        })?;
        self.0.render(name, &context).map(RawHtml).map_err(|e| {
            error_!("template '{}': {}", name, e);
            Status::InternalServerError
        })
    }
}

// ── What every page gets ────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct User {
    name: &'static str,
    role: &'static str,
    initials: &'static str,
}

const REVIEWER: User = User { name: "Morgan Lee", role: "Reviewer", initials: "ML" };

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct Notice {
    kind: String,
    message: String,
}

/// The shell of every page (who is signed in, where we are, the flash message) around the data of the page itself.
#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct Page<'a, T: Serialize> {
    title: &'a str,
    active: &'a str,
    user: &'a User,
    flash: Option<Notice>,
    #[serde(flatten)]
    data: T,
}

fn page<'a, T: Serialize>(title: &'a str, active: &'a str, flash: Option<FlashMessage<'_>>, data: T) -> Page<'a, T> {
    Page { title, active, user: &REVIEWER, flash: flash.map(|f| Notice { kind: f.kind().into(), message: f.message().into() }), data }
}

fn today() -> String {
    OffsetDateTime::now_utc().date().to_string()
}

// ── Forms ───────────────────────────────────────────────────────────────────

#[derive(Debug, FromFormField)]
enum Department { Engineering, Design, Sales, Finance, Operations }

#[derive(Debug, FromFormField)]
enum Category { Travel, Equipment, Software, Training, Meals }

#[derive(Debug, FromFormField)]
enum Decision { Approve, Reject }

macro_rules! name_of {
    ($ty:ident { $($v:ident),* }) => {
        impl $ty { fn name(&self) -> &'static str { match self { $($ty::$v => stringify!($v)),* } } }
    };
}
name_of!(Department { Engineering, Design, Sales, Finance, Operations });
name_of!(Category { Travel, Equipment, Software, Training, Meals });

#[derive(Debug, FromForm)]
struct NewRequest<'v> {
    #[field(validate = len(2..=60))]
    applicant: &'v str,
    #[field(validate = contains('@').or_else(msg!("invalid email address")))]
    email: &'v str,
    department: Department,
    category: Category,
    #[field(validate = range(1..=50000))]
    amount: u32,
    date: Date,
    #[field(validate = len(10..=500))]
    reason: &'v str,
    urgent: bool,
}

fn needs_comment<'v>(comment: &str, decision: &Decision) -> form::Result<'v, ()> {
    if matches!(decision, Decision::Reject) && comment.trim().len() < 5 {
        Err(form::Error::validation("a rejection needs a comment of at least 5 characters"))?;
    }
    Ok(())
}

#[derive(Debug, FromForm)]
struct ReviewForm<'v> {
    decision: Decision,
    #[field(validate = needs_comment(&self.decision))]
    comment: &'v str,
}

// ── Pages ───────────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct DashboardData {
    stats: Stats,
    recent: Vec<Request>,
    waiting: Vec<Request>,
}

#[get("/")]
fn dashboard(store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>) -> Result<RawHtml<String>, Status> {
    let data = store.0.lock().unwrap();
    let mut recent: Vec<Request> = data.items.iter().rev().take(6).cloned().collect();
    recent.sort_by(|a, b| b.id.cmp(&a.id));
    let waiting = data.items.iter().filter(|r| r.status == "pending").cloned().collect();
    let ctx = page("Dashboard", "dashboard", flash, DashboardData { stats: store::stats(&data.items), recent, waiting });
    tpl.render("pages/dashboard.html", &ctx)
}

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct ListData {
    requests: Vec<Request>,
    status: String,
    department: String,
    departments: [&'static str; 5],
    counts: [u32; 4],
}

#[get("/requests?<status>&<department>")]
fn requests(
    store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>,
    status: Option<&str>, department: Option<&str>,
) -> Result<RawHtml<String>, Status> {
    let (status, department) = (status.unwrap_or(""), department.unwrap_or(""));
    let data = store.0.lock().unwrap();
    let all = &data.items;
    let count = |s: &str| all.iter().filter(|r| r.status == s).count() as u32;
    let mut shown: Vec<Request> = all.iter()
        .filter(|r| (status.is_empty() || r.status == status) && (department.is_empty() || r.department == department))
        .cloned().collect();
    shown.sort_by(|a, b| b.id.cmp(&a.id));
    let counts = [all.len() as u32, count("pending"), count("approved"), count("rejected")];
    let ctx = page("Requests", "requests", flash, ListData {
        requests: shown, status: status.into(), department: department.into(), departments: DEPARTMENTS, counts,
    });
    tpl.render("pages/requests.html", &ctx)
}

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct FormData<'a, 'b> {
    departments: [&'static str; 5],
    categories: [&'static str; 5],
    today: String,
    #[serde(flatten)]
    form: &'a Context<'b>,
}

#[get("/requests/new")]
fn new_request(tpl: &State<Templates>, flash: Option<FlashMessage<'_>>) -> Result<RawHtml<String>, Status> {
    let form = Context::default();
    tpl.render("pages/new.html", &page("New request", "new", flash,
        FormData { departments: DEPARTMENTS, categories: CATEGORIES, today: today(), form: &form }))
}

#[post("/requests", data = "<form>")]
fn create<'r>(
    store: &State<Store>, tpl: &State<Templates>, form: Form<Contextual<'r, NewRequest<'r>>>,
) -> Result<Flash<Redirect>, (Status, RawHtml<String>)> {
    let Some(input) = form.value.as_ref() else {
        let ctx = page("New request", "new", None,
            FormData { departments: DEPARTMENTS, categories: CATEGORIES, today: today(), form: &form.context });
        let html = tpl.render("pages/new.html", &ctx).map_err(|s| (s, RawHtml(String::new())))?;
        return Err((form.context.status(), html));
    };
    let id = store.0.lock().unwrap().add(Request {
        id: 0,
        applicant: input.applicant.trim().into(),
        email: input.email.trim().into(),
        department: input.department.name().into(),
        category: input.category.name().into(),
        amount: input.amount,
        date: input.date.to_string(),
        reason: input.reason.trim().into(),
        urgent: input.urgent,
        status: "pending",
        submitted_at: today(),
        review: None,
    });
    Ok(Flash::success(Redirect::to(uri!(detail(id))), format!("Request #{id} was submitted and is waiting for review.")))
}

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct DetailData<'a, 'b> {
    request: Request,
    #[serde(flatten)]
    form: &'a Context<'b>,
}

fn render_detail(
    store: &Store, tpl: &Templates, id: u32, flash: Option<FlashMessage<'_>>, form: &Context<'_>,
) -> Result<RawHtml<String>, Status> {
    let request = store.0.lock().unwrap().get(id).cloned().ok_or(Status::NotFound)?;
    let title = format!("Request #{id}");
    tpl.render("pages/detail.html", &page(&title, "requests", flash, DetailData { request, form }))
}

#[get("/requests/<id>")]
fn detail(store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>, id: u32) -> Result<RawHtml<String>, Status> {
    render_detail(store, tpl, id, flash, &Context::default())
}

#[post("/requests/<id>/review", data = "<form>")]
fn review<'r>(
    store: &State<Store>, tpl: &State<Templates>, id: u32, form: Form<Contextual<'r, ReviewForm<'r>>>,
) -> Result<Flash<Redirect>, (Status, RawHtml<String>)> {
    let Some(input) = form.value.as_ref() else {
        let html = render_detail(store, tpl, id, None, &form.context).map_err(|s| (s, RawHtml(String::new())))?;
        return Err((form.context.status(), html));
    };
    let decision = if matches!(input.decision, Decision::Approve) { "approved" } else { "rejected" };
    let done = store.0.lock().unwrap().review(id, decision, REVIEWER.name, input.comment.trim(), &today());
    if !done {
        return Ok(Flash::error(Redirect::to(uri!(detail(id))), "That request was already reviewed."));
    }
    Ok(Flash::success(Redirect::to(uri!(detail(id))), format!("Request #{id} was {decision}.")))
}

#[get("/stats")]
fn statistics(store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>) -> Result<RawHtml<String>, Status> {
    let stats = store::stats(&store.0.lock().unwrap().items);
    #[derive(Serialize)]
    #[serde(crate = "rocket::serde")]
    struct Data { stats: Stats }
    tpl.render("pages/stats.html", &page("Statistics", "stats", flash, Data { stats }))
}

#[catch(404)]
fn not_found(req: &rocket::Request<'_>) -> RawHtml<String> {
    let tpl = req.rocket().state::<Templates>().expect("templates are managed");
    #[derive(Serialize)]
    #[serde(crate = "rocket::serde")]
    struct Data { path: String }
    tpl.render("pages/not_found.html", &page("Not found", "", None, Data { path: req.uri().to_string() }))
        .unwrap_or_else(|_| RawHtml("Not found".into()))
}

#[launch]
fn rocket() -> _ {
    let templates = Templates::load(Path::new(rocket::fs::relative!("templates"))).expect("Tera 2 templates");
    rocket::build()
        .manage(templates)
        .manage(Store::seeded())
        .mount("/", routes![dashboard, requests, new_request, create, detail, review, statistics])
        .register("/", catchers![not_found])
}
