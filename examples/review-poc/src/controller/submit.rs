use rocket::form::{Context, Contextual, Form};
use rocket::http::Status;
use rocket::request::FlashMessage;
use rocket::response::{content::RawHtml, Flash, Redirect};
use rocket::serde::Serialize;
use rocket::State;

use super::detail;
use crate::forms::NewRequest;
use crate::store::{Request, Store, CATEGORIES, DEPARTMENTS};
use crate::templates::Templates;
use crate::view::{page, today};

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
pub fn form(tpl: &State<Templates>, flash: Option<FlashMessage<'_>>) -> Result<RawHtml<String>, Status> {
    let form = Context::default();
    tpl.render("pages/new.html", &page("New request", "new", flash,
        FormData { departments: DEPARTMENTS, categories: CATEGORIES, today: today(), form: &form }))
}

#[post("/requests", data = "<form>")]
pub fn create<'r>(
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
    Ok(Flash::success(Redirect::to(uri!(detail::show(id))), format!("Request #{id} was submitted and is waiting for review.")))
}
