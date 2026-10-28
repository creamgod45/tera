//! ExpenseFlow: submit an expense request, review it, watch the statistics move.
//!
//! A proof of concept for what Tera 2 buys: the Rust side is routing, forms, validation and state, split by area under
//! `controller/`; every pixel of the interface comes from `templates/`, a four level layout and a component library.
//! Nothing here builds HTML.

#[macro_use] extern crate rocket;

mod controller;
mod forms;
mod store;
mod templates;
mod view;
#[cfg(test)] mod tests;

use std::path::Path;

use store::Store;
use templates::Templates;

#[launch]
fn rocket() -> _ {
    let templates = Templates::load(Path::new(rocket::fs::relative!("templates"))).expect("Tera 2 templates");
    rocket::build()
        .manage(templates)
        .manage(Store::seeded())
        .mount("/", controller::routes())
        .register("/", controller::catchers())
}
