use rocket::form::{Context, Contextual, Form};
use rocket::http::Status;
use rocket::request::FlashMessage;
use rocket::response::{content::RawHtml, Flash, Redirect};
use rocket::serde::Serialize;
use rocket::State;

use crate::forms::{Decision, ReviewForm};
use crate::store::{Request, Store};
use crate::templates::Templates;
use crate::view::{page, today, REVIEWER};

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct DetailData<'a, 'b> {
    request: Request,
    #[serde(flatten)]
    form: &'a Context<'b>,
}

fn render(
    store: &Store, tpl: &Templates, id: u32, flash: Option<FlashMessage<'_>>, form: &Context<'_>,
) -> Result<RawHtml<String>, Status> {
    let request = store.0.lock().unwrap().get(id).cloned().ok_or(Status::NotFound)?;
    let title = format!("Request #{id}");
    tpl.render("pages/detail.html", &page(&title, "requests", flash, DetailData { request, form }))
}

#[get("/requests/<id>")]
pub fn show(store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>, id: u32) -> Result<RawHtml<String>, Status> {
    render(store, tpl, id, flash, &Context::default())
}

#[post("/requests/<id>/review", data = "<form>")]
pub fn review<'r>(
    store: &State<Store>, tpl: &State<Templates>, id: u32, form: Form<Contextual<'r, ReviewForm<'r>>>,
) -> Result<Flash<Redirect>, (Status, RawHtml<String>)> {
    let Some(input) = form.value.as_ref() else {
        let html = render(store, tpl, id, None, &form.context).map_err(|s| (s, RawHtml(String::new())))?;
        return Err((form.context.status(), html));
    };
    let decision = if matches!(input.decision, Decision::Approve) { "approved" } else { "rejected" };
    let done = store.0.lock().unwrap().review(id, decision, REVIEWER.name, input.comment.trim(), &today());
    if !done {
        return Ok(Flash::error(Redirect::to(uri!(show(id))), "That request was already reviewed."));
    }
    Ok(Flash::success(Redirect::to(uri!(show(id))), format!("Request #{id} was {decision}.")))
}
